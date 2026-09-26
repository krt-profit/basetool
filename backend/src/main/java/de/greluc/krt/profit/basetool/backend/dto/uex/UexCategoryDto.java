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

package de.greluc.krt.profit.basetool.backend.dto.uex;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * UEX Corp {@code /categories} row, mapped to the {@code UexCategory} entity by {@code
 * UexCategoryRefService}.
 *
 * <p>{@code type} decides whether entries of this category become {@code game_item} ({@code
 * "item"}) or {@code ship_type} ({@code "vehicle"}) rows.
 *
 * @param id UEX integer category id; stable across runs
 * @param type {@code "item"} or {@code "vehicle"}
 * @param section coarse grouping, e.g. {@code "Armor"}, {@code "Vehicle Weapons"}, {@code
 *     "Systems"}
 * @param name subcategory display name, e.g. {@code "Helmets"}, {@code "Torso"}
 * @param isGameRelated UEX flag (0/1) used to filter categories
 * @param isMining UEX flag (0/1)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UexCategoryDto(
    @JsonProperty("id") Integer id,
    @JsonProperty("type") String type,
    @JsonProperty("section") String section,
    @JsonProperty("name") String name,
    @JsonProperty("is_game_related") Integer isGameRelated,
    @JsonProperty("is_mining") Integer isMining) {}
