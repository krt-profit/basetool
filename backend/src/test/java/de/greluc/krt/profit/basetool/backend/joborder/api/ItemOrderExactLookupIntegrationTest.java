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
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/** Verifies public preview bridges only exact current catalogue costs under database name rules. */
@SpringBootTest
class ItemOrderExactLookupIntegrationTest {
  @Autowired private WebApplicationContext context;
  @Autowired private tools.jackson.databind.ObjectMapper json;
  @Autowired private BlueprintRepository blueprints;
  @Autowired private GameItemRepository gameItems;
  @Autowired private MaterialRepository materials;
  @Autowired private TransactionTemplate transactions;

  /** The exact public requirements, one listed subassembly and one unresolved blank-name item. */
  private record Fixture(
      UUID blueprintId,
      Map<UUID, Double> required,
      UUID activeItemId,
      UUID activeRecipeId,
      UUID blankItemId,
      String unresolvedName) {}

  @Test
  void previewUsesOnlyListedSubrecipesDatabaseCasefoldAndNonnullNamesWithoutBlankFallback()
      throws Exception {
    Fixture fixture = recipe();
    var mvc =
        MockMvcBuilders.webAppContextSetup(context)
            .apply(SecurityMockMvcConfigurers.springSecurity())
            .build();
    String body =
        mvc.perform(
                get("/api/v1/blueprints/{id}/derivation", fixture.blueprintId())
                    .param("amount", "2")
                    .header("Accept-Language", "en")
                    .with(jwt()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    ItemDerivationDto preview = json.readValue(body, ItemDerivationDto.class);
    assertThat(
            preview.materials().stream()
                .collect(
                    Collectors.toMap(
                        row -> row.material().id(), DerivedMaterialDto::requiredQuantity)))
        .containsExactlyInAnyOrderEntriesOf(fixture.required());
    assertThat(preview.subAssemblies())
        .extracting(row -> row.gameItem().id())
        .containsExactlyInAnyOrder(fixture.activeItemId(), fixture.blankItemId());
    var active =
        preview.subAssemblies().stream()
            .filter(row -> row.gameItem().id().equals(fixture.activeItemId()))
            .findFirst()
            .orElseThrow();
    assertThat(active.quantity()).isEqualTo(4);
    assertThat(active.blueprints())
        .extracting(row -> row.id())
        .containsExactly(fixture.activeRecipeId());
    var blank =
        preview.subAssemblies().stream()
            .filter(row -> row.gameItem().id().equals(fixture.blankItemId()))
            .findFirst()
            .orElseThrow();
    assertThat(blank.quantity()).isEqualTo(12);
    assertThat(blank.blueprints()).isEmpty();
    assertThat(preview.unresolvedIngredients())
        .containsExactly(fixture.unresolvedName(), "(unresolved ingredient)");
  }

  private Fixture recipe() {
    return transactions.execute(_ -> {
      String tag = UUID.randomUUID().toString();
      Material activeCost = material("Listed cost " + tag);
      GameItem active = item(activeCost.getName());
      Blueprint activeRecipe = blueprint(active);
      Material hiddenCost = material("Hidden cost " + tag);
      GameItem hidden = item(hiddenCost.getName());
      Blueprint hiddenRecipe = new Blueprint();
      hiddenRecipe.setOutputItem(hidden);
      hiddenRecipe.setOutputName(hidden.getName());
      hiddenRecipe.setP4kHiddenAt(Instant.now());
      blueprints.save(hiddenRecipe);
      Material deletedCost = material("Wiki deleted cost " + tag);
      GameItem deleted = item(deletedCost.getName());
      Blueprint deletedRecipe = new Blueprint();
      deletedRecipe.setOutputItem(deleted);
      deletedRecipe.setOutputName(deleted.getName());
      deletedRecipe.setScwikiDeletedAt(Instant.now());
      blueprints.save(deletedRecipe);
      Material unicodeCost = material("ısı " + tag);
      GameItem unicode = item("ISI " + tag);
      assertThat(materials.findByNameIgnoreCase(unicode.getName())).contains(unicodeCost);
      Material blankFallback = material("Blank fallback " + tag);
      GameItem blank = item("");
      Material unresolved = material("Unresolved material " + tag);
      Blueprint recipe = new Blueprint();
      recipe.setOutputName("Exact lookup output " + tag);
      addItem(recipe, 0, active, null, 2);
      addItem(recipe, 1, hidden, null, 3);
      addItem(recipe, 2, deleted, null, 4);
      addItem(recipe, 3, unicode, null, 5);
      addItem(recipe, 4, blank, blankFallback.getName(), 6);
      addItem(recipe, 5, null, unresolved.getName(), 7);
      BlueprintIngredient unknownResource = new BlueprintIngredient();
      unknownResource.setOrderIndex(6);
      unknownResource.setKind(BlueprintIngredientKind.RESOURCE);
      unknownResource.setQuantityScu(1.0);
      recipe.addIngredient(unknownResource);
      recipe = blueprints.saveAndFlush(recipe);
      return new Fixture(
          recipe.getId(),
          Map.of(hiddenCost.getId(), 6.0, deletedCost.getId(), 8.0, unicodeCost.getId(), 10.0),
          active.getId(),
          activeRecipe.getId(),
          blank.getId(),
          unresolved.getName());
    });
  }

  private Material material(String name) {
    Material material = new Material();
    material.setName(name);
    material.setType(MaterialType.RAW);
    return materials.save(material);
  }

  private GameItem item(String name) {
    GameItem item = new GameItem();
    item.setName(name);
    return gameItems.save(item);
  }

  private Blueprint blueprint(GameItem output) {
    Blueprint blueprint = new Blueprint();
    blueprint.setOutputItem(output);
    blueprint.setOutputName(output.getName());
    return blueprints.save(blueprint);
  }

  private static void addItem(
      Blueprint recipe,
      int index,
      @Nullable GameItem item,
      @Nullable String wikiName,
      int quantity) {
    BlueprintIngredient ingredient = new BlueprintIngredient();
    ingredient.setOrderIndex(index);
    ingredient.setKind(BlueprintIngredientKind.ITEM);
    ingredient.setGameItem(item);
    ingredient.setWikiNameSnapshot(wikiName);
    ingredient.setQuantityUnits(quantity);
    recipe.addIngredient(ingredient);
  }
}
