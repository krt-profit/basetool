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

import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardCatalogueResponse;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardOption;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardStatus;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardWidgetDescriptor;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardWidgetType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Reads the options a widget endpoint receives: a value outside the type's choices falls back to
 * the type's default from the catalogue, so a stale page never breaks a widget (REQ-UI-034).
 */
@Component
@RequiredArgsConstructor
class DashboardWidgetOptions {

  private static final String ORDER_OPEN = "OPEN";

  private static final String ORDER_IN_PROGRESS = "IN_PROGRESS";

  private final DashboardCatalogue catalogue;

  /**
   * The days ahead, or the range in days, of a widget that offers either.
   *
   * @param type the widget type
   * @param requested the requested days, or {@code null}
   * @return the requested days when the type offers them, else the type's default
   */
  int days(@NotNull DashboardWidgetType type, @Nullable Integer requested) {
    DashboardCatalogueResponse all = catalogue.snapshot();
    DashboardWidgetDescriptor descriptor = catalogue.descriptor(type);
    List<Integer> choices =
        descriptor.offers(DashboardOption.RANGE) ? all.rangeChoices() : all.dayChoices();
    return requested != null && choices.contains(requested)
        ? requested
        : defaultOf(descriptor.defaultDays(), type, "days");
  }

  /**
   * The rows shown by a widget that offers the option.
   *
   * @param type the widget type
   * @param requested the requested rows, or {@code null}
   * @return the requested rows when the type offers them, else the type's default
   */
  int rows(@NotNull DashboardWidgetType type, @Nullable Integer requested) {
    DashboardCatalogueResponse all = catalogue.snapshot();
    return requested != null && all.rowChoices().contains(requested)
        ? requested
        : defaultOf(catalogue.descriptor(type).defaultRows(), type, "rows");
  }

  /**
   * The status filter of a widget that offers the option.
   *
   * @param type the widget type
   * @param requested the requested status, or {@code null}
   * @return the requested status when the type offers it, else the type's default
   */
  @NotNull
  DashboardStatus status(@NotNull DashboardWidgetType type, @Nullable DashboardStatus requested) {
    DashboardWidgetDescriptor descriptor = catalogue.descriptor(type);
    return requested != null && descriptor.offersStatus(requested)
        ? requested
        : defaultOf(descriptor.defaultStatus(), type, "status");
  }

  /**
   * The order statuses an order widget's status filter reads.
   *
   * @param status the status filter of the widget, as {@link #status} resolved it
   * @return the backend order status names, never empty
   */
  static @NotNull List<String> orderStatuses(@NotNull DashboardStatus status) {
    if (status == DashboardStatus.OPEN) {
      return List.of(ORDER_OPEN);
    }
    return status == DashboardStatus.IN_PROGRESS
        ? List.of(ORDER_IN_PROGRESS)
        : List.of(ORDER_OPEN, ORDER_IN_PROGRESS);
  }

  /**
   * The coverage a supply-needs status filter asks the backend for.
   *
   * @param status the status filter of the widget
   * @return {@code UNCOVERED} or {@code PARTIAL}, or {@code null} for every open need
   */
  static @Nullable String needCoverage(@NotNull DashboardStatus status) {
    if (status == DashboardStatus.UNCOVERED || status == DashboardStatus.PARTIAL) {
      return status.name();
    }
    return null;
  }

  private static <T> @NotNull T defaultOf(
      @Nullable T value, @NotNull DashboardWidgetType type, @NotNull String option) {
    if (value == null) {
      throw new IllegalStateException(type + " has no default " + option + " in the catalogue");
    }
    return value;
  }
}
