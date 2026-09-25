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

import java.util.UUID;

/**
 * Outpost view for the admin UEX-overrides page.
 *
 * @param id outpost primary key
 * @param name canonical outpost name from UEX
 * @param starSystemName parent star system label
 * @param planetName parent planet label
 * @param hasLoadingDock effective "has loading dock" value, UEX-sourced or admin-pinned
 * @param hasLoadingDockOverridden {@code true} iff an admin has pinned {@code hasLoadingDock}
 */
public record OutpostDto(
    UUID id,
    String name,
    String starSystemName,
    String planetName,
    Boolean hasLoadingDock,
    boolean hasLoadingDockOverridden) {}
