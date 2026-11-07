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

import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintIngredientKind;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintModifierValueKind;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kBlueprintDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kBlueprintDto.CostLine;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kBlueprintDto.Range;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kCraftingReferences;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kDismantleProcessDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kGameplayPropertyDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kGuids;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kLocalisedText;
import de.greluc.krt.profit.basetool.backend.kernel.Quality;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Reads the recipe and the dismantle returns of one P4K blueprint from its cost tree (REQ-P4K-027,
 * contract "Tiers" and "Dismantle returns"). The first tier is the recipe. The mandatory cost's
 * root selection decides how many of its options a craft fills; each option becomes a requirement
 * group, a selection of resource and item lines a group of those lines, and the lines of nested
 * selections join the group they sit in. Every optional cost becomes an optional group.
 * Quantities carry the {@code quantityMultiplier}; minimum qualities are clamped (REQ-DATA-023).
 */
final class P4kBlueprintRecipeReader {

  /** The {@code kind} of a selection. */
  static final String SELECT = "select";

  /** The {@code kind} of a resource line. */
  static final String RESOURCE = "resource";

  /** The {@code kind} of an item line. */
  static final String ITEM = "item";

  /** The {@code field_name} of an unknown cost-line kind in the change report. */
  static final String COST_KIND_FIELD = "costLine.kind";

  /** The {@code field_name} of an unknown modifier-range kind in the change report. */
  static final String RANGE_KIND_FIELD = "modifier.range.kind";

  /** The prefix of a gameplay property's class name the stat key leaves out. */
  private static final String PROPERTY_PREFIX = "GPP_";

  /** The blueprint read. */
  @NotNull private final P4kBlueprintDto blueprint;

  /** The crafting records the cost tree refers to. */
  @NotNull private final P4kCraftingReferences crafting;

  /** Resolves the lines' resources and items. */
  @NotNull private final P4kRecipeLinks links;

  /** The run, for the unknown values. */
  @NotNull private final P4kImportContext context;

  /**
   * Creates the reader of one blueprint.
   *
   * @param blueprint the blueprint
   * @param crafting the crafting records
   * @param links resolves the lines' resources and items
   * @param context the run
   */
  P4kBlueprintRecipeReader(
      @NotNull P4kBlueprintDto blueprint,
      @NotNull P4kCraftingReferences crafting,
      @NotNull P4kRecipeLinks links,
      @NotNull P4kImportContext context) {
    this.blueprint = blueprint;
    this.crafting = crafting;
    this.links = links;
    this.context = context;
  }

  /**
   * Reads the recipe of the first tier.
   *
   * @return the recipe, or {@code null} when the blueprint has no tier with a mandatory cost
   */
  @Nullable
  P4kBlueprintRecipe recipe() {
    P4kBlueprintDto.Tier tier = tier(0);
    if (tier == null || tier.mandatory() == null) {
      return null;
    }
    CostLine root = tier.mandatory();
    List<P4kBlueprintRecipe.Group> groups = new ArrayList<>();
    Integer requiredGroupCount = null;
    if (SELECT.equals(root.kind())) {
      List<CostLine> options = options(root);
      if (root.count() != null && root.count() < options.size()) {
        requiredGroupCount = root.count();
      }
      options.forEach(option -> addGroup(groups, option, false));
    } else {
      addGroup(groups, root, false);
    }
    if (tier.optional() != null) {
      tier.optional().stream().filter(Objects::nonNull).forEach(line -> addGroup(groups, line, true));
    }
    return new P4kBlueprintRecipe(requiredGroupCount, groups);
  }

  /**
   * Reads the dismantle returns: the resource lines of the tier the dismantle names, each the
   * process's {@code efficiency} share of its stored quantity, without the excluded resources; a
   * selection is not resolved and the multiplier not applied (contract "Dismantle returns").
   *
   * @return the returns, or {@code null} when the blueprint has no dismantle or its process is
   *     unknown
   */
  @Nullable
  List<P4kBlueprintRecipe.Return> returns() {
    P4kDismantleProcessDto process = process();
    Integer tierIndex = blueprint.dismantle() == null ? null : blueprint.dismantle().tierIndex();
    P4kBlueprintDto.Tier tier = tierIndex == null ? null : tier(tierIndex);
    if (process == null || process.efficiency() == null || tier == null) {
      return null;
    }
    List<CostLine> leaves = new ArrayList<>();
    if (tier.mandatory() != null) {
      collectLeaves(tier.mandatory(), leaves);
    }
    List<P4kBlueprintRecipe.Return> returns = new ArrayList<>();
    for (CostLine leaf : leaves) {
      UUID resource = P4kGuids.parse(leaf.resourceGuid());
      if (!RESOURCE.equals(leaf.kind())
          || resource == null
          || leaf.quantityScu() == null
          || crafting.dismantleExcludedResources().contains(resource)) {
        continue;
      }
      Material material = links.material(resource);
      returns.add(
          new P4kBlueprintRecipe.Return(
              resource,
              material,
              material != null ? material.getName() : links.resourceName(resource),
              product(leaf.quantityScu(), process.efficiency())));
    }
    return returns;
  }

