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

import java.util.List;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;

/**
 * The dashboard widget catalogue (REQ-UI-034): every widget type with its mode, column, options and
 * defaults, and the choices the options offer. The frontend builds its views from this and keeps
 * only what depends on the caller's rights.
 *
 * @param dayChoices the day counts the days option offers
 * @param rangeChoices the day counts the range option offers
 * @param rowChoices the row counts the rows option offers
 * @param statusChoices every status filter any widget offers, in display order
 * @param widgets the widget types in declaration order
 */
public record DashboardCatalogueResponse(
    @NotNull List<Integer> dayChoices,
    @NotNull List<Integer> rangeChoices,
    @NotNull List<Integer> rowChoices,
    @NotNull List<DashboardStatus> statusChoices,
    @NotNull List<DashboardWidgetDescriptor> widgets) {

  /**
   * Copies the lists so the record stays immutable.
   *
   * @param dayChoices the day counts
   * @param rangeChoices the range counts
   * @param rowChoices the row counts
   * @param statusChoices the status filters
   * @param widgets the widget types
   */
  public DashboardCatalogueResponse {
    dayChoices = List.copyOf(dayChoices);
    rangeChoices = List.copyOf(rangeChoices);
    rowChoices = List.copyOf(rowChoices);
    statusChoices = List.copyOf(statusChoices);
    widgets = List.copyOf(widgets);
  }

  /**
   * The catalogue the application ships.
   *
   * @return the catalogue built from {@link DashboardWidgetType}
   */
  public static @NotNull DashboardCatalogueResponse current() {
    return new DashboardCatalogueResponse(
        DashboardWidgetType.DAY_CHOICES,
        DashboardWidgetType.RANGE_CHOICES,
        DashboardWidgetType.ROW_CHOICES,
        List.of(DashboardStatus.values()),
        Stream.of(DashboardWidgetType.values()).map(DashboardWidgetDescriptor::of).toList());
  }
}
