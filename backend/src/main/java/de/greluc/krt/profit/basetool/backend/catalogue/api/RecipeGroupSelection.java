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

import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Picks the cost lines one craft of a recipe consumes (REQ-P4K-027): every line outside an optional
 * group, and of a choose-n recipe ({@code required_group_count}) only the lines of the {@code n}
 * option groups a stock covers best. The craftability and the item-order derivation both select
 * through this class.
 *
 * <p>An option group covers better when the stock allows more crafts of it alone, then when its
 * shortfall for one craft is smaller; a group without an evaluable line covers nothing, and equal
 * groups keep recipe order.
 */
public final class RecipeGroupSelection {

  /** A stock that holds nothing, under which the groups with the smallest demand cover best. */
  public static final Stock NO_STOCK = (materialId, floor) -> 0.0d;

  private RecipeGroupSelection() {}

  /**
   * Returns the lines one craft of {@code recipe} consumes.
   *
   * @param recipe the recipe; its groups and lines are read
   * @param demandOf the material and per-craft quantity of a line, or {@code null} for a line the
   *     caller cannot evaluate
   * @param stock the stock the selection is measured against
   * @return the counted lines in recipe order
   */
  @NotNull
  @Unmodifiable
  public static List<BlueprintIngredient> countedLines(
      @NotNull Blueprint recipe,
      @NotNull Function<BlueprintIngredient, @Nullable LineDemand> demandOf,
      @NotNull Stock stock) {
    List<BlueprintIngredient> mandatory =
        recipe.getIngredients().stream().filter(line -> !isOptional(line)).toList();
    Integer choose = recipe.getRequiredGroupCount();
    List<BlueprintRequirementGroup> options =
        recipe.getRequirementGroups().stream().filter(g -> !g.isOptional()).toList();
    if (choose == null || choose >= options.size()) {
      return mandatory;
    }
    Set<BlueprintRequirementGroup> chosen = Collections.newSetFromMap(new IdentityHashMap<>());
    options.stream()
        .sorted(
            Comparator.comparing(
                (BlueprintRequirementGroup g) -> coverage(linesOf(mandatory, g), demandOf, stock)))
        .limit(Math.max(choose, 0))
        .forEach(chosen::add);
    return mandatory.stream()
        .filter(line ->
            line.getRequirementGroup() == null || chosen.contains(line.getRequirementGroup()))
        .toList();
  }

  /**
   * Whether a line fills an optional cost of the recipe, which a craft leaves out.
   *
   * @param line the line
   * @return {@code true} when its group is optional
   */
  private static boolean isOptional(@NotNull BlueprintIngredient line) {
    BlueprintRequirementGroup group = line.getRequirementGroup();
    return group != null && group.isOptional();
  }

  /**
   * Returns the lines of one requirement group.
   *
   * @param lines the lines
   * @param group the group
   * @return its lines in recipe order
   */
  @NotNull
  private static List<BlueprintIngredient> linesOf(
      @NotNull List<BlueprintIngredient> lines, @NotNull BlueprintRequirementGroup group) {
    return lines.stream().filter(line -> line.getRequirementGroup() == group).toList();
  }

  /**
   * How well the stock covers one option group, its lines pooled per material.
   *
   * @param lines the group's lines
   * @param demandOf the demand of a line
   * @param stock the stock
   * @return the coverage
   */
  @NotNull
  private static Coverage coverage(
      @NotNull List<BlueprintIngredient> lines,
      @NotNull Function<BlueprintIngredient, @Nullable LineDemand> demandOf,
      @NotNull Stock stock) {
    Map<UUID, LineDemand> pooled = new LinkedHashMap<>();
    for (BlueprintIngredient line : lines) {
      LineDemand demand = demandOf.apply(line);
      if (demand != null && demand.required() > 0.0d) {
        pooled.merge(demand.materialId(), demand, LineDemand::plus);
      }
    }
    if (pooled.isEmpty()) {
      return new Coverage(0, Double.MAX_VALUE);
    }
    int crafts = Integer.MAX_VALUE;
    double missing = 0.0d;
    for (LineDemand demand : pooled.values()) {
      double available = stock.available(demand.materialId(), demand.floor());
      crafts = Math.min(crafts, (int) Math.floor(available / demand.required()));
      missing += Math.max(0.0d, demand.required() - available);
    }
    return new Coverage(crafts, missing);
  }

  /**
   * What one craft needs of a line's material.
   *
   * @param materialId the material the line consumes
   * @param required the quantity one craft needs, in the material's unit
   * @param floor the lowest stock quality that counts (0..1000)
   */
  public record LineDemand(@NotNull UUID materialId, double required, int floor) {

    /**
     * Pools this demand with another of the same material.
     *
     * @param other the other demand
     * @return the summed quantity under the stricter floor
     */
    @NotNull
    LineDemand plus(@NotNull LineDemand other) {
      return new LineDemand(materialId, required + other.required, Math.max(floor, other.floor));
    }
  }

  /** The stock a selection is measured against. */
  @FunctionalInterface
  public interface Stock {

    /**
     * Returns the quantity of a material held at or above a quality.
     *
     * @param materialId the material
     * @param floor the lowest quality that counts (0..1000)
     * @return the qualifying quantity, in the material's unit
     */
    double available(@NotNull UUID materialId, int floor);
  }

  /**
   * How well a stock covers one option group; the better one sorts first: more crafts, then the
   * smaller shortfall.
   *
   * @param crafts the crafts the stock allows for the group alone
   * @param missing the shortfall for one craft of the group
   */
  private record Coverage(int crafts, double missing) implements Comparable<Coverage> {

    @Override
    public int compareTo(@NotNull Coverage other) {
      int byCrafts = Integer.compare(other.crafts, crafts);
      return byCrafts != 0 ? byCrafts : Double.compare(missing, other.missing);
    }
  }
}