  /**
   * Returns the dismantle process the blueprint names.
   *
   * @return the process, or {@code null}
   */
  @Nullable
  P4kDismantleProcessDto process() {
    UUID guid = blueprint.dismantle() == null ? null : P4kGuids.parse(blueprint.dismantle().processGuid());
    return guid == null ? null : crafting.dismantleProcesses().get(guid);
  }

  /**
   * Returns one tier.
   *
   * @param index the tier's index
   * @return the tier, or {@code null} when the blueprint has none at the index
   */
  @Nullable
  private P4kBlueprintDto.Tier tier(int index) {
    List<P4kBlueprintDto.Tier> tiers = blueprint.tiers();
    return tiers == null || index < 0 || index >= tiers.size() ? null : tiers.get(index);
  }

  /**
   * Adds the group of one option of the root selection, or of one optional cost.
   *
   * @param groups the groups so far
   * @param line the cost line
   * @param optional whether the line is an optional cost
   */
  private void addGroup(
      @NotNull List<P4kBlueprintRecipe.Group> groups, @NotNull CostLine line, boolean optional) {
    if (!known(line)) {
      return;
    }
    List<CostLine> modifierHolders = new ArrayList<>();
    List<CostLine> leaves = new ArrayList<>();
    collect(line, modifierHolders, leaves);
    boolean select = SELECT.equals(line.kind());
    groups.add(
        new P4kBlueprintRecipe.Group(
            select ? line.slot() : null,
            optional,
            select ? line.count() : Integer.valueOf(1),
            modifierHolders.stream().flatMap(h -> modifiersOf(h).stream()).toList(),
            leaves.stream().map(this::line).filter(Objects::nonNull).toList()));
  }

  /**
   * Collects, depth first in stored order, the lines carrying modifiers and the resource and item
   * lines under a cost line.
   *
   * @param line the cost line
   * @param modifierHolders receives every line with modifiers
   * @param leaves receives every resource and item line
   */
  private void collect(
      @NotNull CostLine line,
      @NotNull List<CostLine> modifierHolders,
      @NotNull List<CostLine> leaves) {
    if (line.modifiers() != null && !line.modifiers().isEmpty()) {
      modifierHolders.add(line);
    }
    if (SELECT.equals(line.kind())) {
      for (CostLine option : options(line)) {
        if (known(option)) {
          collect(option, modifierHolders, leaves);
        }
      }
    } else {
      leaves.add(line);
    }
  }

  /**
   * Collects the resource and item lines under a cost line, depth first.
   *
   * @param line the cost line
   * @param leaves receives every resource and item line
   */
  private static void collectLeaves(@NotNull CostLine line, @NotNull List<CostLine> leaves) {
    if (SELECT.equals(line.kind())) {
      options(line).forEach(option -> collectLeaves(option, leaves));
    } else {
      leaves.add(line);
    }
  }

  /**
   * Whether a cost line has a kind the Basetool knows; an unknown one is reported and maps to
   * nothing.
   *
   * @param line the cost line
   * @return {@code true} for a selection, a resource line or an item line
   */
  private boolean known(@NotNull CostLine line) {
    String kind = line.kind();
    if (SELECT.equals(kind) || RESOURCE.equals(kind) || ITEM.equals(kind)) {
      return true;
    }
    context
        .getReport()
        .unknownValue(P4kKind.BLUEPRINT, blueprint, COST_KIND_FIELD, String.valueOf(kind));
    return false;
  }

  /**
   * Returns the options of a selection.
   *
   * @param line the selection
   * @return its options, without {@code null} entries
   */
  @NotNull
  private static List<CostLine> options(@NotNull CostLine line) {
    return line.options() == null
        ? List.of()
        : line.options().stream().filter(Objects::nonNull).toList();
  }

  /**
   * Builds one cost line.
   *
   * @param leaf a resource or item line
   * @return the line, or {@code null} when it names no resource or item
   */
  @Nullable
  private P4kBlueprintRecipe.Line line(@NotNull CostLine leaf) {
    Integer minQuality = Quality.clampImported(leaf.minQuality(), "blueprint cost minQuality");
    if (RESOURCE.equals(leaf.kind())) {
      UUID resource = P4kGuids.parse(leaf.resourceGuid());
      if (resource == null) {
        return null;
      }
      Material material = links.material(resource);
      return new P4kBlueprintRecipe.Line(
          BlueprintIngredientKind.RESOURCE,
          resource,
          null,
          material,
          null,
          material != null ? material.getName() : links.resourceName(resource),
          leaf.quantityScu() == null ? null : product(leaf.quantityScu(), multiplier(leaf)),
          null,
          minQuality);
    }
    UUID itemGuid = P4kGuids.parse(leaf.itemGuid());
    if (itemGuid == null) {
      return null;
    }
    GameItem item = links.item(itemGuid);
    return new P4kBlueprintRecipe.Line(
        BlueprintIngredientKind.ITEM,
        null,
        itemGuid,
        null,
        item,
        item == null ? null : item.getName(),
        null,
        leaf.quantityUnits() == null ? null : units(leaf.quantityUnits(), multiplier(leaf)),
        minQuality);
  }

