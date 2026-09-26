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
 * One {@code canvasItems} element of a StarJump FleetViewer JSON export; only {@code SHIP} items
 * are imported and unknown fields are ignored.
 *
 * @param itemType the canvas-item discriminator; only {@code "SHIP"} (case-insensitive) is imported
 * @param shipSlug FleetViewer's ship slug, the fallback match key against {@code ShipType.uexSlug}
 *     / {@code ShipType.scwikiSlug}
 * @param variantSlug FleetViewer's variant slug; unused
 * @param defaultText the human-readable ship name, the primary name-matcher input
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StarjumpCanvasItemDto(
    @JsonProperty("itemType") String itemType,
    @JsonProperty("shipSlug") String shipSlug,
    @JsonProperty("variantSlug") String variantSlug,
    @JsonProperty("defaultText") String defaultText) {}
