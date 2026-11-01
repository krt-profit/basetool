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

import de.greluc.krt.profit.basetool.frontend.dashboard.client.DashboardBackendClient;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardColumn;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardMode;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardResponse;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardView;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardView.Item;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardWidgetResponse;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardWidgetType;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.ReauthenticationRequiredException;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationAccess;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.OrgUnitMembershipOptionDto;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The page reader of the dashboard (REQ-UI-034): reads a mode's stored dashboard once per request,
 * deletes the instances of widgets the member no longer holds the roles for, and resolves what each
 * instance shows in the active context.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DashboardReader {

  private final DashboardBackendClient client;

  /**
   * Reads and resolves a mode's dashboard.
   *
   * @param mode the mode
   * @param access the caller's reachable roles and flags
   * @param activeUnitId the org unit the context is pinned to, {@code null} for all units
   * @param units the units the caller can name, for the label of a pinned unit
   * @return the dashboard to render; never {@code null}
   */
  @NotNull
  public DashboardView read(
      @NotNull DashboardMode mode,
      @NotNull NavigationAccess access,
      @Nullable UUID activeUnitId,
      @NotNull List<OrgUnitMembershipOptionDto> units) {
    try {
      DashboardResponse stored = client.dashboard(mode);
      if (stored == null) {
        return DashboardView.notLoaded(mode);
      }
      Resolution resolution = resolve(stored, access, activeUnitId, units);
      if (resolution.gone().isEmpty() || !stored.customised()) {
        return resolution.view();
      }
      DashboardResponse pruned = client.pruneDashboard(mode, resolution.gone());
      return pruned == null
          ? resolution.view()
          : resolve(pruned, access, activeUnitId, units).view();
    } catch (ReauthenticationRequiredException e) {
      throw e;
    } catch (BackendServiceException e) {
      log.debug("Failed to read the {} dashboard", mode, e);
      return DashboardView.notLoaded(mode);
    }
  }

  /**
   * Splits a stored dashboard into the instances to render and the widget types to delete.
   *
   * @param stored the stored dashboard
   * @param access the caller's reachable roles and flags
   * @param activeUnitId the org unit the context is pinned to, {@code null} for all units
   * @param units the units the caller can name
   * @return the view and the widget types the caller may no longer use
   */
  @NotNull
  Resolution resolve(
      @NotNull DashboardResponse stored,
      @NotNull NavigationAccess access,
      @Nullable UUID activeUnitId,
      @NotNull List<OrgUnitMembershipOptionDto> units) {
    List<Item> wide = new ArrayList<>();
    List<Item> narrow = new ArrayList<>();
    List<DashboardWidgetType> gone = new ArrayList<>();
    for (DashboardWidgetResponse widget : stored.widgets()) {
      if (!widget.type().permitsRoles(access)) {
        if (!gone.contains(widget.type())) {
          gone.add(widget.type());
        }
        continue;
      }
      boolean available =
          widget.type().permits(access) && unitAvailable(widget.orgUnitId(), activeUnitId);
      Item item = new Item(widget, available, unitName(widget.orgUnitId(), units));
      (widget.column() == DashboardColumn.WIDE ? wide : narrow).add(item);
    }
    List<DashboardWidgetType> catalogue =
        DashboardWidgetType.ofMode(stored.mode()).stream()
            .filter(type -> type.permits(access))
            .toList();
    return new Resolution(
        new DashboardView(
            true, stored.version(), stored.customised(), wide, narrow, catalogue, stored.mode()),
        gone);
  }

  private static boolean unitAvailable(@Nullable UUID pinned, @Nullable UUID activeUnitId) {
    return pinned == null || activeUnitId == null || pinned.equals(activeUnitId);
  }

  @Nullable
  private static String unitName(
      @Nullable UUID pinned, @NotNull List<OrgUnitMembershipOptionDto> units) {
    if (pinned == null) {
      return null;
    }
    return units.stream()
        .filter(unit -> pinned.equals(unit.orgUnitId()))
        .map(OrgUnitMembershipOptionDto::orgUnitName)
        .findFirst()
        .orElse(null);
  }

  /**
   * The outcome of {@link #resolve}.
   *
   * @param view the dashboard to render
   * @param gone the widget types whose stored instances are deleted
   */
  record Resolution(@NotNull DashboardView view, @NotNull List<DashboardWidgetType> gone) {}
}