  /**
   * Builds the modifiers of a cost line.
   *
   * @param line the cost line
   * @return its modifiers
   */
  @NotNull
  private List<P4kBlueprintRecipe.Modifier> modifiersOf(@NotNull CostLine line) {
    List<P4kBlueprintRecipe.Modifier> modifiers = new ArrayList<>();
    for (P4kBlueprintDto.Modifier modifier : Objects.requireNonNull(line.modifiers())) {
      if (modifier != null) {
        modifiers.add(modifier(modifier));
      }
    }
    return modifiers;
  }

  /**
   * Builds one modifier: a single range is its band, several are its segments.
   *
   * @param modifier the catalogue modifier
   * @return the modifier
   */
  @NotNull
  private P4kBlueprintRecipe.Modifier modifier(@NotNull P4kBlueprintDto.Modifier modifier) {
    UUID propertyGuid = P4kGuids.parse(modifier.propertyGuid());
    P4kGameplayPropertyDto property =
        propertyGuid == null ? null : crafting.gameplayProperties().get(propertyGuid);
    List<Range> ranges =
        modifier.ranges() == null
            ? List.of()
            : modifier.ranges().stream().filter(Objects::nonNull).toList();
    Range first = ranges.isEmpty() ? null : ranges.getFirst();
    Range last = ranges.isEmpty() ? null : ranges.getLast();
    List<P4kBlueprintRecipe.Segment> segments =
        ranges.size() < 2
            ? List.of()
            : ranges.stream()
                .map(
                    r ->
                        new P4kBlueprintRecipe.Segment(
                            quality(r.startQuality()), quality(r.endQuality()), r.atStart(), r.atEnd()))
                .toList();
    return new P4kBlueprintRecipe.Modifier(
        propertyGuid,
        property == null ? null : propertyKey(property.className()),
        property == null ? null : P4kLocalisedText.enOf(property.name()),
        first == null ? null : valueKind(first.kind()),
        first == null ? null : quality(first.startQuality()),
        last == null ? null : quality(last.endQuality()),
        first == null ? null : first.atStart(),
        last == null ? null : last.atEnd(),
        segments);
  }

  /**
   * Maps a range kind; an unknown one is reported and reads as {@code null}.
   *
   * @param raw the catalogue's kind
   * @return the value kind, or {@code null}
   */
  @Nullable
  private BlueprintModifierValueKind valueKind(@Nullable String raw) {
    if (raw == null) {
      return null;
    }
    return switch (raw) {
      case "multiplier" -> BlueprintModifierValueKind.MULTIPLIER;
      case "additive" -> BlueprintModifierValueKind.ADDITIVE;
      default -> {
        context.getReport().unknownValue(P4kKind.BLUEPRINT, blueprint, RANGE_KIND_FIELD, raw);
        yield null;
      }
    };
  }

  /**
   * Derives the stat key the SC Wiki uses from a gameplay property's class name.
   *
   * @param className e.g. {@code GPP_Weapon_Damage}
   * @return e.g. {@code weapon_damage}, or {@code null} without a class name
   */
  @Nullable
  static String propertyKey(@Nullable String className) {
    if (className == null || className.isBlank()) {
      return null;
    }
    String key =
        className.regionMatches(true, 0, PROPERTY_PREFIX, 0, PROPERTY_PREFIX.length())
            ? className.substring(PROPERTY_PREFIX.length())
            : className;
    return key.toLowerCase(Locale.ROOT);
  }

  /**
   * Clamps a modifier quality.
   *
   * @param quality the catalogue's quality
   * @return the clamped quality, or {@code null}
   */
  @Nullable
  private static Double quality(@Nullable Double quality) {
    return Quality.clampImported(quality, "blueprint modifier quality");
  }

  /**
   * Returns a line's quantity multiplier.
   *
   * @param line the cost line
   * @return the factor, {@code 1} without one
   */
  private static double multiplier(@NotNull CostLine line) {
    return line.quantityMultiplier() == null ? 1.0d : line.quantityMultiplier();
  }

  /**
   * Multiplies two decimal values exactly, so {@code 2 x 1.15} is {@code 2.3}.
   *
   * @param value the value
   * @param factor the factor
   * @return the product
   */
  static double product(double value, double factor) {
    return BigDecimal.valueOf(value).multiply(BigDecimal.valueOf(factor)).doubleValue();
  }

  /**
   * Multiplies a number of units, rounding half up to whole units.
   *
   * @param units the stored units
   * @param factor the factor
   * @return the whole units
   */
  private static int units(int units, double factor) {
    return BigDecimal.valueOf(units)
        .multiply(BigDecimal.valueOf(factor))
        .setScale(0, RoundingMode.HALF_UP)
        .intValueExact();
  }
}