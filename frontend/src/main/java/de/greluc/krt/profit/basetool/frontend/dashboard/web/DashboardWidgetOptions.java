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
import java.util.List;
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

  private static final String ORDER_OPEN = "OPEN";

  private static final String ORDER_IN_PROGRESS = "IN_PROGRESS";

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
    return rows(requested, DEFAULT_ROWS);
  }

  /**
   * The rows shown by a widget whose default is not {@link #DEFAULT_ROWS}.
   *
   * @param requested the requested rows, or {@code null}
   * @param fallback the widget's default rows
   * @return the requested rows when offered, else {@code fallback}
   */
  static int rows(@Nullable Integer requested, int fallback) {
    return requested != null && DashboardWidgetType.ROW_CHOICES.contains(requested)
        ? requested
        : fallback;
  }

  /**
   * The status filter of the assigned-orders widget.
   *
   * @param requested the requested status, or {@code null}
   * @return the requested status when the widget offers it, else {@link
   *     DashboardStatus#OPEN_ACTIVE}
   */
  static @NotNull DashboardStatus orderStatus(@Nullable DashboardStatus requested) {
    return requested != null && DashboardWidgetType.ASSIGNED_ORDERS.offersStatus(requested)
        ? requested
        : DashboardStatus.OPEN_ACTIVE;
  }

  /**
   * The order statuses an assigned-orders status filter reads.
   *
   * @param status the status filter of the widget, as {@link #orderStatus} resolved it
   * @return the backend order status names
   */
  static @NotNull List<String> orderStatuses(@NotNull DashboardStatus status) {
    return switch (status) {
      case OPEN -> List.of(ORDER_OPEN);
      case IN_PROGRESS -> List.of(ORDER_IN_PROGRESS);
      case OPEN_ACTIVE, UNREAD, ALL -> List.of(ORDER_OPEN, ORDER_IN_PROGRESS);
    };
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
