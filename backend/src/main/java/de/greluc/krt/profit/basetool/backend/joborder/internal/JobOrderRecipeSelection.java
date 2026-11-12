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

import de.greluc.krt.profit.basetool.backend.catalogue.api.Blueprint;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintIngredient;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintIngredientKind;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintRequirementGroup;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ItemOrderRecipeFacts;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityTypeRounding;
import de.greluc.krt.profit.basetool.backend.catalogue.api.RecipeGroupSelection;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItemPlan;
import de.greluc.krt.profit.basetool.backend.kernel.ScuAmounts;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.dao.IncorrectResultSizeDataAccessException;

/** Adapts immutable item-order recipe facts to the common no-stock cost selection. */
public final class JobOrderRecipeSelection {
  private final JobOrderItemPlan.Facts facts;
  private final Map<UUID, ItemOrderRecipeFacts.Ingredient> ingredients;
  private final Map<UUID, List<ItemOrderRecipeFacts.MaterialBridge>> bridges;
  private final Set<UUID> craftable;

  /**
   * Indexes the immutable catalogue selection for the owning action or preview.
   *
   * @param facts the captured derivation inputs
   */
  public JobOrderRecipeSelection(@NotNull JobOrderItemPlan.Facts facts) {
    this.facts = facts;
    ingredients =
        facts.ingredients().stream()
            .collect(Collectors.toMap(ItemOrderRecipeFacts.Ingredient::id, line -> line));
    bridges =
        facts.bridges().stream()
            .collect(Collectors.groupingBy(ItemOrderRecipeFacts.MaterialBridge::ingredientId));
    craftable =
        facts.outputs().stream()
            .map(ItemOrderRecipeFacts.Output::ingredientId)
            .collect(Collectors.toSet());
  }

  /**
   * Selects the recipe's counted costs through the common no-stock group policy.
   *
   * @param source the captured recipe
   * @return the selected immutable ingredient facts
   */
  @NotNull
  public List<ItemOrderRecipeFacts.Ingredient> counted(
      @NotNull ItemOrderRecipeFacts.Recipe source) {
    Blueprint recipe = new Blueprint();
    recipe.setId(source.id());
    recipe.setRequiredGroupCount(source.requiredGroupCount());
    Map<UUID, BlueprintRequirementGroup> groups = new LinkedHashMap<>();
    for (ItemOrderRecipeFacts.Group fact : facts.groups()) {
      if (!fact.blueprintId().equals(source.id())) {
        continue;
      }
      BlueprintRequirementGroup group = new BlueprintRequirementGroup();
      group.setId(fact.id());
      group.setOrderIndex(fact.orderIndex());
      group.setOptional(fact.optional());
      groups.put(fact.id(), group);
      recipe.addRequirementGroup(group);
    }
    for (ItemOrderRecipeFacts.Ingredient fact : facts.ingredients()) {
      if (!fact.blueprintId().equals(source.id())) {
        continue;
      }
      BlueprintIngredient line = new BlueprintIngredient();
      line.setId(fact.id());
      line.setOrderIndex(fact.orderIndex());
      line.setKind(fact.kind());
      UUID groupId = fact.groupId();
      if (groupId != null) {
        line.setRequirementGroup(Objects.requireNonNull(groups.get(groupId)));
      }
      recipe.addIngredient(line);
    }
    return RecipeGroupSelection.countedLines(
            recipe,
            line -> demand(Objects.requireNonNull(ingredients.get(line.getId()))),
            RecipeGroupSelection.NO_STOCK)
        .stream()
        .map(line -> Objects.requireNonNull(ingredients.get(line.getId())))
        .toList();
  }

  /**
   * Resolves a selected cost to its material identity and unit.
   *
   * @param line the captured ingredient
   * @return the matching material, or null for an unresolved or craftable item cost
   */
  @Nullable
  public ResolvedMaterial material(@NotNull ItemOrderRecipeFacts.Ingredient line) {
    if (line.kind() == BlueprintIngredientKind.RESOURCE) {
      return line.materialId() == null
          ? null
          : new ResolvedMaterial(line.materialId(), line.quantityType());
    }
    if (line.gameItemId() == null || craftable.contains(line.id())) {
      return null;
    }
    String name = line.gameItemName() != null ? line.gameItemName() : line.wikiName();
    if (name == null || name.isBlank()) {
      return null;
    }
    List<ItemOrderRecipeFacts.MaterialBridge> matches = bridges.getOrDefault(line.id(), List.of());
    if (matches.size() > 1) {
      throw new IncorrectResultSizeDataAccessException(1, matches.size());
    }
    if (matches.isEmpty()) {
      return null;
    }
    var bridge = matches.getFirst();
    return new ResolvedMaterial(bridge.materialId(), bridge.quantityType());
  }

  @Nullable
  private RecipeGroupSelection.LineDemand demand(@NotNull ItemOrderRecipeFacts.Ingredient line) {
    ResolvedMaterial material = material(line);
    return material == null
        ? null
        : new RecipeGroupSelection.LineDemand(
            material.id(),
            QuantityTypeRounding.roundForQuantityType(perUnit(line), material.quantityType()),
            0);
  }

  /**
   * Scales a selected cost without losing its representable unit quantity.
   *
   * @param ingredient the selected recipe cost
   * @param amount the ordered whole-unit count
   * @param material the resolved material and its rounding unit
   * @return the canonical rounded requirement
   * @throws IllegalArgumentException when the quantity cannot be represented in exact quanta
   */
  public static double requiredQuantity(
      @NotNull ItemOrderRecipeFacts.Ingredient ingredient,
      int amount,
      @NotNull ResolvedMaterial material) {
    double raw = perUnit(ingredient) * amount;
    try {
      long sourceQuanta = ScuAmounts.toQuanta(raw);
      double rounded = QuantityTypeRounding.roundForQuantityType(raw, material.quantityType());
      long resultQuanta = ScuAmounts.toQuanta(rounded);
      if (material.quantityType() != QuantityType.PIECE && sourceQuanta != resultQuanta) {
        throw new ArithmeticException("Rounded SCU quantity differs from source quanta");
      }
      return rounded;
    } catch (ArithmeticException | NumberFormatException failure) {
      throw new IllegalArgumentException(
          "Derived material quantity cannot be represented exactly", failure);
    }
  }

  /**
   * Scales one selected subassembly cost without wrapping its public whole-unit count.
   *
   * @param ingredient the selected ITEM cost
   * @param amount the ordered whole-unit count
   * @return the exact required unit count
   * @throws IllegalArgumentException when the count cannot be represented as an integer
   */
  public static int requiredUnits(@NotNull ItemOrderRecipeFacts.Ingredient ingredient, int amount) {
    try {
      return Math.multiplyExact(
          ingredient.quantityUnits() == null ? 0 : ingredient.quantityUnits(), amount);
    } catch (ArithmeticException failure) {
      throw new IllegalArgumentException(
          "Derived subassembly quantity cannot be represented exactly", failure);
    }
  }

  static double perUnit(@NotNull ItemOrderRecipeFacts.Ingredient line) {
    return line.kind() == BlueprintIngredientKind.RESOURCE
        ? line.quantityScu() == null ? 0.0 : line.quantityScu()
        : line.quantityUnits() == null ? 0.0 : line.quantityUnits();
  }

  /**
   * The material identity and unit of one resolved cost.
   *
   * @param id the material identity
   * @param quantityType its rounding unit, or null when the catalogue leaves it unspecified
   */
  public record ResolvedMaterial(@NotNull UUID id, @Nullable QuantityType quantityType) {}
}
