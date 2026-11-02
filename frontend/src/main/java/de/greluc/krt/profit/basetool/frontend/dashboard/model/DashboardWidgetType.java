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
import de.greluc.krt.profit.basetool.frontend.kernel.security.Roles;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationAccess;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationFlag;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationGate;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

/**
 * The widget types of the catalogue (REQ-UI-034) with what only the frontend can say: the gate a
 * member has to pass, which depends on the caller's roles and flags, the endpoint segment and the
 * message key. Everything else about a type (mode, column, options, defaults, choices) is read from
 * the backend's catalogue as a {@link DashboardWidgetDescriptor}; the mirror test fails when the
 * backend names a type that has no constant here.
 */
@DtoMirror
@RequiredArgsConstructor
public enum DashboardWidgetType {

  /** The missions of the coming days; the mission list is for members and above. */
  UPCOMING_DAYS(NavigationGate.anyRole(Roles.KRT_MEMBER)),

  /** The announcement. */
  INFORMATION(NavigationGate.ALWAYS),

  /** The member's favourites. */
  FAVOURITES(NavigationGate.ALWAYS),

  /** The member's notifications. */
  NOTIFICATIONS(NavigationGate.ALWAYS),

  /** The member's next missions; the mission list is for members and above. */
  NEXT_MISSION(NavigationGate.anyRole(Roles.KRT_MEMBER)),

  /** The readiness of the member's hangar; the hangar is open to every signed-in member. */
  HANGAR_READINESS(NavigationGate.ALWAYS),

  /** The admin links as tiles. */
  ADMIN_HUB(NavigationGate.anyRole(Roles.ADMIN)),

  /** The pending deletion requests. */
  DELETION_REQUESTS(NavigationGate.anyRole(Roles.ADMIN)),

  /** The pending Discord registrations. */
  DISCORD_REGISTRATIONS(NavigationGate.anyRole(Roles.ADMIN)),

  /** The latest sync and import runs. */
  SYNC_STATUS(NavigationGate.anyRole(Roles.ADMIN)),

  /** The members without accepted terms. */
  OPEN_TERMS(NavigationGate.anyRole(Roles.ADMIN)),

  /**
   * The orders the member is an assignee of; an assignee entry grants the read whatever the
   * member's roles are (REQ-ORDERS-047).
   */
  ASSIGNED_ORDERS(NavigationGate.ALWAYS),

  /** The low minimum stock levels; the Lager is for members and above. */
  LOW_STOCK(NavigationGate.anyRole(Roles.KRT_MEMBER)),

  /** The viewable org-unit bank accounts; the org-unit bank page is for members and above. */
  BANK(NavigationGate.anyRole(Roles.KRT_MEMBER)),

  /** The orders one of the member's units processes; the member has to enter the order area. */
  ORDERS_TO_PROCESS(NavigationGate.requires(NavigationFlag.JOB_ORDERS)),

  /** The open material demand; the overview is part of the order area. */
  MATERIAL_DEMAND(NavigationGate.requires(NavigationFlag.JOB_ORDERS)),

  /** The refinery orders that are ready to be stored; the refinery is open to every member. */
  REFINERY_READY(NavigationGate.ALWAYS),

  /** The open Materialbörse entries; the board is for members and above. */
  MARKET(NavigationGate.anyRole(Roles.KRT_MEMBER)),

  /** The open supply needs; the supply surface is for members while it is switched on. */
  SUPPLY_NEEDS(NavigationGate.anyRole(Roles.KRT_MEMBER).andRequires(NavigationFlag.SUPPLY)),

  /** The member's own bank requests; the org-unit bank page is for members and above. */
  BANK_REQUESTS(NavigationGate.anyRole(Roles.KRT_MEMBER));

  private final NavigationGate gate;

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
   * Whether the member still holds the roles and rights the widget needs in some context, as the
   * navigation counts a lost right ({@link NavigationGate#permitsUserWide}).
   *
   * @param access the caller's reachable roles and flags
   * @return {@code true} iff the widget's instances may stay stored
   */
  public boolean permitsUserWide(@NotNull NavigationAccess access) {
    return gate.permitsUserWide(access);
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
}
