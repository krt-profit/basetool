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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Blueprint;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintIngredient;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintIngredientKind;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintRequirementGroup;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.joborder.api.CreateJobOrderItemLineDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.CreateJobOrderItemMaterialDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItem;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItemMaterial;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;

/** Verifies exact public ITEM snapshots from the canonical grouped recipe and quality choices. */
@SpringBootTest
@WithMockUser(roles = "ADMIN")
class ItemOrderDerivationContractIntegrationTest extends MaterialContributionTestSupport {
  @Autowired private BlueprintRepository blueprints;
  @Autowired private GameItemRepository gameItems;

  /** The explicit recipe output and its four independently identified cost materials. */
  private record Recipe(UUID outputId, UUID retainedId, UUID blueprintId, List<UUID> materials) {}

  @Test
  void itemEditKeepsCheapestMandatoryGroupsRecipeOrderTiesAndOnlyDerivedQualityChoices()
      throws Exception {
    Fixture fixture = seed();
    Recipe recipe = groupedRecipe(fixture.orderId());
    var before = jobOrderQueryService.getJobOrderById(fixture.orderId());
    var line =
        new CreateJobOrderItemLineDto(
            null,
            recipe.retainedId(),
            recipe.blueprintId(),
            2,
            List.of(
                new CreateJobOrderItemMaterialDto(recipe.materials().get(1), "GOOD"),
                new CreateJobOrderItemMaterialDto(recipe.materials().get(2), "NOT_A_TIER"),
                new CreateJobOrderItemMaterialDto(recipe.materials().get(3), "NOT_A_TIER")),
            1,
            null);
    var request =
        new CreateJobOrderItemRequestDto(
            null, null, before.handle(), null, List.of(line), before.version());
    String body =
        mvc()
            .perform(
                put("/api/v1/orders/{id}/items", fixture.orderId())
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JobOrderDto result = json.readValue(body, JobOrderDto.class);
    assertThat(result.items()).hasSize(1);
    assertThat(result.items().getFirst().gameItem())
        .isNotNull()
        .extracting(de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemReferenceDto::id)
        .isEqualTo(recipe.outputId());
    var materials = result.items().getFirst().materials();
    assertThat(materials)
        .extracting(row -> row.material().id())
        .containsExactlyInAnyOrder(recipe.materials().get(0), recipe.materials().get(1));
    assertThat(
            materials.stream()
                .collect(
                    Collectors.toMap(row -> row.material().id(), row -> row.requiredQuantity())))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(recipe.materials().get(0), 0.06, recipe.materials().get(1), 0.04));
    assertThat(
            materials.stream()
                .collect(
                    Collectors.toMap(row -> row.material().id(), row -> row.qualityRequirement())))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(recipe.materials().get(0), "NONE", recipe.materials().get(1), "GOOD"));
  }

  @NotNull
  private Recipe groupedRecipe(@NotNull UUID orderId) {
    return transactionTemplate.execute(_ -> {
      var order = jobOrderRepository.findById(orderId).orElseThrow();
      order.setType(JobOrderType.ITEM);
      order.getMaterials().clear();
      GameItem output = new GameItem();
      output.setName("Grouped output " + UUID.randomUUID());
      output = gameItems.save(output);
      Blueprint recipe = new Blueprint();
      recipe.setOutputItem(output);
      recipe.setOutputName(output.getName());
      recipe.setRequiredGroupCount(2);
      double[] quantities = {0.03, 0.02, 0.03, 0.01};
      List<UUID> materialIds = new ArrayList<>();
      for (int index = 0; index < quantities.length; index++) {
        Material material = new Material();
        material.setName("Grouped cost " + index + " " + UUID.randomUUID());
        material.setType(MaterialType.RAW);
        material = materialRepository.save(material);
        materialIds.add(material.getId());
        BlueprintRequirementGroup group = new BlueprintRequirementGroup();
        group.setOrderIndex(index);
        group.setKind("group");
        group.setRequiredCount(1);
        group.setOptional(index == 3);
        recipe.addRequirementGroup(group);
        BlueprintIngredient ingredient = new BlueprintIngredient();
        ingredient.setOrderIndex(index);
        ingredient.setKind(BlueprintIngredientKind.RESOURCE);
        ingredient.setMaterial(material);
        ingredient.setQuantityScu(quantities[index]);
        ingredient.setRequirementGroup(group);
        recipe.addIngredient(ingredient);
      }
      recipe = blueprints.saveAndFlush(recipe);
      JobOrderItem existing =
          JobOrderItem.builder().gameItem(output).blueprint(recipe).amount(1).build();
      existing.addMaterial(
          JobOrderItemMaterial.builder()
              .material(materialRepository.getReferenceById(materialIds.get(0)))
              .requiredQuantity(0.03)
              .qualityTier(qualityTierRepository.findByCode("NONE").orElseThrow())
              .build());
      existing.addMaterial(
          JobOrderItemMaterial.builder()
              .material(materialRepository.getReferenceById(materialIds.get(1)))
              .requiredQuantity(0.02)
              .qualityTier(qualityTierRepository.findByCode("GOOD").orElseThrow())
              .build());
      order.addItem(existing);
      jobOrderRepository.saveAndFlush(order);
      UUID retainedId = UUID.randomUUID();
      jdbc.update(
          "INSERT INTO game_item_redirect (id, canonical_id, retired_snapshot)"
              + " VALUES (?, ?, '{}'::jsonb)",
          retainedId,
          output.getId());
      return new Recipe(output.getId(), retainedId, recipe.getId(), List.copyOf(materialIds));
    });
  }
}
