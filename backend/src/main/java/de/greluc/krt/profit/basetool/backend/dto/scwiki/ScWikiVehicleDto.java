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
import java.util.Map;
import java.util.UUID;

/**
 * SC Wiki vehicle row from {@code /api/vehicles}, used by {@code ScWikiVehicleSyncService} to fill
 * the Wiki-owned columns of the {@code ship_type} matched by {@code external_uuid}.
 *
 * <p>Only Wiki-owned fields are modelled; {@link #description} maps locale to text.
 *
 * @param uuid in-game asset UUID (the join key against {@code ship_type.external_uuid})
 * @param slug Wiki URL slug
 * @param name display name
 * @param gameName Wiki game-name field
 * @param className RSI engine class name
 * @param vehicleInventory vehicle internal inventory in SCU (Wiki's {@code vehicle_inventory})
 * @param description locale → description text map ({@code en_EN}, {@code de_DE}, …)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScWikiVehicleDto(
    @JsonProperty("uuid") UUID uuid,
    @JsonProperty("slug") String slug,
    @JsonProperty("name") String name,
    @JsonProperty("game_name") String gameName,
    @JsonProperty("class_name") String className,
    @JsonProperty("vehicle_inventory") Double vehicleInventory,
    @JsonProperty("description") Map<String, String> description)
    implements ScWikiRow {}
