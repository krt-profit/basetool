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

import de.greluc.krt.profit.basetool.frontend.patchchange.client.PatchChangeBackendClient;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.ChooseHomeLocationRequest;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.RelocationResultDto;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * AJAX relay of the member's home-location page (REQ-PATCH-022): the one final choice, which
 * relocates the member and frees the session from the home-location gate; a backend refusal is
 * relayed as {@code application/problem+json} with the backend's localised detail.
 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Slf4j
public class HomeLocationRelayController {

  /** Relays the choice. */
  private final PatchChangeBackendClient patchChangeClient;

  /** The gate whose cached verdict the choice discards. */
  private final HomeLocationGate homeLocationGate;

  /**
   * Relays the caller's final choice; the page navigates into the tool afterwards.
   *
   * @param choice the chosen home location
   * @param request the current request, whose session caches the gate's verdict
   * @return what moved, or the relayed backend refusal with its localised detail
   */
  @PostMapping(value = HomeLocationGate.PAGE_PATH, headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> chooseHomeLocation(
      @RequestBody @NotNull ChooseHomeLocationRequest choice, @NotNull HttpServletRequest request) {
    return relay(
        log,
        "choose the caller's home location (ajax)",
        () -> {
          RelocationResultDto result = patchChangeClient.chooseMyHomeLocation(choice);
          homeLocationGate.forget(request);
          return ResponseEntity.ok(result);
        });
  }
}
