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

package de.greluc.krt.profit.basetool.backend.joborder.api;

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
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import jakarta.persistence.EntityManagerFactory;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

/** Verifies the public ITEM preview keeps exact bridged requirements without per-cost reads. */
@SpringBootTest
class ItemOrderPreviewQueryIntegrationTest {
  @Autowired private org.springframework.web.context.WebApplicationContext context;
  @Autowired private tools.jackson.databind.ObjectMapper json;
  @Autowired private BlueprintRepository blueprints;
  @Autowired private GameItemRepository gameItems;
  @Autowired private MaterialRepository materials;
  @Autowired private TransactionTemplate transactions;
  @Autowired private EntityManagerFactory entityManagerFactory;

  /** The explicitly selected recipe and the independent material identities its costs require. */
  private record Fixture(UUID blueprintId, Set<UUID> materialIds) {}

  @Test
  void previewReadsRemainBoundedWhenRequiredItemBridgesGrowFromThreeToThirty() throws Exception {
    Fixture few = recipe(3);
    Fixture many = recipe(30);
    var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    boolean enabled = statistics.isStatisticsEnabled();
    statistics.setStatisticsEnabled(true);
    try {
      statistics.clear();
      ItemDerivationDto small = preview(few.blueprintId());
      long fewQueries = statistics.getPrepareStatementCount();
      statistics.clear();
      ItemDerivationDto large = preview(many.blueprintId());
      long manyQueries = statistics.getPrepareStatementCount();
      assertThat(small.materials())
          .extracting(row -> row.material().id())
          .containsExactlyInAnyOrderElementsOf(few.materialIds());
      assertThat(large.materials())
          .extracting(row -> row.material().id())
          .containsExactlyInAnyOrderElementsOf(many.materialIds());
      assertThat(large.materials())
          .extracting(DerivedMaterialDto::requiredQuantity)
          .containsOnly(2.0);
      assertThat(large.subAssemblies()).isEmpty();
      assertThat(large.unresolvedIngredients()).isEmpty();
      assertThat(manyQueries)
          .as("preview query count for thirty versus three required ITEM bridges")
          .isEqualTo(fewQueries);
    } finally {
      statistics.setStatisticsEnabled(enabled);
    }
  }

  private ItemDerivationDto preview(UUID id) throws Exception {
    var mvc =
        org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
            .apply(
                org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                    .springSecurity())
            .build();
    String body =
        mvc.perform(get("/api/v1/blueprints/{id}/derivation", id).param("amount", "2").with(jwt()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readValue(body, ItemDerivationDto.class);
  }

  private Fixture recipe(int count) {
    return transactions.execute(_ -> {
      GameItem output = new GameItem();
      output.setName("Preview output " + UUID.randomUUID());
      output = gameItems.save(output);
      Blueprint recipe = new Blueprint();
      recipe.setOutputItem(output);
      recipe.setOutputName(output.getName());
      Set<UUID> materialIds = new LinkedHashSet<>();
      for (int index = 0; index < count; index++) {
        Material material = new Material();
        material.setName("Preview bridge " + UUID.randomUUID());
        material.setType(MaterialType.RAW);
        material = materials.save(material);
        materialIds.add(material.getId());
        GameItem component = new GameItem();
        component.setName(material.getName());
        component = gameItems.save(component);
        BlueprintIngredient ingredient = new BlueprintIngredient();
        ingredient.setOrderIndex(index);
        ingredient.setKind(BlueprintIngredientKind.ITEM);
        ingredient.setGameItem(component);
        ingredient.setQuantityUnits(1);
        recipe.addIngredient(ingredient);
      }
      recipe = blueprints.saveAndFlush(recipe);
      return new Fixture(recipe.getId(), Set.copyOf(materialIds));
    });
  }
}
