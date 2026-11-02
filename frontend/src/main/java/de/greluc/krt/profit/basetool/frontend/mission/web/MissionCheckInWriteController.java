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

package de.greluc.krt.profit.basetool.frontend.mission.web;

import static de.greluc.krt.profit.basetool.frontend.kernel.web.BackendErrorResponses.relay;

import de.greluc.krt.profit.basetool.frontend.mission.client.MissionCheckInBackendClient;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AJAX proxy of the crew board's „Alle einchecken" / „Alle auschecken" buttons (REQ-MISSION-032);
 * the backend enforces the manage gate. The class-level {@code isAuthenticated()} gate is the floor
 * for every handler (REQ-SEC-052).
 */
@Slf4j
@RestController
@RequestMapping("/missions/{id}/participants")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class MissionCheckInWriteController {

  private final MissionCheckInBackendClient checkInClient;

  /**
   * Checks in every participant who has not checked in yet.
   *
   * @param id mission id
   * @return 204 on success, otherwise the relayed backend problem
   */
  @PostMapping(value = "/check-in-all/ajax", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Object> checkInAllAjax(@PathVariable @NotNull UUID id) {
    return relay(
        log,
        "check in all participants (ajax) for mission " + id,
        () -> {
          checkInClient.checkInAll(id);
          return ResponseEntity.noContent().build();
        });
  }

  /**
   * Checks out every checked-in participant who has not checked out yet.
   *
   * @param id mission id
   * @return 204 on success, otherwise the relayed backend problem
   */
  @PostMapping(value = "/check-out-all/ajax", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Object> checkOutAllAjax(@PathVariable @NotNull UUID id) {
    return relay(
        log,
        "check out all participants (ajax) for mission " + id,
        () -> {
          checkInClient.checkOutAll(id);
          return ResponseEntity.noContent().build();
        });
  }
}
