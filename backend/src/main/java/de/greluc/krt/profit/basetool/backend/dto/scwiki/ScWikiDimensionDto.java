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

/**
 * Bounding-box dimensions nested inside a {@link ScWikiItemDto} (REQ-DATA-015); any axis may be
 * {@code null}.
 *
 * <p>Only the top-level axes are bound; the {@code true_dimension}, {@code cargo_dimension} and
 * {@code ui_dimension} blocks are ignored.
 *
 * @param width the box's width in metres, written to {@code game_item.dimension_x}
 * @param height the box's height in metres, written to {@code game_item.dimension_y}
 * @param length the box's depth / length in metres, written to {@code game_item.dimension_z}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScWikiDimensionDto(
    @JsonProperty("width") Double width,
    @JsonProperty("height") Double height,
    @JsonProperty("length") Double length) {}
