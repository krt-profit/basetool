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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryItemDto;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryQualityCorrectionOptionDto;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockBookingReason;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.kernel.PageResponse;
import de.greluc.krt.profit.basetool.backend.kernel.ScuAmounts;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.core.type.TypeReference;

/** Verifies that a connected SCU quality split preserves every ordinary allocation quantum. */
@SpringBootTest
@WithMockUser(roles = "ADMIN")
class MaterialProvisionScuQualitySplitIntegrationTest extends MaterialContributionTestSupport {
  @ParameterizedTest
  @ValueSource(ints = {4, 5})
  void correctingKnownScuPortionPreservesPhysicalAndAllocatedQuantity(int sliceCount)
      throws Exception {
    Fixture fixture = seed();
    var claim =
        materialClaimService.upsertClaim(
            fixture.orderId(), claim(fixture.materialId(), fixture.squadronAId(), 0.002));
    StockFixture source = stock(fixture, 0.002);
    provide(fixture, claim, source, 0.002);
    UUID merged =
        transactionTemplate.execute(_ -> {
          var row = stockReads.findRow(source.rowId()).orElseThrow();
          return stockCommands.bookIntoPool(
              source.holderId(),
              row.getOwningOrgUnit(),
              new StockLot(row.getMaterial(), null, row.getLocation(), 650, false),
              ScuAmounts.fromQuanta(sliceCount - 2L),
              StockBookingReason.PURCHASE);
        });
    assertThat(merged).isNotEqualTo(source.rowId());
    assertThat(stockReads.findRow(source.rowId())).isEmpty();
    List<UUID> orders = matchingOrders(fixture, sliceCount);
    setAllocation(
        patch("/api/v1/inventory/{id}/allocation", merged),
        source,
        merged,
        fixture.orderId(),
        0.001);
    for (UUID order : orders.subList(1, orders.size())) {
      setAllocation(
          post("/api/v1/inventory/{id}/allocation", merged), source, merged, order, 0.001);
    }
    var beforeRows = currentRows(fixture, source);
    assertThat(beforeRows).hasSize(1);
    assertThat(ScuAmounts.toQuanta(beforeRows.getFirst().amount())).isEqualTo(sliceCount);
    assertThat(beforeRows.getFirst().jobOrderAllocations()).hasSize(sliceCount);
    assertThat(beforeRows.getFirst().jobOrderAllocations())
        .allSatisfy(allocation -> assertThat(allocation.amount()).isEqualTo(0.001));
    long stockVersion = beforeRows.getFirst().version();
    var before = contributionHistory(fixture.orderId(), claim.id());
    var receipt = before.provisions().getFirst();
    assertThat(receipt.quantityAmount()).isEqualTo(0.002);
    assertThat(receipt.amount()).isEqualTo(0.002);
    assertThat(qualityRows(fixture.orderId(), receipt.id(), source.holderId()))
        .singleElement()
        .satisfies(option -> {
          assertThat(option.id()).isEqualTo(merged);
          assertThat(ScuAmounts.toQuanta(option.amount())).isEqualTo(sliceCount);
          assertThat(option.affectedAmount()).isEqualTo(0.002);
          assertThat(option.version()).isEqualTo(stockVersion);
        });
    var correction =
        json.createObjectNode()
            .put("kind", "QUALITY")
            .put("version", receipt.version())
            .put("claimVersion", before.version())
            .put("amount", 0.002)
            .put("targetClaimId", claim.id().toString())
            .put("targetClaimVersion", before.version())
            .put("suppliedQuality", 700);
    correction.putArray("qualityStockRowIds").add(merged.toString());
    correction
        .putArray("stockVersions")
        .addObject()
        .put("id", merged.toString())
        .put("version", stockVersion);

    mvc()
        .perform(
            post(
                    "/api/v1/orders/{order}/claims/provisions/{receipt}/correction",
                    fixture.orderId(),
                    receipt.id())
                .with(
                    jwt()
                        .jwt(token -> token.subject(source.holderId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(correction.toString()))
        .andExpect(result ->
            assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200));

    var afterRows = currentRows(fixture, source);
    assertThat(afterRows).hasSize(2);
    assertThat(afterRows).extracting(InventoryItemDto::quality).containsExactlyInAnyOrder(650, 700);
    assertThat(afterRows)
        .allSatisfy(row -> {
          assertThat(ScuAmounts.toQuanta(row.amount()))
              .isEqualTo(row.quality() == 700 ? 2L : sliceCount - 2L);
          assertThat(
                  row.jobOrderAllocations().stream()
                      .mapToLong(allocation -> ScuAmounts.toQuanta(allocation.amount()))
                      .sum())
              .isEqualTo(ScuAmounts.toQuanta(row.amount()));
          assertThat(row.jobOrderRest()).isZero();
          assertThat(row.jobOrderAllocations())
              .allSatisfy(allocation ->
                  assertThat(allocation.amount()).isPositive().isLessThanOrEqualTo(row.amount()));
        });
    assertThat(afterRows.stream().mapToLong(row -> ScuAmounts.toQuanta(row.amount())).sum())
        .isEqualTo(sliceCount);
    for (UUID order : orders) {
      assertThat(
              afterRows.stream()
                  .flatMap(row -> row.jobOrderAllocations().stream())
                  .filter(allocation -> allocation.jobOrderId().equals(order))
                  .mapToLong(allocation -> ScuAmounts.toQuanta(allocation.amount()))
                  .sum())
          .as("retained allocation of %s", order)
          .isEqualTo(1L);
    }
    assertThat(stockReads.findRow(merged).orElseThrow().getVersion()).isGreaterThan(stockVersion);
    var after = contributionHistory(fixture.orderId(), claim.id());
    assertThat(after.version()).isGreaterThan(before.version());
    assertThat(after.openAmount()).isZero();
    assertThat(after.creditedAmount()).isEqualTo(0.002);
    var corrected = after.provisions().getFirst();
    assertThat(corrected.id()).isEqualTo(receipt.id());
    assertThat(corrected.version()).isGreaterThan(receipt.version());
    assertThat(corrected.quantityAmount()).isEqualTo(0.002);
    assertThat(corrected.amount()).isEqualTo(0.002);
    assertThat(corrected.suppliedQuality()).isEqualTo(700);
    assertThat(corrected.factChanges()).hasSize(2);
    assertThat(corrected.factChanges().getFirst()).isEqualTo(receipt.factChanges().getFirst());
    assertThat(corrected.factChanges().getLast().previous().suppliedQuality()).isEqualTo(650);
    assertThat(corrected.factChanges().getLast().current().suppliedQuality()).isEqualTo(700);
    assertThat(qualityRows(fixture.orderId(), receipt.id(), source.holderId()))
        .singleElement()
        .satisfies(option -> {
          assertThat(option.quality()).isEqualTo(700);
          assertThat(option.amount()).isEqualTo(0.002);
          assertThat(option.affectedAmount()).isEqualTo(0.002);
          assertThat(option.version())
              .isEqualTo(stockReads.findRow(option.id()).orElseThrow().getVersion());
        });
  }

  /** Commits the valid orders receiving the stock's independent allocation slices. */
  private List<UUID> matchingOrders(Fixture fixture, int sliceCount) {
    return transactionTemplate.execute(_ -> {
      var original = jobOrderRepository.findById(fixture.orderId()).orElseThrow();
      List<UUID> orders = new ArrayList<>();
      orders.add(original.getId());
      for (int index = 0; index < sliceCount - 1; index++) {
        JobOrder sibling =
            JobOrder.builder()
                .responsibleOrgUnit(original.getResponsibleOrgUnit())
                .requestingOrgUnit(original.getRequestingOrgUnit())
                .handle("scu-split-" + index)
                .status(JobOrderStatus.OPEN)
                .build();
        sibling.addMaterial(
            JobOrderMaterial.builder()
                .material(materialRepository.getReferenceById(fixture.materialId()))
                .qualityTier(qualityTierRepository.findByCode("GOOD").orElseThrow())
                .amount(10.0)
                .build());
        orders.add(jobOrderRepository.save(sibling).getId());
      }
      return List.copyOf(orders);
    });
  }

  /** Applies one ordinary versioned allocation through the public Inventory route. */
  private void setAllocation(
      MockHttpServletRequestBuilder request,
      StockFixture source,
      UUID row,
      UUID order,
      double amount)
      throws Exception {
    var payload =
        json.createObjectNode()
            .put("field", "JOB_ORDER")
            .put("targetId", order.toString())
            .put("amount", amount)
            .put("version", stockReads.findRow(row).orElseThrow().getVersion());
    mvc()
        .perform(
            request
                .with(
                    jwt()
                        .jwt(token -> token.subject(source.holderId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload.toString()))
        .andExpect(status().isOk());
  }

  /** Reads the holder's complete current material stock through the authenticated public API. */
  private List<InventoryItemDto> currentRows(Fixture fixture, StockFixture source)
      throws Exception {
    String body =
        mvc()
            .perform(
                get("/api/v1/inventory/material/{id}", fixture.materialId())
                    .with(
                        jwt()
                            .jwt(token -> token.subject(source.holderId().toString()))
                            .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    PageResponse<InventoryItemDto> page = json.readValue(body, new TypeReference<>() {});
    assertThat(page.totalElements()).isEqualTo(page.content().size());
    return page.content();
  }

  /** Reads the explicitly correctable known quantity through the public quality options. */
  private List<InventoryQualityCorrectionOptionDto> qualityRows(
      UUID order, UUID receipt, UUID holder) throws Exception {
    String body =
        mvc()
            .perform(
                get(
                        "/api/v1/orders/{order}/claims/provisions/{receipt}/quality-options",
                        order,
                        receipt)
                    .with(
                        jwt()
                            .jwt(token -> token.subject(holder.toString()))
                            .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readValue(body, new TypeReference<List<InventoryQualityCorrectionOptionDto>>() {});
  }
}
