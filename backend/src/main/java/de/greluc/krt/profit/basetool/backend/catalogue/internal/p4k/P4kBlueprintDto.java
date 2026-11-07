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

package de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * One entry of {@code blueprints} in catalogue version 2, bound to the fields the import writes or
 * matches on; every other field is skipped.
 *
 * @param guid the Record GUID
 * @param className the Record name
 * @param recordPath the record path without {@code .xml}
 * @param key the blueprint key, e.g. {@code BP_CRAFT_cds_armor_heavy_arms_01_01_01}
 * @param name the localised name, almost always a placeholder; a blueprint is named through its
 *     produced item
 * @param categoryGuid the GUID of the {@code blueprintCategories} entry, or {@code null}
 * @param producedItemGuid the GUID of the item the blueprint produces, or {@code null}
 * @param craftTimeSeconds the craft time of the first tier in seconds, or {@code null}
 * @param ingredients the cost lines of the first tier, flattened; {@code null} when the file has
 *     none
 * @param tiers every tier in stored order; {@code null} when the file has none
 * @param defaultAvailable whether a player has the blueprint by default, or {@code null}
 * @param dismantle the dismantle process and the tier the produced item returns, or {@code null}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record P4kBlueprintDto(
    @Nullable String guid,
    @Nullable String className,
    @Nullable String recordPath,
    @Nullable String key,
    @Nullable P4kLocalisedText name,
    @Nullable String categoryGuid,
    @Nullable String producedItemGuid,
    @Nullable Integer craftTimeSeconds,
    @Nullable List<Ingredient> ingredients,
    @Nullable List<Tier> tiers,
    @Nullable Boolean defaultAvailable,
    @Nullable Dismantle dismantle)
    implements P4kRecord {

  /**
   * One flattened cost line of the first tier, bound to the commodity join (REQ-P4K-026).
   *
   * @param resourceGuid the {@code ResourceType} of a resource line, joined to a commodity's {@code
   *     subtypeGuid}; {@code null} on an item line
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Ingredient(@Nullable String resourceGuid) {}

  /**
   * One {@code CraftingBlueprintTier}.
   *
   * @param craftTimeSeconds the tier's craft time in whole seconds, or {@code null}
   * @param mandatory the mandatory cost, or {@code null}
   * @param optional the optional costs in stored order, or {@code null}
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Tier(
      @Nullable Integer craftTimeSeconds,
      @Nullable CostLine mandatory,
      @Nullable List<CostLine> optional) {}

  /**
   * One cost line of a tier: a selection, a resource line or an item line.
   *
   * @param kind {@code select}, {@code resource} or {@code item}; an unknown value maps to nothing
   * @param count {@code select}: how many options the player chooses
   * @param slot {@code select}: the selection's debug name, e.g. {@code CASING}
   * @param options {@code select}: the options in stored order
   * @param resourceGuid {@code resource}: the {@code ResourceType} GUID
   * @param itemGuid {@code item}: the entity GUID
   * @param quantityScu {@code resource}: the quantity in SCU before the multiplier
   * @param quantityUnits {@code item}: the number of units before the multiplier
   * @param minQuality {@code resource} and {@code item}: the minimum quality
   * @param quantityMultiplier the factor the Basetool applies to the quantity, or {@code null}
   * @param modifiers the stat modifiers of the line
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CostLine(
      @Nullable String kind,
      @Nullable Integer count,
      @Nullable String slot,
      @Nullable List<CostLine> options,
      @Nullable String resourceGuid,
      @Nullable String itemGuid,
      @Nullable Double quantityScu,
      @Nullable Integer quantityUnits,
      @Nullable Integer minQuality,
      @Nullable Double quantityMultiplier,
      @Nullable List<Modifier> modifiers) {}

  /**
   * One stat modifier of a cost line.
   *
   * @param propertyGuid the {@code gameplayProperties} GUID of the stat
   * @param ranges the value ranges, linear in the quality between their ends
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Modifier(@Nullable String propertyGuid, @Nullable List<Range> ranges) {}

  /**
   * One value range of a modifier.
   *
   * @param kind {@code multiplier} or {@code additive}; an unknown value maps to nothing
   * @param startQuality the quality the range starts at
   * @param endQuality the quality the range ends at
   * @param atStart the value at the start quality
   * @param atEnd the value at the end quality
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Range(
      @Nullable String kind,
      @Nullable Double startQuality,
      @Nullable Double endQuality,
      @Nullable Double atStart,
      @Nullable Double atEnd) {}

  /**
   * The dismantle link of a blueprint.
   *
   * @param processGuid the {@code dismantleProcesses} GUID
   * @param tierIndex the index into {@code tiers} of the tier the produced item returns
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Dismantle(@Nullable String processGuid, @Nullable Integer tierIndex) {}
}
