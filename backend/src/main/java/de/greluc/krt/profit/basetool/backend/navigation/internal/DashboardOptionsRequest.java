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

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Write DTO that replaces the options of one instance. An option the type offers and the request
 * leaves out takes its default; one the type does not offer must be absent. A stale {@code version}
 * is rejected with HTTP 409.
 *
 * @param orgUnitId the pinned org unit, {@code null} to follow the context
 * @param days the days ahead
 * @param rows the rows shown
 * @param status the status filter
 * @param version the option version the caller last saw
 */
public record DashboardOptionsRequest(
    UUID orgUnitId,
    Integer days,
    Integer rows,
    DashboardStatus status,
    @NotNull @Min(0) Long version) {}
