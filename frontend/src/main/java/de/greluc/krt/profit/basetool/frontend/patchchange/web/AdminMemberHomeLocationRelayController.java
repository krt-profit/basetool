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

package de.greluc.krt.profit.basetool.frontend.patchchange.web;

import static de.greluc.krt.profit.basetool.frontend.kernel.web.BackendErrorResponses.relay;

import de.greluc.krt.profit.basetool.frontend.kernel.security.Roles;
import de.greluc.krt.profit.basetool.frontend.patchchange.client.PatchChangeBackendClient;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.ChooseHomeLocationRequest;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AJAX relay of the admin's member home-location tab (REQ-PATCH-022): the choice on a member's
 * behalf, which relocates the member; a backend refusal is relayed as {@code
 * application/problem+json} with the backend's localised detail.
 */
@RestController
@RequestMapping(AdminMemberHomeLocationPageController.PAGE)
@RequiredArgsConstructor
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
@Slf4j
public class AdminMemberHomeLocationRelayController {

  /** Relays the choice. */
  private final PatchChangeBackendClient patchChangeClient;

  /**
   * Chooses the member's new home location and relocates the member, once per phase.
   *
   * @param userId the member
   * @param choice the chosen home location
   * @return what moved, or the relayed backend refusal ({@code 409} when the phase is not open, the
   *     member already chose or is not asked, {@code 400} when the location is not on offer)
   */
  @PostMapping(headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> chooseHomeLocation(
      @PathVariable @NotNull UUID userId, @RequestBody @NotNull ChooseHomeLocationRequest choice) {
    return relay(
        log,
        "choose the home location of member " + userId + " (ajax)",
        () -> ResponseEntity.ok(patchChangeClient.chooseMemberHomeLocation(userId, choice)));
  }
}
