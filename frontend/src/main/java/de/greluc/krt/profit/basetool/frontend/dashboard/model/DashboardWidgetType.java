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

import de.greluc.krt.profit.basetool.frontend.kernel.security.Roles;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationAccess;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationGate;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

/**
 * The widgets of the catalogue (REQ-UI-034): the path segment of the widget's own endpoint, its
 * label, its mode, the gate a member has to pass to read its data and the options it offers. A new
 * widget or option is a change to the spec's catalogue table and to the backend's matching enum.
 */
@Getter
public enum DashboardWidgetType {

  /** The missions of the coming days; the mission list is for members and above. */
  UPCOMING_DAYS(
      DashboardMode.MEMBER,
      DashboardColumn.WIDE,
      NavigationGate.anyRole(Roles.KRT_MEMBER),
      EnumSet.of(DashboardOption.UNIT, DashboardOption.DAYS, DashboardOption.ROWS)),

  /** The announcement. */
  INFORMATION(
      DashboardMode.MEMBER,
      DashboardColumn.WIDE,
      NavigationGate.ALWAYS,
      EnumSet.noneOf(DashboardOption.class)),

  /** The member's favourites. */
  FAVOURITES(
      DashboardMode.MEMBER,
      DashboardColumn.NARROW,
      NavigationGate.ALWAYS,
      EnumSet.noneOf(DashboardOption.class)),

  /** The member's notifications. */
  NOTIFICATIONS(
      DashboardMode.MEMBER,
      DashboardColumn.NARROW,
      NavigationGate.ALWAYS,
      EnumSet.of(DashboardOption.ROWS, DashboardOption.STATUS)),

  /** The member's next missions; the mission list is for members and above. */
  NEXT_MISSION(
      DashboardMode.MEMBER,
      DashboardColumn.NARROW,
      NavigationGate.anyRole(Roles.KRT_MEMBER),
      EnumSet.of(DashboardOption.ROWS)),

  /** The readiness of the member's hangar; the hangar is open to every signed-in member. */
  HANGAR_READINESS(
      DashboardMode.MEMBER,
      DashboardColumn.NARROW,
      NavigationGate.ALWAYS,
      EnumSet.noneOf(DashboardOption.class));

  /** The day counts the days option offers. */
  public static final List<Integer> DAY_CHOICES = List.of(3, 7, 14);

  /** The row counts the rows option offers. */
  public static final List<Integer> ROW_CHOICES = List.of(3, 5, 10);

  /** The status filters the status option offers. */
  public static final List<DashboardStatus> STATUS_CHOICES =
      List.of(DashboardStatus.UNREAD, DashboardStatus.ALL);

  private final DashboardMode mode;
  private final DashboardColumn defaultColumn;
  private final NavigationGate gate;
  private final Set<DashboardOption> options;

  DashboardWidgetType(
      DashboardMode mode,
      DashboardColumn defaultColumn,
      NavigationGate gate,
      Set<DashboardOption> options) {
    this.mode = mode;
    this.defaultColumn = defaultColumn;
    this.gate = gate;
    this.options = options;
  }

  /**
   * The widgets of a mode in the order of the default dashboard, wide column first.
   *
   * @param mode the mode
   * @return the widgets
   */
  public static @NotNull List<DashboardWidgetType> ofMode(@NotNull DashboardMode mode) {
    return Stream.of(values())
        .filter(type -> type.mode == mode)
        .sorted(Comparator.comparing(DashboardWidgetType::getDefaultColumn))
        .toList();
  }

  /**
   * Finds a widget by the segment of its endpoint.
   *
   * @param slug the segment, e.g. {@code upcoming-days}
   * @return the widget, empty for an unknown segment
   */
  public static @NotNull Optional<DashboardWidgetType> bySlug(@NotNull String slug) {
    return Stream.of(values()).filter(type -> type.slug().equals(slug)).findFirst();
  }

  /**
   * The widget's endpoint segment.
   *
   * @return the lower-case name with dashes
   */
  public @NotNull String slug() {
    return name().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  /**
   * The message key of the widget's title.
   *
   * @return the bundle key
   */
  public @NotNull String labelKey() {
    return "dashboard.widget." + slug();
  }

  /**
   * Whether the member still holds the roles the widget needs, whatever the context says.
   *
   * @param access the caller's reachable roles and flags
   * @return {@code true} iff the widget's instances may stay stored
   */
  public boolean permitsRoles(@NotNull NavigationAccess access) {
    return gate.permitsRoles(access);
  }

  /**
   * Whether the member may add the widget now.
   *
   * @param access the caller's reachable roles and flags
   * @return {@code true} iff the roles and flags of the gate hold
   */
  public boolean permits(@NotNull NavigationAccess access) {
    return gate.permits(access);
  }

  /**
   * The options as a space-separated attribute value for the script that edits them.
   *
   * @return the lower-case option names in declaration order
   */
  public @NotNull String optionsAttribute() {
    return options.stream()
        .map(option -> option.name().toLowerCase(Locale.ROOT))
        .collect(Collectors.joining(" "));
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
}
