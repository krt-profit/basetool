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
import de.greluc.krt.profit.basetool.frontend.identity.model.UserDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.SquadronReferenceDto;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The org units the signed-in member belongs to, as the dashboard widgets scope their reads to them
 * (REQ-UI-034): the member's Staffeln and the units of the member's direct seats and roles.
 */
@Component
@RequiredArgsConstructor
class OwnOrgUnits {

  private final DashboardBackendClient client;

  /**
   * Reads the caller's own units.
   *
   * @return the ids of the caller's units; empty when the caller belongs to none
   */
  @NotNull
  Set<UUID> ids() {
    Set<UUID> ids = new HashSet<>();
    UserDto currentUser = client.currentUser();
    if (currentUser != null) {
      if (currentUser.squadrons() != null) {
        for (SquadronReferenceDto squadron : currentUser.squadrons()) {
          if (squadron != null && squadron.id() != null) {
            ids.add(squadron.id());
          }
        }
      }
      if (currentUser.squadron() != null && currentUser.squadron().id() != null) {
        ids.add(currentUser.squadron().id());
      }
    }
    List<UUID> direct = client.myOrgUnitIds();
    if (direct != null) {
      direct.stream().filter(Objects::nonNull).forEach(ids::add);
    }
    return ids;
  }
}
