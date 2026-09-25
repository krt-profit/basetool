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

package de.greluc.krt.profit.basetool.backend.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Admin view of a synced crafting blueprint: recipe header, requirement groups with ingredients and
 * modifiers, the flat ingredient list, the stat roll-up and the dismantle returns.
 *
 * @param id local primary key
 * @param scwikiUuid SC Wiki blueprint UUID
 * @param scwikiKey Wiki internal key (e.g. {@code "BP_CRAFT_AMRS_LaserCannon_S1"})
 * @param outputName display name of the crafted item
 * @param craftTimeSeconds craft time in seconds
 * @param isAvailableByDefault whether the recipe is unlocked by default
 * @param ingredientCount Wiki-reported distinct ingredient count
 * @param unlockingMissionsCount Wiki-reported count of missions that unlock the recipe
 * @param gameVersionSeen game version the recipe was last seen in
 * @param dismantleTimeSeconds dismantle duration in seconds, or {@code null}
 * @param dismantleEfficiency fraction of inputs recovered on dismantle, or {@code null}
 * @param scwikiSyncedAt timestamp of the last successful SC Wiki sync touch
 * @param requirementGroups build slots with their ingredients and stat modifiers
 * @param ingredients the flat ingredient list (fallback when no groups are present)
 * @param summaryProperties roll-up of the stats this blueprint affects
 * @param dismantleReturns commodities recovered on dismantle
 * @param version optimistic-lock version
 */
public record BlueprintDto(
    UUID id,
    UUID scwikiUuid,
    String scwikiKey,
    String outputName,
    Integer craftTimeSeconds,
    @JsonProperty("isAvailableByDefault") Boolean isAvailableByDefault,
    Integer ingredientCount,
    Integer unlockingMissionsCount,
    String gameVersionSeen,
    Integer dismantleTimeSeconds,
    Double dismantleEfficiency,
    Instant scwikiSyncedAt,
    List<BlueprintRequirementGroupDto> requirementGroups,
    List<BlueprintRequirementIngredientDto> ingredients,
    List<BlueprintSummaryPropertyDto> summaryProperties,
    List<BlueprintDismantleReturnDto> dismantleReturns,
    Long version) {}
