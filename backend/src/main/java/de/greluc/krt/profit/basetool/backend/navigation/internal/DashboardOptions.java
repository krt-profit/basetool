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

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * The options of one widget instance; an option the type does not offer is {@code null}, and an
 * empty unit means the instance follows the active context (REQ-UI-034).
 *
 * @param orgUnitId the pinned org unit, or {@code null} to follow the context
 * @param days the number of days ahead
 * @param rows the number of rows shown
 * @param status the status filter
 */
public record DashboardOptions(
    @Nullable UUID orgUnitId,
    @Nullable Integer days,
    @Nullable Integer rows,
    @Nullable DashboardStatus status) {

  /** The options of a widget type that offers none. */
  public static final DashboardOptions NONE = new DashboardOptions(null, null, null, null);
}
