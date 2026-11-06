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

package de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintCatalogueMetadata;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * SC Wiki blueprint row from {@code /api/blueprints}.
 *
 * <p>{@link #ingredients} and {@link #dismantleReturns} are read straight off the list payload and
 * may be {@code null}; the blueprint is persisted either way.
 *
 * @param uuid SC Wiki blueprint UUID (upsert key)
 * @param key Wiki internal key, e.g. {@code "BP_CRAFT_AMRS_LaserCannon_S1"}
 * @param outputItemUuid authoritative output-item UUID (trusted over any nested {@code
 *     output.uuid})
 * @param outputName display name of the output item
 * @param categoryUuid Wiki category UUID
 * @param craftTimeSeconds craft time in seconds
 * @param isAvailableByDefault whether the recipe is unlocked by default
 * @param gameVersion game version the row was last seen in
 * @param ingredientCount Wiki-reported ingredient count
 * @param unlockingMissionsCount Wiki-reported unlocking-mission count
 * @param ingredients ordered ingredient lines, or {@code null}
 * @param dismantleReturns ordered dismantle-return lines, or {@code null}
 * @param requirementGroups named build slots with their ingredients and stat modifiers; detail
 *     response only, {@code null} on list payloads
 * @param summaryProperties de-duplicated roll-up of the affected stats; detail response only
 * @param dismantle dismantle time / efficiency; detail response only
 * @param unlockingMissions mission unlock rewards; detail response only
 * @param unlockingMissionGroups missions grouped by reward chance
 * @param aspects source aspect inputs and choice constraints
 * @param tiers complete source requirement trees for each crafting tier
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScWikiBlueprintDto(
    @JsonProperty("uuid") UUID uuid,
    @JsonProperty("key") String key,
    @JsonProperty("output_item_uuid") UUID outputItemUuid,
    @JsonProperty("output_name") String outputName,
    @JsonProperty("category_uuid") UUID categoryUuid,
    @JsonProperty("craft_time_seconds") Integer craftTimeSeconds,
    @JsonProperty("is_available_by_default") Boolean isAvailableByDefault,
    @JsonProperty("game_version") String gameVersion,
    @JsonProperty("ingredient_count") Integer ingredientCount,
    @JsonProperty("unlocking_missions_count") Integer unlockingMissionsCount,
    @JsonProperty("ingredients") List<ScWikiBlueprintIngredientDto> ingredients,
    @JsonProperty("dismantle_returns") List<ScWikiBlueprintIngredientDto> dismantleReturns,
    @JsonProperty("requirement_groups") List<ScWikiBlueprintRequirementGroupDto> requirementGroups,
    @JsonProperty("summary_properties") List<ScWikiBlueprintSummaryPropertyDto> summaryProperties,
    @JsonProperty("dismantle") ScWikiBlueprintDismantleDto dismantle,
    @Nullable @JsonProperty("unlocking_missions")
        List<BlueprintCatalogueMetadata.Mission> unlockingMissions,
    @Nullable @JsonProperty("unlocking_missions_grouped")
        List<BlueprintCatalogueMetadata.MissionGroup> unlockingMissionGroups,
    @Nullable @JsonProperty("aspects") BlueprintCatalogueMetadata.Aspects aspects,
    @Nullable @JsonProperty("tiers") List<BlueprintCatalogueMetadata.Tier> tiers)
    implements ScWikiRow {

  /**
   * Projects only explicitly supplied detail sections, keeping list fallbacks distinguishable.
   *
   * @return optional typed detail evidence
   */
  @Nullable
  public BlueprintCatalogueMetadata metadata() {
    Set<BlueprintCatalogueMetadata.DetailSection> loaded =
        EnumSet.noneOf(BlueprintCatalogueMetadata.DetailSection.class);
    if (unlockingMissions != null) {
      loaded.add(BlueprintCatalogueMetadata.DetailSection.MISSIONS);
    }
    if (unlockingMissionGroups != null) {
      loaded.add(BlueprintCatalogueMetadata.DetailSection.MISSION_GROUPS);
    }
    if (aspects != null) {
      loaded.add(BlueprintCatalogueMetadata.DetailSection.ASPECTS);
    }
    if (tiers != null) {
      loaded.add(BlueprintCatalogueMetadata.DetailSection.TIERS);
    }
    if (loaded.isEmpty()) {
      return null;
    }
    return new BlueprintCatalogueMetadata(
        BlueprintCatalogueMetadata.Source.SC_WIKI,
        gameVersion,
        loaded,
        unlockingMissions == null ? List.of() : unlockingMissions,
        unlockingMissionGroups == null ? List.of() : unlockingMissionGroups,
        aspects,
        tiers == null ? List.of() : tiers);
  }
}
