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

package de.greluc.krt.profit.basetool.frontend.dashboard.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One widget type of the backend's catalogue (REQ-UI-034).
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
@DtoMirror
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
   * The type's constant name, which the layout writes and the page scripts use.
   *
   * @return the name, e.g. {@code UPCOMING_DAYS}
   */
  public @NotNull String name() {
    return type.name();
  }

  /**
   * The type's endpoint segment.
   *
   * @return the lower-case name with dashes
   */
  public @NotNull String slug() {
    return type.slug();
  }

  /**
   * The message key of the widget's title.
   *
   * @return the bundle key
   */
  public @NotNull String labelKey() {
    return type.labelKey();
  }

  /**
   * Whether the widget offers an option.
   *
   * @param option the option
   * @return {@code true} iff it is offered
   */
  public boolean offers(@NotNull DashboardOption option) {
    return options.contains(option);
  }

  /**
   * Whether the widget's status option offers a status.
   *
   * @param status the status
   * @return {@code true} iff the status is one of the widget's choices
   */
  public boolean offersStatus(@NotNull DashboardStatus status) {
    return statuses.contains(status);
  }

  /**
   * The options as a space-separated attribute value for the script that edits them.
   *
   * @return the lower-case option names in display order
   */
  public @NotNull String optionsAttribute() {
    return options.stream()
        .map(option -> option.name().toLowerCase(Locale.ROOT))
        .collect(Collectors.joining(" "));
  }

  /**
   * The statuses the widget's status option offers as a space-separated attribute value for the
   * script that edits the options.
   *
   * @return the status names in display order, empty when the widget has no status option
   */
  public @NotNull String statusesAttribute() {
    return statuses.stream().map(Enum::name).collect(Collectors.joining(" "));
  }
}
