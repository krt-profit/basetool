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
import java.util.Map;
import java.util.UUID;
import lombok.Builder;

/**
 * SC Wiki vehicle row from {@code /api/vehicles}, used by {@code ScWikiVehicleSyncService} to fill
 * the Wiki-owned columns of the {@code ship_type} matched by {@code external_uuid}.
 *
 * <p>Only Wiki-owned fields are modelled; {@link #description} maps locale to text.
 *
 * <dl>
 *   <dt>{@code uuid}
 *   <dd>in-game asset UUID (the join key against {@code ship_type.external_uuid})
 *   <dt>{@code slug}
 *   <dd>Wiki URL slug
 *   <dt>{@code name}
 *   <dd>display name
 *   <dt>{@code gameName}
 *   <dd>Wiki game-name field
 *   <dt>{@code className}
 *   <dd>RSI engine class name
 *   <dt>{@code vehicleInventory}
 *   <dd>vehicle internal inventory in micro-SCU (Wiki's {@code vehicle_inventory})
 *   <dt>{@code description}
 *   <dd>locale to description text map
 *   <dt>{@code oreCapacity}
 *   <dd>ore storage in SCU
 *   <dt>{@code maxMedicalTier}
 *   <dd>medical service tier, such as {@code T2}
 *   <dt>{@code health}
 *   <dd>hull health points
 *   <dt>{@code shieldHp}
 *   <dd>shield health points
 *   <dt>{@code version}
 *   <dd>upstream game build
 * </dl>
 */
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScWikiVehicleDto(
    @JsonProperty("uuid") UUID uuid,
    @JsonProperty("slug") String slug,
    @JsonProperty("name") String name,
    @JsonProperty("game_name") String gameName,
    @JsonProperty("class_name") String className,
    @JsonProperty("vehicle_inventory") Double vehicleInventory,
    @JsonProperty("description") Map<String, String> description,
    @JsonProperty("ore_capacity") Double oreCapacity,
    @JsonProperty("max_medical_tier") String maxMedicalTier,
    @JsonProperty("health") Double health,
    @JsonProperty("shield_hp") Double shieldHp,
    String version)
    implements ScWikiRow {}
