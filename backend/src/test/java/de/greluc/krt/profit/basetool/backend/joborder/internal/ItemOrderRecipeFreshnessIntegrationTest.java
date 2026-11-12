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
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Blueprint;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintIngredient;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintIngredientKind;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.CreateJobOrderItemLineDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItem;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItemMaterial;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderType;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;

/**
 * Verifies a waiting public ITEM edit refuses current scalar recipe drift without changing scope.
 */
@SpringBootTest
@WithMockUser(roles = "ADMIN")
class ItemOrderRecipeFreshnessIntegrationTest extends MaterialContributionTestSupport {
  @Autowired private BlueprintRepository blueprints;
  @Autowired private GameItemRepository gameItems;

  /** The same recipe and output identities used before and after the catalogue fact changes. */
  private record Recipe(UUID blueprintId, UUID outputId) {}

  @Test
  void waitingItemEditRefusesSameIdentityQuantityDriftAndAnExplicitFreshRetryUsesTheNewRecipe()
      throws Exception {
    Fixture fixture = seed();
    Recipe recipe = itemRecipe(fixture);
    StockFixture stock = stock(fixture, 1.0);
    var before = jobOrderQueryService.getJobOrderById(fixture.orderId());
    var line =
        new CreateJobOrderItemLineDto(
            before.items().getFirst().id(),
            recipe.outputId(),
            recipe.blueprintId(),
            2,
            List.of(),
            null,
            null);
    var update =
        new CreateJobOrderItemRequestDto(
            null, null, before.handle(), null, List.of(line), before.version());
    var allocation =
        Map.of(
            "field",
            "JOB_ORDER",
            "targetId",
            fixture.orderId(),
            "amount",
            1.0,
            "version",
            stockReads.findRow(stock.rowId()).orElseThrow().getVersion());
    CountDownLatch allocated = new CountDownLatch(1);
    CountDownLatch commitAllocation = new CountDownLatch(1);
    AtomicInteger holderPid = new AtomicInteger();
    AtomicInteger editPid = new AtomicInteger();
    try (var executor = Executors.newFixedThreadPool(2)) {
      var holding =
          executor.submit(() ->
              transactionTemplate.execute(_ ->
                  assertDoesNotThrow(() -> {
                    holderPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                    int response =
                        mvc()
                            .perform(
                                post("/api/v1/inventory/{id}/allocation", stock.rowId())
                                    .with(
                                        jwt()
                                            .jwt(
                                                token -> token.subject(stock.holderId().toString()))
                                            .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(json.writeValueAsString(allocation)))
                            .andReturn()
                            .getResponse()
                            .getStatus();
                    assertThat(response).isEqualTo(200);
                    allocated.countDown();
                    assertThat(commitAllocation.await(20, TimeUnit.SECONDS)).isTrue();
                    return response;
                  })));
      try {
        assertThat(allocated.await(15, TimeUnit.SECONDS)).isTrue();
        var editing =
            executor.submit(() ->
                transactionTemplate.execute(transaction ->
                    assertDoesNotThrow(() -> {
                      editPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                      int response =
                          mvc()
                              .perform(
                                  put("/api/v1/orders/{id}/items", fixture.orderId())
                                      .with(
                                          jwt()
                                              .authorities(
                                                  new SimpleGrantedAuthority("ROLE_ADMIN")))
                                      .contentType(MediaType.APPLICATION_JSON)
                                      .content(json.writeValueAsString(update)))
                              .andReturn()
                              .getResponse()
                              .getStatus();
                      if (response >= 400) {
                        transaction.setRollbackOnly();
                      }
                      return response;
                    })));
        await()
            .atMost(Duration.ofSeconds(10))
            .until(() -> editing.isDone() || blockedBy(editPid.get(), holderPid.get()));
        assertThat(blockedBy(editPid.get(), holderPid.get()))
            .as("the prepared ITEM edit really waits for the ordinary allocation transaction")
            .isTrue();
        transactionTemplate.executeWithoutResult(_ -> {
          Blueprint current = blueprints.findById(recipe.blueprintId()).orElseThrow();
          current.getIngredients().getFirst().setQuantityScu(20.0);
          blueprints.saveAndFlush(current);
        });
        commitAllocation.countDown();
        assertThat(holding.get(15, TimeUnit.SECONDS)).isEqualTo(200);
        assertThat(editing.get(15, TimeUnit.SECONDS)).isEqualTo(409);
      } finally {
        commitAllocation.countDown();
      }
    }
    var refused = jobOrderQueryService.getJobOrderById(fixture.orderId());
    assertThat(refused.version()).isEqualTo(before.version());
    assertThat(refused.items().getFirst().amount()).isEqualTo(1);
    assertThat(refused.items().getFirst().materials().getFirst().requiredQuantity())
        .isEqualTo(10.0);
    assertThat(onlyBucket(fixture.orderId()).openRemaining()).isEqualTo(9.0);
    var retry =
        new CreateJobOrderItemRequestDto(
            null, null, refused.handle(), null, List.of(line), refused.version());
    String body =
        mvc()
            .perform(
                put("/api/v1/orders/{id}/items", fixture.orderId())
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(retry)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JobOrderDto fresh = json.readValue(body, JobOrderDto.class);
    assertThat(fresh.items().getFirst().amount()).isEqualTo(2);
    assertThat(fresh.items().getFirst().materials().getFirst().requiredQuantity()).isEqualTo(40.0);
    assertThat(onlyBucket(fixture.orderId()).openRemaining()).isEqualTo(39.0);
  }

  private Recipe itemRecipe(Fixture fixture) {
    return transactionTemplate.execute(_ -> {
      GameItem output = new GameItem();
      output.setName("Fresh recipe output " + UUID.randomUUID());
      output = gameItems.save(output);
      Blueprint recipe = new Blueprint();
      recipe.setOutputItem(output);
      recipe.setOutputName(output.getName());
      BlueprintIngredient ingredient = new BlueprintIngredient();
      ingredient.setOrderIndex(0);
      ingredient.setKind(BlueprintIngredientKind.RESOURCE);
      ingredient.setMaterial(materialRepository.getReferenceById(fixture.materialId()));
      ingredient.setQuantityScu(10.0);
      ingredient.setMinQuality(650);
      recipe.addIngredient(ingredient);
      recipe = blueprints.saveAndFlush(recipe);
      var order = jobOrderRepository.findById(fixture.orderId()).orElseThrow();
      order.setType(JobOrderType.ITEM);
      order.getMaterials().clear();
      JobOrderItem item =
          JobOrderItem.builder().gameItem(output).blueprint(recipe).amount(1).build();
      item.addMaterial(
          JobOrderItemMaterial.builder()
              .material(materialRepository.getReferenceById(fixture.materialId()))
              .requiredQuantity(10.0)
              .qualityTier(qualityTierRepository.findByCode("GOOD").orElseThrow())
              .build());
      order.addItem(item);
      jobOrderRepository.saveAndFlush(order);
      return new Recipe(recipe.getId(), output.getId());
    });
  }

  private boolean blockedBy(int waiter, int blocker) {
    return waiter > 0
        && Boolean.TRUE.equals(
            jdbc.queryForObject(
                "SELECT ? = ANY(pg_blocking_pids(?))", Boolean.class, blocker, waiter));
  }
}
