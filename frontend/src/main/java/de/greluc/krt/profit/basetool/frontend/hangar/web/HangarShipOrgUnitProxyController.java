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
import de.greluc.krt.profit.basetool.frontend.hangar.model.ShipOrgUnitRequestDto;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Frontend proxy of the „Einheit ändern" row action (REQ-HANGAR-009): forwards {@code POST
 * /hangar/{id}/org-unit} to the backend's {@code PUT /api/v1/hangar/ships/{id}/org-unit} and
 * answers {@code 204} for the in-place table swap.
 */
@Slf4j
@RestController
@RequestMapping("/hangar")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class HangarShipOrgUnitProxyController {

  /** Sends the unit change to the backend. */
  private final HangarBackendClient hangarClient;

  /**
   * Changes the owning org unit of one of the caller's ships.
   *
   * @param id ship id
   * @param request the new org unit, or none, and the last-seen unit counter
   * @return {@code 204} on success, {@code 422} with code {@code VALIDATION} without a unit
   *     counter, or the relayed backend {@code problem+json}
   */
  @PostMapping(value = "/{id}/org-unit", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> changeShipOrgUnit(
      @PathVariable @NotNull UUID id, @RequestBody @Nullable ShipOrgUnitRequestDto request) {
    if (request == null || request.owningOrgUnitVersion() == null) {
      return ResponseEntity.status(422)
          .contentType(MediaType.APPLICATION_PROBLEM_JSON)
          .body(Map.of("status", 422, "code", "VALIDATION"));
    }
    return relay(
        log,
        "change ship org unit (ajax)",
        () -> {
          hangarClient.changeShipOrgUnit(id, request);
          return ResponseEntity.noContent().build();
        });
  }
}
