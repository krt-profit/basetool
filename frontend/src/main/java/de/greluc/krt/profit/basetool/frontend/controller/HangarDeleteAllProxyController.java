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

package de.greluc.krt.profit.basetool.frontend.controller;

import static de.greluc.krt.profit.basetool.frontend.support.BackendErrorResponses.withBackendStatus;

import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Frontend proxy for the "delete all ships" endpoint.
 *
 * <p>Forwards {@code DELETE /hangar/ships/all} to the backend {@code DELETE /api/v1/hangar/ships}
 * through {@link BackendApiClient#execute} (authenticated client, shared error mapping), and
 * returns 204 No Content on success.
 */
@RestController
@RequestMapping("/hangar/ships")
@RequiredArgsConstructor
public class HangarDeleteAllProxyController {

  /** Backend path that clears the caller's hangar. */
  private static final String SHIPS_URI = "/api/v1/hangar/ships";

  private final BackendApiClient backendApiClient;

  /**
   * Proxies a "delete all ships" request to the backend.
   *
   * @return 204 No Content on success
   */
  @DeleteMapping("/all")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<Void> deleteAllShips() {
    withBackendStatus(
        () ->
            backendApiClient.execute(
                HttpMethod.DELETE,
                SHIPS_URI,
                webClient -> webClient.delete().uri(SHIPS_URI),
                WebClient.ResponseSpec::toBodilessEntity));
    return ResponseEntity.noContent().build();
  }
}
