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
 * Bounding-box dimensions nested inside a {@link ScWikiItemDto} (SC_WIKI_SYNC_PLAN.md §3.3). Any
 * axis may be {@code null} when the Wiki omits it.
 *
 * <p>The record originally bound {@code {x, y, z}}, which the Wiki has never served: the nested
 * object is {@code {width, height, length, volume, true_dimension, cargo_dimension, ui_dimension,
 * …}} (verified against the live API on 2026-08-28). Because the record is {@link
 * JsonIgnoreProperties}{@code (ignoreUnknown = true)}, the three absent names decoded to {@code
 * null} instead of failing, so {@code game_item.dimension_x/y/z} was written {@code null} for every
 * item on every run and the columns never held a single value — REQ-DATA-015 / ADR-0148.
 *
 * <p>Only the three top-level axes are bound. The sibling {@code true_dimension} / {@code
 * cargo_dimension} / {@code ui_dimension} blocks are deliberately left unmapped: they answer
 * different questions (hitbox, container footprint, inventory-grid slot) and no local column asks
 * any of them.
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
