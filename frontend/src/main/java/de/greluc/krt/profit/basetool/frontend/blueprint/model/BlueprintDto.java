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

package de.greluc.krt.profit.basetool.frontend.blueprint.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import de.greluc.krt.profit.basetool.frontend.model.DtoMirror;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Frontend mirror of the backend blueprint DTO consumed by the admin blueprint page. */
@DtoMirror
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
