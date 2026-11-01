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

package de.greluc.krt.profit.basetool.frontend.dashboard.web;

import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardStatus;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardWidgetType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Reads the options a widget endpoint receives: a value outside the offered set falls back to the
 * default, so a stale page never breaks a widget (REQ-UI-034).
 */
final class DashboardWidgetOptions {

  /** The days ahead of a widget that offers the option and was given none. */
  static final int DEFAULT_DAYS = 7;

  /** The range in days of a sync status that was given none. */
  static final int DEFAULT_RANGE_DAYS = 7;

  /** The rows of a widget that offers the option and was given none. */
  static final int DEFAULT_ROWS = 5;

  private DashboardWidgetOptions() {}

  /**
   * The days ahead.
   *
   * @param requested the requested days, or {@code null}
   * @return the requested days when offered, else {@link #DEFAULT_DAYS}
   */
  static int days(@Nullable Integer requested) {
    return requested != null && DashboardWidgetType.DAY_CHOICES.contains(requested)
        ? requested
        : DEFAULT_DAYS;
  }

  /**
   * The range of the sync status in days.
   *
   * @param requested the requested range, or {@code null}
   * @return the requested range when offered, else {@link #DEFAULT_RANGE_DAYS}
   */
  static int range(@Nullable Integer requested) {
    return requested != null && DashboardWidgetType.RANGE_CHOICES.contains(requested)
        ? requested
        : DEFAULT_RANGE_DAYS;
  }

  /**
   * The rows shown.
   *
   * @param requested the requested rows, or {@code null}
   * @return the requested rows when offered, else {@link #DEFAULT_ROWS}
   */
  static int rows(@Nullable Integer requested) {
    return requested != null && DashboardWidgetType.ROW_CHOICES.contains(requested)
        ? requested
        : DEFAULT_ROWS;
  }

  /**
   * The status filter of the notifications widget.
   *
   * @param requested the requested status, or {@code null}
   * @return the requested status, else {@link DashboardStatus#UNREAD}
   */
  static @NotNull DashboardStatus status(@Nullable DashboardStatus requested) {
    return requested != null ? requested : DashboardStatus.UNREAD;
  }
}
