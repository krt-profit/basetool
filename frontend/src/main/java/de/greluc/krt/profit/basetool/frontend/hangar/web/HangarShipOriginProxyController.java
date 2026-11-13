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

package de.greluc.krt.profit.basetool.frontend.hangar.web;

import static de.greluc.krt.profit.basetool.frontend.kernel.web.BackendErrorResponses.relay;

import de.greluc.krt.profit.basetool.frontend.hangar.client.HangarBackendClient;
import de.greluc.krt.profit.basetool.frontend.hangar.model.ShipBulkOriginRequestDto;
import de.greluc.krt.profit.basetool.frontend.hangar.model.ShipBulkOriginResultDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Frontend proxy of the selection bar's „Als gepledged markieren" and „Als ingame gekauft
 * markieren" (REQ-HANGAR-018): forwards {@code POST /hangar/bulk-origin} to the backend's {@code
 * POST /api/v1/hangar/ships/bulk-origin} and answers the counts, which end in an in-place table
 * swap.
 */
@Slf4j
@RestController
@RequestMapping("/hangar")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class HangarShipOriginProxyController {

  /** Sends the origin change to the backend. */
  private final HangarBackendClient hangarClient;

  /**
   * Marks a selection of the caller's ships with one origin.
   *
   * @param request the selected ships and the origin
   * @return {@code 200} with the changed and skipped counts, {@code 422} with code {@code
   *     VALIDATION} for an empty selection or an origin other than {@code PLEDGE} or {@code
   *     IN_GAME}, or the relayed backend {@code problem+json}
   */
  @PostMapping(value = "/bulk-origin", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> bulkChangeShipOrigin(
      @RequestBody @Nullable ShipBulkOriginRequestDto request) {
    ShipBulkOriginRequestDto valid = BulkOriginRequests.validated(request);
    if (valid == null) {
      return BulkOriginRequests.validationError();
    }
    return relay(
        log,
        "bulk change ship origin (ajax)",
        () -> {
          ShipBulkOriginResultDto result = hangarClient.bulkChangeShipOrigin(valid);
          return ResponseEntity.ok(result != null ? result : new ShipBulkOriginResultDto(0, 0));
        });
  }
}
