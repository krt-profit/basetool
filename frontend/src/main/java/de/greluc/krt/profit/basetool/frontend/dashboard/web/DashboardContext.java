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

import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationAccess;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.OrgUnitMembershipOptionDto;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.ui.Model;

/**
 * What the layout advices put into the model that the dashboard reads: the caller's reachable roles
 * and flags, the org unit the context is pinned to and the units the caller can name. A missing
 * attribute fails closed to no roles, no pin and no units.
 *
 * @param access the caller's reachable roles and flags
 * @param activeUnitId the org unit the context is pinned to, {@code null} for all units
 * @param units the units the caller can name
 */
record DashboardContext(
    @NotNull NavigationAccess access,
    @Nullable UUID activeUnitId,
    @NotNull List<OrgUnitMembershipOptionDto> units) {

  /**
   * Reads the context from a request's model.
   *
   * @param model the model the layout advices filled
   * @return the context; never {@code null}
   */
  @NotNull
  static DashboardContext of(@NotNull Model model) {
    NavigationAccess access =
        model.getAttribute("navigationAccess") instanceof NavigationAccess known
            ? known
            : new NavigationAccess(Set.of(), Set.of());
    UUID activeUnitId =
        model.getAttribute("activeSquadronId") instanceof UUID pinned ? pinned : null;
    List<OrgUnitMembershipOptionDto> units =
        model.getAttribute("availableOrgUnits") instanceof List<?> listed
            ? listed.stream()
                .filter(OrgUnitMembershipOptionDto.class::isInstance)
                .map(OrgUnitMembershipOptionDto.class::cast)
                .toList()
            : List.of();
    return new DashboardContext(access, activeUnitId, units);
  }
}
