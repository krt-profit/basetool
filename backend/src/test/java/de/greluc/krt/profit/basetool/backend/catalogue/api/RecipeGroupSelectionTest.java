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

package de.greluc.krt.profit.basetool.backend.catalogue.api;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.catalogue.api.RecipeGroupSelection.LineDemand;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RecipeGroupSelection} (REQ-P4K-027): a line belongs to the option group its
 * requirement group equals as an entity, not only to the very instance the recipe lists.
 */
class RecipeGroupSelectionTest {

  private static final UUID FRAME_MATERIAL = UUID.randomUUID();
  private static final UUID LINER_MATERIAL = UUID.randomUUID();

  @Test
  void aChooseOneRecipe_countsTheBestCoveredGroup_whenItsLinesHoldAnotherInstanceOfTheGroup() {
    Blueprint recipe = new Blueprint();
    recipe.setRequiredGroupCount(1);
    BlueprintRequirementGroup frame = group(recipe, 0);
    BlueprintRequirementGroup liner = group(recipe, 1);
    BlueprintIngredient frameLine = line(recipe, 0, sameRow(frame));
    BlueprintIngredient linerLine = line(recipe, 1, sameRow(liner));
    Map<BlueprintIngredient, LineDemand> demands =
        Map.of(
            frameLine, new LineDemand(FRAME_MATERIAL, 1.0d, 0),
            linerLine, new LineDemand(LINER_MATERIAL, 1.0d, 0));

    List<BlueprintIngredient> counted =
        RecipeGroupSelection.countedLines(
            recipe,
            demands::get,
            (materialId, floor) -> materialId.equals(LINER_MATERIAL) ? 5.0d : 0.0d);

    assertThat(counted).containsExactly(linerLine);
  }

  @Test
  void aChooseOneRecipe_leavesOutALineWhoseGroupHasAnotherId() {
    Blueprint recipe = new Blueprint();
    recipe.setRequiredGroupCount(1);
    BlueprintRequirementGroup frame = group(recipe, 0);
    group(recipe, 1);
    BlueprintRequirementGroup unlisted = new BlueprintRequirementGroup();
    unlisted.setId(UUID.randomUUID());
    BlueprintIngredient frameLine = line(recipe, 0, frame);
    line(recipe, 1, unlisted);

    List<BlueprintIngredient> counted =
        RecipeGroupSelection.countedLines(
            recipe,
            ingredient ->
                frameLine.equals(ingredient) ? new LineDemand(FRAME_MATERIAL, 1.0d, 0) : null,
            RecipeGroupSelection.NO_STOCK);

    assertThat(counted).containsExactly(frameLine);
  }

  /**
   * Adds a non-optional option group with an id to the recipe.
   *
   * @param recipe the recipe
   * @param orderIndex the group's position
   * @return the group as the recipe lists it
   */
  private static BlueprintRequirementGroup group(Blueprint recipe, int orderIndex) {
    BlueprintRequirementGroup group = new BlueprintRequirementGroup();
    group.setId(UUID.randomUUID());
    group.setOrderIndex(orderIndex);
    recipe.addRequirementGroup(group);
    return group;
  }

  /**
   * Returns another instance of the same group row, as a lazy proxy of the group is.
   *
   * @param group the group as the recipe lists it
   * @return a distinct instance carrying the group's id
   */
  private static BlueprintRequirementGroup sameRow(BlueprintRequirementGroup group) {
    BlueprintRequirementGroup other = new BlueprintRequirementGroup();
    other.setId(group.getId());
    other.setOrderIndex(group.getOrderIndex());
    return other;
  }

  /**
   * Adds a resource line in a group to the recipe.
   *
   * @param recipe the recipe
   * @param orderIndex the line's position
   * @param group the requirement group the line references
   * @return the line
   */
  private static BlueprintIngredient line(
      Blueprint recipe, int orderIndex, BlueprintRequirementGroup group) {
    BlueprintIngredient line = new BlueprintIngredient();
    line.setId(UUID.randomUUID());
    line.setOrderIndex(orderIndex);
    line.setKind(BlueprintIngredientKind.RESOURCE);
    line.setRequirementGroup(group);
    recipe.addIngredient(line);
    return line;
  }
}
