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

package de.greluc.krt.profit.basetool.backend.dto.scwiki;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;

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
    @JsonProperty("dismantle") ScWikiBlueprintDismantleDto dismantle)
    implements ScWikiRow {}
