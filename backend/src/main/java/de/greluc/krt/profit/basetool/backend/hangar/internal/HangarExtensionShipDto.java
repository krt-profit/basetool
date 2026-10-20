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

package de.greluc.krt.profit.basetool.backend.hangar.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One ship of a "Star Citizen: Hangar Extension" fleet JSON export ({@code my-fleet-*.json}); only
 * the fields the import consumes are mapped, unknown fields are ignored (REQ-HANGAR-015).
 *
 * @param matrix the ship-matrix model name (e.g. {@code "600i Explorer"}), the primary match key
 * @param name the export's short name or slug (e.g. {@code "600i-explorer"}), the fallback key
 * @param status the production status text (e.g. {@code "Flight-Ready"}, {@code "In-Concept"})
 * @param insurance the insurance text ({@code "LTI"} or {@code "<n>MI"})
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HangarExtensionShipDto(
    @JsonProperty("matrix") String matrix,
    @JsonProperty("name") String name,
    @JsonProperty("status") String status,
    @JsonProperty("insurance") String insurance) {}
