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

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The widget types the store accepts, with the mode each belongs to, its place in the mode's
 * default, the options it offers and their defaults (REQ-UI-034). A new type or option is a change
 * to the spec's catalogue table.
 */
@Getter
public enum DashboardWidgetType {

  /** The missions of the coming days. */
  UPCOMING_DAYS(
      DashboardMode.MEMBER,
      DashboardColumn.WIDE,
      true,
      EnumSet.of(DashboardOption.UNIT, DashboardOption.DAYS, DashboardOption.ROWS),
      new DashboardOptions(null, 7, 5, null)),

  /** The announcement. */
  INFORMATION(
      DashboardMode.MEMBER,
      DashboardColumn.WIDE,
      true,
      EnumSet.noneOf(DashboardOption.class),
      DashboardOptions.NONE),

  /** The member's favourites. */
  FAVOURITES(
      DashboardMode.MEMBER,
      DashboardColumn.NARROW,
      true,
      EnumSet.noneOf(DashboardOption.class),
      DashboardOptions.NONE),

  /** The member's notifications. */
  NOTIFICATIONS(
      DashboardMode.MEMBER,
      DashboardColumn.NARROW,
      true,
      EnumSet.of(DashboardOption.ROWS, DashboardOption.STATUS),
      new DashboardOptions(null, null, 5, DashboardStatus.UNREAD)),

  /** The member's next missions: those of the own units and those the member is signed up for. */
  NEXT_MISSION(
      DashboardMode.MEMBER,
      DashboardColumn.NARROW,
      false,
      EnumSet.of(DashboardOption.ROWS),
      new DashboardOptions(null, null, 5, null)),

  /** The readiness of the member's hangar. */
  HANGAR_READINESS(
      DashboardMode.MEMBER,
      DashboardColumn.NARROW,
      false,
      EnumSet.noneOf(DashboardOption.class),
      DashboardOptions.NONE);

  /** The day counts a widget with the {@link DashboardOption#DAYS} option accepts. */
  public static final Set<Integer> DAY_CHOICES = Set.of(3, 7, 14);

  /** The row counts a widget with the {@link DashboardOption#ROWS} option accepts. */
  public static final Set<Integer> ROW_CHOICES = Set.of(3, 5, 10);

  private final DashboardMode mode;
  private final DashboardColumn defaultColumn;
  private final boolean inDefault;
  private final Set<DashboardOption> options;
  private final DashboardOptions defaults;

  DashboardWidgetType(
      DashboardMode mode,
      DashboardColumn defaultColumn,
      boolean inDefault,
      Set<DashboardOption> options,
      DashboardOptions defaults) {
    this.mode = mode;
    this.defaultColumn = defaultColumn;
    this.inDefault = inDefault;
    this.options = options;
    this.defaults = defaults;
  }

  /**
   * The widget types of a mode's default dashboard in their display order.
   *
   * @param mode the mode
   * @return the default types, wide column first
   */
  public static @NotNull List<DashboardWidgetType> defaultsOf(@NotNull DashboardMode mode) {
    return Stream.of(values())
        .filter(type -> type.mode == mode && type.inDefault)
        .sorted(Comparator.comparing(DashboardWidgetType::getDefaultColumn))
        .toList();
  }

  /**
   * Checks that options fit this type: every option the type offers is within its allowed values
   * and every other option is absent.
   *
   * @param candidate the options to check
   * @return the first violated rule, or {@code null} when the options are valid
   */
  public @Nullable String violation(@NotNull DashboardOptions candidate) {
    if (candidate.orgUnitId() != null && !options.contains(DashboardOption.UNIT)) {
      return "The widget offers no unit option.";
    }
    if (candidate.days() != null
        && (!options.contains(DashboardOption.DAYS) || !DAY_CHOICES.contains(candidate.days()))) {
      return "The days option is not allowed here.";
    }
    if (candidate.rows() != null
        && (!options.contains(DashboardOption.ROWS) || !ROW_CHOICES.contains(candidate.rows()))) {
      return "The rows option is not allowed here.";
    }
    if (candidate.status() != null && !options.contains(DashboardOption.STATUS)) {
      return "The widget offers no status option.";
    }
    return null;
  }
}
