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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One vehicle entry of a Fleetyards hangar JSON export; only the fields the import consumes are
 * mapped, unknown fields are ignored.
 *
 * @param name the ship-model name (e.g. {@code "A1 Spirit"}), the primary match key
 * @param shipName the user's custom ship name, or {@code null}/blank when unnamed
 * @param slug the kebab-case slug (e.g. {@code "crus-a1-spirit"}), the fallback match key
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FleetyardsEntryDto(
    @JsonProperty("name") String name,
    @JsonProperty("shipName") String shipName,
    @JsonProperty("slug") String slug) {}
