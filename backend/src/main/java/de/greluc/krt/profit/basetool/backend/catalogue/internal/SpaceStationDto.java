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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import java.util.UUID;

/**
 * Outbound projection of a {@code SpaceStation} aggregate as needed by the admin UEX-overrides
 * page.
 *
 * <p>Only the columns the admin UI actually renders or acts on are exposed: identifier, name,
 * star-system / planet labels for context, the effective {@code hasLoadingDock} value and the
 * {@code hasLoadingDockOverridden} flag that tells the UI whether the value is admin-pinned or
 * UEX-managed.
 *
 * @param id space station primary key
 * @param name canonical station name as supplied by UEX
 * @param starSystemName parent star system label (denormalised by UEX)
 * @param planetName parent planet label, or {@code null} for free-floating stations
 * @param hasLoadingDock current effective "has loading dock" value (UEX-sourced or admin-pinned)
 * @param hasLoadingDockOverridden {@code true} iff an admin has pinned {@code hasLoadingDock}
 */
public record SpaceStationDto(
    UUID id,
    String name,
    String starSystemName,
    String planetName,
    Boolean hasLoadingDock,
    boolean hasLoadingDockOverridden) {}
