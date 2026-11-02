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

import static de.greluc.krt.profit.basetool.backend.navigation.internal.DashboardOptions.DEFAULT_BANK_ROWS;
import static de.greluc.krt.profit.basetool.backend.navigation.internal.DashboardOptions.DEFAULT_DAYS;
import static de.greluc.krt.profit.basetool.backend.navigation.internal.DashboardOptions.DEFAULT_ROWS;

import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The widget types the store accepts, with the mode each belongs to, its place in the mode's
 * default, the options it offers and their defaults (REQ-UI-034). This enum is the one catalogue:
 * the frontend reads it through {@link DashboardCatalogueResponse}. A new type or option is a
 * change to the spec's catalogue table.
 */
@Getter
public enum DashboardWidgetType {

  /** The missions of the coming days. */
  UPCOMING_DAYS(
      DashboardMode.MEMBER,
      DashboardColumn.WIDE,
      true,
      EnumSet.of(DashboardOption.UNIT, DashboardOption.DAYS, DashboardOption.ROWS),
      DashboardOptions.ofDaysAndRows(DEFAULT_DAYS, DEFAULT_ROWS)),

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
      DashboardOptions.ofRowsAndStatus(DEFAULT_ROWS, DashboardStatus.UNREAD),
      ordered(DashboardStatus.UNREAD, DashboardStatus.ALL)),

  /** The member's next missions: those of the own units and those the member is signed up for. */
  NEXT_MISSION(
      DashboardMode.MEMBER,
      DashboardColumn.NARROW,
      false,
      EnumSet.of(DashboardOption.ROWS),
      DashboardOptions.ofRows(DEFAULT_ROWS)),

  /** The readiness of the member's hangar. */
  HANGAR_READINESS(
      DashboardMode.MEMBER,
      DashboardColumn.NARROW,
      false,
      EnumSet.noneOf(DashboardOption.class),
      DashboardOptions.NONE),

  /** The admin links as tiles. */
  ADMIN_HUB(
      DashboardMode.ADMIN,
      DashboardColumn.WIDE,
      true,
      EnumSet.noneOf(DashboardOption.class),
      DashboardOptions.NONE),

  /** The pending deletion requests. */
  DELETION_REQUESTS(
      DashboardMode.ADMIN,
      DashboardColumn.NARROW,
      true,
      EnumSet.of(DashboardOption.ROWS),
      DashboardOptions.ofRows(DEFAULT_ROWS)),

  /** The pending Discord registrations. */
  DISCORD_REGISTRATIONS(
      DashboardMode.ADMIN,
      DashboardColumn.NARROW,
      true,
      EnumSet.of(DashboardOption.ROWS),
      DashboardOptions.ofRows(DEFAULT_ROWS)),

  /** The latest sync and import runs. */
  SYNC_STATUS(
      DashboardMode.ADMIN,
      DashboardColumn.NARROW,
      true,
      EnumSet.of(DashboardOption.RANGE),
      DashboardOptions.ofDays(DEFAULT_DAYS)),

  /** The members without accepted terms. */
  OPEN_TERMS(
      DashboardMode.ADMIN,
      DashboardColumn.NARROW,
      true,
      EnumSet.of(DashboardOption.ROWS),
      DashboardOptions.ofRows(DEFAULT_ROWS)),

  /** The orders the member is an assignee of, by priority. */
  ASSIGNED_ORDERS(
      DashboardMode.MEMBER,
      DashboardColumn.NARROW,
      false,
      EnumSet.of(DashboardOption.ROWS, DashboardOption.STATUS),
      DashboardOptions.ofRowsAndStatus(DEFAULT_ROWS, DashboardStatus.OPEN_ACTIVE),
      ordered(DashboardStatus.OPEN_ACTIVE, DashboardStatus.OPEN, DashboardStatus.IN_PROGRESS)),

  /** The minimum stock levels that are low, emptiest first. */
  LOW_STOCK(
      DashboardMode.MEMBER,
      DashboardColumn.NARROW,
      false,
      EnumSet.of(DashboardOption.UNIT, DashboardOption.ROWS),
      DashboardOptions.ofRows(DEFAULT_ROWS)),

  /** The balances of the org-unit bank accounts the member may view. */
  BANK(
      DashboardMode.MEMBER,
      DashboardColumn.NARROW,
      false,
      EnumSet.of(DashboardOption.ROWS),
      DashboardOptions.ofRows(DEFAULT_BANK_ROWS)),

  /** The orders one of the member's units processes, by priority. */
  ORDERS_TO_PROCESS(
      DashboardMode.MEMBER,
      DashboardColumn.NARROW,
      false,
      EnumSet.of(DashboardOption.UNIT, DashboardOption.ROWS, DashboardOption.STATUS),
      DashboardOptions.ofRowsAndStatus(DEFAULT_ROWS, DashboardStatus.OPEN_ACTIVE),
      ordered(DashboardStatus.OPEN_ACTIVE, DashboardStatus.OPEN, DashboardStatus.IN_PROGRESS)),

  /** The open material demand across the visible orders, the largest gap first. */
  MATERIAL_DEMAND(
      DashboardMode.MEMBER,
      DashboardColumn.NARROW,
      false,
      EnumSet.of(DashboardOption.UNIT, DashboardOption.ROWS),
      DashboardOptions.ofRows(DEFAULT_ROWS)),

  /** The refinery orders that are ready to be stored. */
  REFINERY_READY(
      DashboardMode.MEMBER,
      DashboardColumn.NARROW,
      false,
      EnumSet.of(DashboardOption.ROWS),
      DashboardOptions.ofRows(DEFAULT_ROWS)),

  /** The open Materialbörse entries: offers, wanted-listings or both. */
  MARKET(
      DashboardMode.MEMBER,
      DashboardColumn.NARROW,
      false,
      EnumSet.of(DashboardOption.ROWS, DashboardOption.STATUS),
      DashboardOptions.ofRowsAndStatus(DEFAULT_ROWS, DashboardStatus.BOTH),
      ordered(DashboardStatus.BOTH, DashboardStatus.OFFERS, DashboardStatus.REQUESTS)),

  /** The open supply needs, optionally the uncovered or partly covered ones. */
  SUPPLY_NEEDS(
      DashboardMode.MEMBER,
      DashboardColumn.NARROW,
      false,
      EnumSet.of(DashboardOption.UNIT, DashboardOption.ROWS, DashboardOption.STATUS),
      DashboardOptions.ofRowsAndStatus(DEFAULT_ROWS, DashboardStatus.ALL_OPEN),
      ordered(DashboardStatus.ALL_OPEN, DashboardStatus.UNCOVERED, DashboardStatus.PARTIAL)),

  /** The member's own bank requests: pending, decided or all. */
  BANK_REQUESTS(
      DashboardMode.MEMBER,
      DashboardColumn.NARROW,
      false,
      EnumSet.of(DashboardOption.ROWS, DashboardOption.STATUS),
      DashboardOptions.ofRowsAndStatus(DEFAULT_BANK_ROWS, DashboardStatus.OPEN),
      ordered(DashboardStatus.OPEN, DashboardStatus.DECIDED, DashboardStatus.ALL));

  /** The day counts a widget with the {@link DashboardOption#DAYS} option accepts. */
  public static final List<Integer> DAY_CHOICES = List.of(3, 7, 14);

  /** The day counts a widget with the {@link DashboardOption#RANGE} option accepts. */
  public static final List<Integer> RANGE_CHOICES = List.of(1, 7, 30);

  /** The row counts a widget with the {@link DashboardOption#ROWS} option accepts. */
  public static final List<Integer> ROW_CHOICES = List.of(3, 5, 10);

  private final DashboardMode mode;
  private final DashboardColumn defaultColumn;
  private final boolean inDefault;
  private final Set<DashboardOption> options;
  private final DashboardOptions defaults;
  private final Set<DashboardStatus> statuses;

  DashboardWidgetType(
      DashboardMode mode,
      DashboardColumn defaultColumn,
      boolean inDefault,
      Set<DashboardOption> options,
      DashboardOptions defaults) {
    this(mode, defaultColumn, inDefault, options, defaults, EnumSet.noneOf(DashboardStatus.class));
  }

  DashboardWidgetType(
      DashboardMode mode,
      DashboardColumn defaultColumn,
      boolean inDefault,
      Set<DashboardOption> options,
      DashboardOptions defaults,
      Set<DashboardStatus> statuses) {
    this.mode = mode;
    this.defaultColumn = defaultColumn;
    this.inDefault = inDefault;
    this.options = options;
    this.defaults = defaults;
    this.statuses = statuses;
  }

  /**
   * The status choices of a widget in the order its options dialog lists them.
   *
   * @param choices the statuses in display order
   * @return an immutable set that iterates in that order
   */
  private static @NotNull Set<DashboardStatus> ordered(@NotNull DashboardStatus... choices) {
    return Collections.unmodifiableSet(new LinkedHashSet<>(List.of(choices)));
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
    if (candidate.days() != null && !daysAllowed(candidate.days())) {
      return "The days option is not allowed here.";
    }
    if (candidate.rows() != null
        && (!options.contains(DashboardOption.ROWS) || !ROW_CHOICES.contains(candidate.rows()))) {
      return "The rows option is not allowed here.";
    }
    if (candidate.status() != null && !statuses.contains(candidate.status())) {
      return "The status option is not allowed here.";
    }
    return null;
  }

  private boolean daysAllowed(int days) {
    return options.contains(DashboardOption.DAYS) && DAY_CHOICES.contains(days)
        || options.contains(DashboardOption.RANGE) && RANGE_CHOICES.contains(days);
  }
}
