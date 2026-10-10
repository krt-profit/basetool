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

package de.greluc.krt.profit.basetool.frontend.catalogue.model;

import de.greluc.krt.profit.basetool.frontend.model.DtoMirror;
import java.util.UUID;

/**
 * Frontend-side projection of the {@code SpaceStation} payload returned by the backend's
 * UEX-overrides endpoint. Mirrors {@code
 * de.greluc.krt.profit.basetool.backend.model.dto.SpaceStationDto} exactly.
 *
 * @param id space station primary key
 * @param name canonical station name as supplied by UEX
 * @param starSystemName parent star system label
 * @param planetName parent planet label, or {@code null} for free-floating stations
 * @param hasLoadingDock current effective "has loading dock" value
 * @param hasLoadingDockOverridden whether {@code hasLoadingDock} is admin-pinned
 */
@DtoMirror
public record SpaceStationDto(
    UUID id,
    String name,
    String starSystemName,
    String planetName,
    Boolean hasLoadingDock,
    boolean hasLoadingDockOverridden) {}
