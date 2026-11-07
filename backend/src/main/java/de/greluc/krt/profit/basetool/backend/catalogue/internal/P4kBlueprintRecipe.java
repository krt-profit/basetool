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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Blueprint;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintDismantleReturn;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintIngredient;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintIngredientKind;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintModifierSegment;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintModifierValueKind;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintRequirementGroup;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintRequirementModifier;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiPredicate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The recipe of one blueprint as the P4K delivers it (REQ-P4K-027): the requirement groups with
 * their modifiers and cost lines, and how many of the mandatory groups a craft fills. The value
 * {@linkplain #matches matches} the recipe a blueprint stores when the P4K changed nothing, so an
 * unchanged recipe is neither reported nor rewritten, also after the SC Wiki filled its gaps.
 *
 * @param requiredGroupCount how many mandatory groups one craft fills, {@code null} for all
 * @param groups the requirement groups in order, the mandatory before the optional ones
 */
record P4kBlueprintRecipe(
    @Nullable Integer requiredGroupCount, @NotNull @Unmodifiable List<Group> groups) {

  /** The node kind every requirement group carries. */
  static final String GROUP_KIND = "group";

  /**
   * Copies the groups.
   *
   * @param requiredGroupCount how many mandatory groups one craft fills, {@code null} for all
   * @param groups the requirement groups
   */
  P4kBlueprintRecipe {
    groups = List.copyOf(groups);
  }

  /**
   * One requirement group (build slot).
   *
   * @param key the slot's debug name, e.g. {@code CASING}, or {@code null}
   * @param optional whether the group is an optional cost
   * @param requiredCount how many of its lines a craft fills
   * @param modifiers the stat modifiers of the slot
   * @param lines the cost lines of the slot
   */
  record Group(
      @Nullable String key,
      boolean optional,
      @Nullable Integer requiredCount,
      @NotNull @Unmodifiable List<Modifier> modifiers,
      @NotNull @Unmodifiable List<Line> lines) {

    /**
     * Copies the lists.
     *
     * @param key the slot's debug name
     * @param optional whether the group is optional
     * @param requiredCount how many lines a craft fills
     * @param modifiers the modifiers
     * @param lines the cost lines
     */
    Group {
      modifiers = List.copyOf(modifiers);
      lines = List.copyOf(lines);
    }

    /**
     * Whether a stored group holds this group's P4K values.
     *
     * @param stored the stored group
     * @return {@code true} when the key, the counts, the modifiers and the lines match
     */
    boolean matches(@NotNull Group stored) {
      return Objects.equals(key, stored.key)
          && optional == stored.optional
          && Objects.equals(requiredCount, stored.requiredCount)
          && pairwise(modifiers, stored.modifiers, Modifier::matches)
          && pairwise(lines, stored.lines, Line::matches);
    }
  }

  /**
   * One stat modifier of a group.
   *
   * @param propertyGuid the {@code gameplayProperties} GUID
   * @param propertyKey the stat key, e.g. {@code weapon_damage}, or {@code null}
   * @param label the stat's English name, or {@code null}
   * @param valueKind whether the values are factors or summands, or {@code null}
   * @param qualityMin the quality of the first range's start
   * @param qualityMax the quality of the last range's end
   * @param atMin the value at {@code qualityMin}
   * @param atMax the value at {@code qualityMax}
   * @param segments the ranges of a modifier with more than one, else empty
   */
  record Modifier(
      @Nullable UUID propertyGuid,
      @Nullable String propertyKey,
      @Nullable String label,
      @Nullable BlueprintModifierValueKind valueKind,
      @Nullable Double qualityMin,
      @Nullable Double qualityMax,
      @Nullable Double atMin,
      @Nullable Double atMax,
      @NotNull @Unmodifiable List<Segment> segments) {

    /**
     * Copies the segments.
     *
     * @param propertyGuid the stat GUID
     * @param propertyKey the stat key
     * @param label the stat name
     * @param valueKind the value kind
     * @param qualityMin the lowest quality
     * @param qualityMax the highest quality
     * @param atMin the value at the lowest quality
     * @param atMax the value at the highest quality
     * @param segments the ranges
     */
    Modifier {
      segments = List.copyOf(segments);
    }

    /**
     * Whether a stored modifier holds this modifier's P4K values; a stat key or label the P4K
     * leaves {@code null} is the SC Wiki's to fill.
     *
     * @param stored the stored modifier
     * @return {@code true} when every value the P4K delivers matches
     */
    boolean matches(@NotNull Modifier stored) {
      return Objects.equals(propertyGuid, stored.propertyGuid)
          && led(propertyKey, stored.propertyKey)
          && led(label, stored.label)
          && valueKind == stored.valueKind
          && Objects.equals(qualityMin, stored.qualityMin)
          && Objects.equals(qualityMax, stored.qualityMax)
          && Objects.equals(atMin, stored.atMin)
          && Objects.equals(atMax, stored.atMax)
          && segments.equals(stored.segments);
    }
  }

  /**
   * One value range of a modifier with several.
   *
   * @param qualityMin the range's start quality
   * @param qualityMax the range's end quality
   * @param atStart the value at the start
   * @param atEnd the value at the end
   */
  record Segment(
      @Nullable Double qualityMin,
      @Nullable Double qualityMax,
      @Nullable Double atStart,
      @Nullable Double atEnd) {}

  /**
   * One cost line: a resource in SCU or an item in units, the quantity multiplier applied.
   *
   * @param kind resource or item
   * @param resourceGuid the {@code ResourceType} GUID of a resource line
   * @param itemGuid the entity GUID of an item line
   * @param material the resolved material, or {@code null}
   * @param item the resolved item, or {@code null}
   * @param name the name the line shows, or {@code null}
   * @param quantityScu the SCU of a resource line
   * @param quantityUnits the units of an item line
   * @param minQuality the minimum quality, clamped
   */
  record Line(
      @NotNull BlueprintIngredientKind kind,
      @Nullable UUID resourceGuid,
      @Nullable UUID itemGuid,
      @Nullable Material material,
      @Nullable GameItem item,
      @Nullable String name,
      @Nullable Double quantityScu,
      @Nullable Integer quantityUnits,
      @Nullable Integer minQuality) {

    /**
     * Whether the line resolved to a material or an item.
     *
     * @return {@code true} when the line links a row
     */
    boolean resolved() {
      return material != null || item != null;
    }

    /**
     * Whether a stored line holds this line's P4K values; a material, item or name the P4K leaves
     * {@code null} is the SC Wiki's to fill.
     *
     * @param stored the stored line
     * @return {@code true} when every value the P4K delivers matches
     */
    boolean matches(@NotNull Line stored) {
      return kind == stored.kind
          && Objects.equals(resourceGuid, stored.resourceGuid)
          && Objects.equals(itemGuid, stored.itemGuid)
          && led(material, stored.material)
          && led(item, stored.item)
          && led(name, stored.name)
          && Objects.equals(quantityScu, stored.quantityScu)
          && Objects.equals(quantityUnits, stored.quantityUnits)
          && Objects.equals(minQuality, stored.minQuality);
    }
  }

  /**
   * One dismantle-return line.
   *
   * @param resourceGuid the {@code ResourceType} GUID, {@code null} on a line the Wiki wrote
   * @param material the resolved material, or {@code null}
   * @param name the name the line shows, or {@code null}
   * @param quantityScu the SCU returned
   */
  record Return(
      @Nullable UUID resourceGuid,
      @Nullable Material material,
      @Nullable String name,
      @Nullable Double quantityScu) {}

  /**
   * Whether a stored recipe holds every value of this recipe the P4K delivers; the values the P4K
   * leaves {@code null} are the SC Wiki's to fill and do not count (REQ-P4K-027).
   *
   * @param stored the stored recipe, or {@code null} when the stored recipe is not the P4K's
   * @return {@code true} when the recipe need not be rewritten
   */
  boolean matches(@Nullable P4kBlueprintRecipe stored) {
    return stored != null
        && Objects.equals(requiredGroupCount, stored.requiredGroupCount)
        && pairwise(groups, stored.groups, Group::matches);
  }

  /**
   * Whether a stored value holds the P4K's: equal, or any value where the P4K delivers none.
   *
   * @param p4kValue the P4K's value, or {@code null}
   * @param stored the stored value, or {@code null}
   * @return {@code true} when the stored value need not change
   */
  private static boolean led(@Nullable Object p4kValue, @Nullable Object stored) {
    return p4kValue == null || p4kValue.equals(stored);
  }

  /**
   * Whether two lists have one length and match element by element.
   *
   * @param p4k the P4K's elements
   * @param stored the stored elements
   * @param matches whether a stored element holds a P4K element
   * @param <T> the element type
   * @return {@code true} when every pair matches
   */
  private static <T> boolean pairwise(
      @NotNull List<T> p4k, @NotNull List<T> stored, @NotNull BiPredicate<T, T> matches) {
    if (p4k.size() != stored.size()) {
      return false;
    }
    for (int i = 0; i < p4k.size(); i++) {
      if (!matches.test(p4k.get(i), stored.get(i))) {
        return false;
      }
    }
    return true;
  }

  /**
   * Counts the lines that resolved to a material or an item.
   *
   * @return the resolved lines of every group
   */
  int resolvedLines() {
    return (int) groups.stream().flatMap(g -> g.lines().stream()).filter(Line::resolved).count();
  }

  /**
   * Summarises the recipe for a report row.
   *
   * @return e.g. {@code groups=2;lines=3}
   */
  @NotNull
  String summary() {
    return "groups="
        + groups.size()
        + ";lines="
        + groups.stream().mapToInt(g -> g.lines().size()).sum();
  }

  /**
   * Reads the recipe a blueprint stores, in the shape the P4K writes it.
   *
   * @param blueprint the blueprint
   * @return the stored recipe, or {@code null} when the stored recipe is not the P4K's
   */
  @Nullable
  static P4kBlueprintRecipe stored(@NotNull Blueprint blueprint) {
    if (!blueprint.isP4kRecipe()) {
      return null;
    }
    List<Group> groups = new ArrayList<>();
    for (BlueprintRequirementGroup group : blueprint.getRequirementGroups()) {
      List<Line> lines = new ArrayList<>();
      for (BlueprintIngredient line : blueprint.getIngredients()) {
        if (Objects.equals(line.getRequirementGroup(), group)) {
          lines.add(
              new Line(
                  line.getKind(),
                  line.getP4kResourceGuid(),
                  line.getP4kItemGuid(),
                  line.getMaterial(),
                  line.getGameItem(),
                  line.getWikiNameSnapshot(),
                  line.getQuantityScu(),
                  line.getQuantityUnits(),
                  line.getMinQuality()));
        }
      }
      List<Modifier> modifiers =
          group.getModifiers().stream().map(P4kBlueprintRecipe::storedModifier).toList();
      groups.add(
          new Group(
              group.getGroupKey(), group.isOptional(), group.getRequiredCount(), modifiers, lines));
    }
    return new P4kBlueprintRecipe(blueprint.getRequiredGroupCount(), groups);
  }

  /**
   * Reads a stored modifier.
   *
   * @param modifier the modifier
   * @return its value
   */
  @NotNull
  private static Modifier storedModifier(@NotNull BlueprintRequirementModifier modifier) {
    return new Modifier(
        modifier.getPropertyGuid(),
        modifier.getPropertyKey(),
        modifier.getLabel(),
        modifier.getValueKind(),
        modifier.getQualityMin(),
        modifier.getQualityMax(),
        modifier.getModifierAtMinQuality(),
        modifier.getModifierAtMaxQuality(),
        modifier.getSegments().stream()
            .map(s ->
                new Segment(
                    s.getQualityMin(),
                    s.getQualityMax(),
                    s.getModifierAtStart(),
                    s.getModifierAtEnd()))
            .toList());
  }

  /**
   * Reads the dismantle returns a blueprint stores, in the shape the P4K writes them.
   *
   * @param blueprint the blueprint
   * @return the stored returns
   */
  @NotNull
  static List<Return> storedReturns(@NotNull Blueprint blueprint) {
    return blueprint.getDismantleReturns().stream()
        .map(line ->
            new Return(
                line.getP4kResourceGuid(),
                line.getMaterial(),
                line.getWikiNameSnapshot(),
                line.getQuantityScu()))
        .toList();
  }

  /**
   * Replaces a blueprint's requirement groups and ingredient lines with this recipe and marks the
   * recipe as the P4K's; the summary properties stay, and where the P4K delivers {@code null} the
   * new rows keep what the SC Wiki filled on the stored ones ({@link P4kRecipeWikiFills}).
   *
   * @param blueprint the blueprint
   */
  void writeTo(@NotNull Blueprint blueprint) {
    final P4kRecipeWikiFills fills = P4kRecipeWikiFills.of(blueprint);
    blueprint.clearIngredients();
    blueprint.clearRequirementGroups();
    blueprint.setRequiredGroupCount(requiredGroupCount);
    blueprint.setP4kRecipe(true);
    int groupOrder = 0;
    int lineOrder = 0;
    for (Group group : groups) {
      BlueprintRequirementGroup row = new BlueprintRequirementGroup();
      row.setOrderIndex(groupOrder++);
      row.setGroupKey(group.key());
      row.setKind(GROUP_KIND);
      row.setRequiredCount(group.requiredCount());
      row.setOptional(group.optional());
      fills.fillGroup(row);
      blueprint.addRequirementGroup(row);
      int modifierOrder = 0;
      for (Modifier modifier : group.modifiers()) {
        BlueprintRequirementModifier modifierRow = modifierRow(modifier, modifierOrder++);
        fills.fillModifier(group.key(), modifierRow);
        row.addModifier(modifierRow);
      }
      for (Line line : group.lines()) {
        BlueprintIngredient ingredient = new BlueprintIngredient();
        ingredient.setOrderIndex(lineOrder++);
        ingredient.setRequirementGroup(row);
        ingredient.setKind(line.kind());
        ingredient.setP4kResourceGuid(line.resourceGuid());
        ingredient.setP4kItemGuid(line.itemGuid());
        ingredient.setMaterial(line.material());
        ingredient.setGameItem(line.item());
        ingredient.setWikiNameSnapshot(line.name());
        ingredient.setQuantityScu(line.quantityScu());
        ingredient.setQuantityUnits(line.quantityUnits());
        ingredient.setMinQuality(line.minQuality());
        fills.fillLine(group.key(), ingredient);
        blueprint.addIngredient(ingredient);
      }
    }
  }

  /**
   * Builds the row of a modifier.
   *
   * @param modifier the modifier
   * @param order its position in the group
   * @return the unsaved row
   */
  @NotNull
  private static BlueprintRequirementModifier modifierRow(@NotNull Modifier modifier, int order) {
    BlueprintRequirementModifier row = new BlueprintRequirementModifier();
    row.setOrderIndex(order);
    row.setPropertyGuid(modifier.propertyGuid());
    row.setPropertyKey(modifier.propertyKey());
    row.setLabel(modifier.label());
    row.setValueKind(modifier.valueKind());
    row.setValueRangeType("linear");
    row.setQualityMin(modifier.qualityMin());
    row.setQualityMax(modifier.qualityMax());
    row.setModifierAtMinQuality(modifier.atMin());
    row.setModifierAtMaxQuality(modifier.atMax());
    int segmentOrder = 0;
    for (Segment segment : modifier.segments()) {
      BlueprintModifierSegment segmentRow = new BlueprintModifierSegment();
      segmentRow.setOrderIndex(segmentOrder++);
      segmentRow.setQualityMin(segment.qualityMin());
      segmentRow.setQualityMax(segment.qualityMax());
      segmentRow.setModifierAtStart(segment.atStart());
      segmentRow.setModifierAtEnd(segment.atEnd());
      row.addSegment(segmentRow);
    }
    return row;
  }

  /**
   * Replaces a blueprint's dismantle returns.
   *
   * @param blueprint the blueprint
   * @param returns the returns the P4K delivers
   */
  static void writeReturns(@NotNull Blueprint blueprint, @NotNull List<Return> returns) {
    blueprint.clearDismantleReturns();
    int order = 0;
    for (Return line : returns) {
      BlueprintDismantleReturn row = new BlueprintDismantleReturn();
      row.setOrderIndex(order++);
      row.setP4kResourceGuid(line.resourceGuid());
      row.setMaterial(line.material());
      row.setWikiNameSnapshot(line.name());
      row.setQuantityScu(line.quantityScu());
      blueprint.addDismantleReturn(row);
    }
  }
}
