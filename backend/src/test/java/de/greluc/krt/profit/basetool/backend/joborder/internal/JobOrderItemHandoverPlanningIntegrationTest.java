/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Blueprint;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryAllocations;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItem;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItem;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;

/** Verifies complete multi-item handovers through the authenticated public API. */
@SpringBootTest
@WithMockUser(roles = "ADMIN")
class JobOrderItemHandoverPlanningIntegrationTest extends MaterialContributionTestSupport {
  @Autowired private GameItemRepository gameItems;
  @Autowired private BlueprintRepository blueprints;
  @Autowired private InventoryItemRepository inventory;

  private record Source(UUID lineId, UUID rowId, UUID holderId) {}

  @Test
  void oneHandoverConsumesBothItemPrefixesWithoutTouchingTheirRemainingStock() throws Exception {
    Fixture fixture = seed();
    List<Source> sources = seedFinishedStock(fixture);
    var request =
        new JobOrderItemHandoverCreateDto(
            Instant.now(),
            "Handover recipient",
            sources.stream()
                .map(source -> new JobOrderItemHandoverEntryCreateDto(source.lineId(), 1))
                .toList());

    mvc()
        .perform(
            post("/api/v1/orders/{id}/item-handovers", fixture.orderId())
                .with(
                    jwt()
                        .jwt(token -> token.subject(sources.getFirst().holderId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request)))
        .andExpect(result ->
            assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(201));

    transactionTemplate.executeWithoutResult(_ -> {
      for (Source source : sources) {
        var remaining = stockReads.findRow(source.rowId()).orElseThrow();
        assertThat(remaining.getAmount()).isEqualTo(1.0);
        assertThat(remaining.jobOrderSliceAmount(fixture.orderId())).isEqualTo(1.0);
      }
    });
    String body =
        mvc()
            .perform(
                get("/api/v1/orders/{id}", fixture.orderId())
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var current =
        json.readValue(body, de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderDto.class);
    assertThat(current.status().name()).isEqualTo("OPEN");
    assertThat(current.items())
        .hasSize(2)
        .allSatisfy(line -> {
          assertThat(line.deliveredAmount()).isEqualTo(1);
          assertThat(line.manufacturedAmount()).isEqualTo(2);
          assertThat(line.amount()).isEqualTo(3);
        });
  }

  private List<Source> seedFinishedStock(Fixture fixture) {
    return transactionTemplate.execute(_ -> {
      var order = jobOrderRepository.findById(fixture.orderId()).orElseThrow();
      order.setType(JobOrderType.ITEM);
      order.getMaterials().clear();
      List<Source> result = new ArrayList<>();
      for (int index = 0; index < 2; index++) {
        GameItem item = new GameItem();
        item.setName("Handover output " + UUID.randomUUID());
        item = gameItems.saveAndFlush(item);
        Blueprint blueprint = new Blueprint();
        blueprint.setOutputItem(item);
        blueprint.setOutputName(item.getName());
        blueprint = blueprints.saveAndFlush(blueprint);
        JobOrderItem line =
            JobOrderItem.builder()
                .jobOrder(order)
                .gameItem(item)
                .blueprint(blueprint)
                .amount(3)
                .manufacturedAmount(2)
                .build();
        order.getItems().add(line);
      }
      jobOrderRepository.saveAndFlush(order);
      for (var line : order.getItems()) {
        var holder = new de.greluc.krt.profit.basetool.backend.identity.api.User();
        holder.setId(UUID.randomUUID());
        holder.setUsername("handover-" + UUID.randomUUID());
        holder = users.saveAndFlush(holder);
        UUID locationId = UUID.randomUUID();
        de.greluc.krt.profit.basetool.backend.support.TestPlaces.insertLocation(
            jdbc, locationId, "Handover " + locationId);
        jdbc.update("UPDATE location SET version = 0 WHERE id = ?", locationId);
        InventoryItem row = new InventoryItem();
        row.setUser(holder);
        row.setGameItem(line.getGameItem());
        row.setLocation(locations.findById(locationId).orElseThrow());
        row.setAmount(2.0);
        row.setPersonal(false);
        InventoryAllocations.addJobOrder(row, fixture.orderId(), 2.0, false);
        row = inventory.saveAndFlush(row);
        result.add(new Source(line.getId(), row.getId(), holder.getId()));
      }
      return List.copyOf(result);
    });
  }
}
