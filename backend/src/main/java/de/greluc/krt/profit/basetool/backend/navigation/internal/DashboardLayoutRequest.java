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

package de.greluc.krt.profit.basetool.backend.navigation.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Write DTO that replaces a mode's layout: the instances in display order. An item without an id
 * adds an instance with the type's default options, a stored instance that is missing is removed. A
 * stale {@code version} is rejected with HTTP 409.
 *
 * @param widgets the instances in display order, at most {@link
 *     NavigationLimits#MAX_DASHBOARD_WIDGETS}
 * @param version the layout version the caller last saw
 */
public record DashboardLayoutRequest(
    @NotNull @Size(max = NavigationLimits.MAX_DASHBOARD_WIDGETS)
        List<@NotNull @Valid DashboardLayoutItem> widgets,
    @NotNull @Min(0) Long version) {}
