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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One widget type of the catalogue as the frontend reads it (REQ-UI-034).
 *
 * @param type the widget type
 * @param mode the mode whose dashboard offers it
 * @param defaultColumn the column an instance sits in unless the member moves it
 * @param inDefault whether the mode's default dashboard holds an instance
 * @param options the options it offers in the order the options dialog lists them
 * @param statuses the status choices in display order, empty without the status option
 * @param defaultDays the days ahead or range an instance starts with, {@code null} without either
 * @param defaultRows the rows an instance shows by default, {@code null} without the rows option
 * @param defaultStatus the status an instance starts with, {@code null} without the status option
 */
public record DashboardWidgetDescriptor(
    @NotNull DashboardWidgetType type,
    @NotNull DashboardMode mode,
    @NotNull DashboardColumn defaultColumn,
    boolean inDefault,
    @NotNull List<DashboardOption> options,
    @NotNull List<DashboardStatus> statuses,
    @Nullable Integer defaultDays,
    @Nullable Integer defaultRows,
    @Nullable DashboardStatus defaultStatus) {

  /**
   * Copies the lists so the record stays immutable.
   *
   * @param type the widget type
   * @param mode the mode
   * @param defaultColumn the default column
   * @param inDefault whether the default dashboard holds an instance
   * @param options the offered options
   * @param statuses the status choices
   * @param defaultDays the default days
   * @param defaultRows the default rows
   * @param defaultStatus the default status
   */
  public DashboardWidgetDescriptor {
    options = List.copyOf(options);
    statuses = List.copyOf(statuses);
  }

  /**
   * Describes a widget type.
   *
   * @param type the widget type
   * @return its descriptor
   */
  public static @NotNull DashboardWidgetDescriptor of(@NotNull DashboardWidgetType type) {
    DashboardOptions defaults = type.getDefaults();
    return new DashboardWidgetDescriptor(
        type,
        type.getMode(),
        type.getDefaultColumn(),
        type.isInDefault(),
        type.getOptions().stream().sorted().toList(),
        List.copyOf(type.getStatuses()),
        defaults.days(),
        defaults.rows(),
        defaults.status());
  }
}
