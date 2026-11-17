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

import static de.greluc.krt.profit.basetool.backend.support.StatementCounter.measured;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Blueprint;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintIngredient;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintIngredientKind;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.joborder.api.CreateJobOrderItemLineDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItem;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItemMaterial;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItemPlan;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItemService;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderType;
import de.greluc.krt.profit.basetool.backend.support.StatementCounter.Measured;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;

/** Verifies the published ITEM batch planner reads distinct persisted snapshots without N+1. */
@SpringBootTest
@WithMockUser(roles = "ADMIN")
class ItemOrderPersistedPlanQueryIntegrationTest extends MaterialContributionTestSupport {
  @Autowired private BlueprintRepository blueprints;
  @Autowired private GameItemRepository gameItems;
  @Autowired private JobOrderItemService itemService;

  /**
   * Real persisted lines, their ordered requested edits and their distinct exact material scopes.
   */
  private record OrderFixture(
      UUID orderId, List<CreateJobOrderItemLineDto> inputs, Set<UUID> materialIds) {}

  @Test
  void preparingThreeOrThirtyDistinctKeptLinesHasTheSameReadCountAndNoMutation() throws Exception {
    OrderFixture few = itemOrder(3);
    OrderFixture many = itemOrder(30);
    Measured<JobOrderItemPlan> small = prepare(few);
    Measured<JobOrderItemPlan> large = prepare(many);
    assertThat(small.result().materialIds()).containsExactlyInAnyOrderElementsOf(few.materialIds());
    assertThat(large.result().materialIds())
        .containsExactlyInAnyOrderElementsOf(many.materialIds());
    assertThat(small.result().lines())
        .extracting(JobOrderItemPlan.Line::payload)
        .containsExactlyElementsOf(few.inputs());
    assertThat(large.result().lines())
        .extracting(JobOrderItemPlan.Line::payload)
        .containsExactlyElementsOf(many.inputs());
    assertThat(large.result().lines())
        .allSatisfy(line -> {
          assertThat(line.materials()).hasSize(1);
          assertThat(line.materials().getFirst().requiredQuantity()).isEqualTo(2.0);
        });
    assertThat(large.statements())
        .as("SQL preparation reads for thirty versus three distinct persisted ITEM lines")
        .isPositive()
        .isEqualTo(small.statements());
    assertUnchanged(few, 3);
    assertUnchanged(many, 30);
  }

  private Measured<JobOrderItemPlan> prepare(OrderFixture fixture) throws Exception {
    return measured(() ->
        transactionTemplate.execute(_ -> {
          var order = jobOrderRepository.findById(fixture.orderId()).orElseThrow();
          return itemService.prepareItemLines(fixture.inputs(), order.getItems());
        }));
  }

  private void assertUnchanged(OrderFixture fixture, int count) throws Exception {
    String body =
        mvc()
            .perform(
                get("/api/v1/orders/{id}", fixture.orderId())
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JobOrderDto current = json.readValue(body, JobOrderDto.class);
    assertThat(current.items())
        .hasSize(count)
        .allSatisfy(line -> {
          assertThat(line.amount()).isEqualTo(1);
          assertThat(line.materials()).hasSize(1);
          assertThat(line.materials().getFirst().requiredQuantity()).isEqualTo(1.0);
        });
  }

  private OrderFixture itemOrder(int count) {
    Fixture root = seed();
    return transactionTemplate.execute(_ -> {
      var order = jobOrderRepository.findById(root.orderId()).orElseThrow();
      order.setType(JobOrderType.ITEM);
      order.getMaterials().clear();
      List<JobOrderItem> items = new ArrayList<>();
      List<UUID> materialIds = new ArrayList<>();
      for (int index = 0; index < count; index++) {
        Material material = new Material();
        material.setName("Batch cost " + UUID.randomUUID());
        material.setType(MaterialType.RAW);
        material = materialRepository.save(material);
        materialIds.add(material.getId());
        GameItem output = new GameItem();
        output.setName("Batch output " + UUID.randomUUID());
        output = gameItems.save(output);
        Blueprint recipe = new Blueprint();
        recipe.setOutputItem(output);
        recipe.setOutputName(output.getName());
        BlueprintIngredient ingredient = new BlueprintIngredient();
        ingredient.setOrderIndex(0);
        ingredient.setKind(BlueprintIngredientKind.RESOURCE);
        ingredient.setMaterial(material);
        ingredient.setQuantityScu(1.0);
        recipe.addIngredient(ingredient);
        recipe = blueprints.save(recipe);
        JobOrderItem item =
            JobOrderItem.builder().gameItem(output).blueprint(recipe).amount(1).build();
        item.addMaterial(
            JobOrderItemMaterial.builder()
                .material(material)
                .requiredQuantity(1.0)
                .qualityTier(qualityTierRepository.findByCode("NONE").orElseThrow())
                .build());
        if (!items.isEmpty()) {
          item.setParentItem(items.getFirst());
        }
        order.addItem(item);
        items.add(item);
      }
      jobOrderRepository.saveAndFlush(order);
      List<CreateJobOrderItemLineDto> inputs = new ArrayList<>();
      for (int index = 0; index < items.size(); index++) {
        JobOrderItem item = items.get(index);
        inputs.add(
            new CreateJobOrderItemLineDto(
                item.getId(),
                item.getGameItem().getId(),
                item.getBlueprint().getId(),
                2,
                List.of(),
                index + 1,
                index == 0 ? null : 1));
      }
      return new OrderFixture(order.getId(), List.copyOf(inputs), Set.copyOf(materialIds));
    });
  }
}
