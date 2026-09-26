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
import java.util.List;

/**
 * Root object of a StarJump FleetViewer JSON export; unknown layout fields are ignored.
 *
 * @param type the export discriminator (expected {@code "starjumpFleetviewer"}, case-insensitive)
 * @param canvasItems the canvas elements, ship tiles and widgets intermixed; may be {@code null}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StarjumpFleetviewerDto(
    @JsonProperty("type") String type,
    @JsonProperty("canvasItems") List<StarjumpCanvasItemDto> canvasItems) {}
