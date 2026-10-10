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

package de.greluc.krt.profit.basetool.frontend.exchange.web;

import static de.greluc.krt.profit.basetool.frontend.support.BackendErrorResponses.relay;

import de.greluc.krt.profit.basetool.frontend.exchange.client.ExchangeBackendClient;
import de.greluc.krt.profit.basetool.frontend.exchange.model.ExchangeUndoRequestDto;
import jakarta.validation.Valid;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AJAX relay of the member's disconnects to {@code /api/v1/connected-apps} (REQ-XCH-008); a backend
 * failure is relayed as {@code application/problem+json}.
 */
@RestController
@RequestMapping("/connected-apps")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Slf4j
public class ConnectedAppsRelayController {

  /** The shape of a registry client id, identical to the backend's rule. */
  private static final Pattern CLIENT_ID = Pattern.compile("^[a-z0-9][a-z0-9-]{1,62}$");

  /** Talks to the backend. */
  private final ExchangeBackendClient exchangeClient;

  /**
   * Disconnects a whole client for the member.
   *
   * @param clientId the client id; anything outside the registry's shape is refused here
   * @return {@code 204}, {@code 400} for a malformed id, or the relayed backend error
   */
  @DeleteMapping(value = "/{clientId}", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> disconnectClient(@PathVariable @NotNull String clientId) {
    if (!CLIENT_ID.matcher(clientId).matches()) {
      return ResponseEntity.badRequest().build();
    }
    return relay(
        log,
        "disconnect exchange client (ajax)",
        () -> {
          exchangeClient.disconnectClient(clientId);
          return ResponseEntity.noContent().build();
        });
  }

  /**
   * Marks one of the member's new installations seen after the member acknowledged it in its row.
   *
   * @param installationId the installation
   * @return {@code 204}, or the relayed backend error
   */
  @PostMapping(
      value = "/installations/{installationId}/seen",
      headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> markSeen(@PathVariable @NotNull UUID installationId) {
    return relay(
        log,
        "mark exchange installation seen (ajax)",
        () -> {
          exchangeClient.markInstallationSeen(installationId);
          return ResponseEntity.noContent().build();
        });
  }

  /**
   * Undoes a client's writes to the member's blueprints, stock and ships since a point in time.
   *
   * @param clientId the client id; anything outside the registry's shape is refused here
   * @param request the point in time
   * @return the entries restored and skipped, {@code 400} for a malformed id, or the relayed
   *     backend error
   */
  @PostMapping(value = "/{clientId}/undo", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> undo(
      @PathVariable @NotNull String clientId,
      @Valid @RequestBody @NotNull ExchangeUndoRequestDto request) {
    if (!CLIENT_ID.matcher(clientId).matches()) {
      return ResponseEntity.badRequest().build();
    }
    return relay(
        log,
        "undo exchange client writes (ajax)",
        () -> ResponseEntity.ok(exchangeClient.undoClientWrites(clientId, request)));
  }

  /**
   * Disconnects one of the member's installations.
   *
   * @param installationId the installation
   * @return {@code 204}, or the relayed backend error
   */
  @DeleteMapping(
      value = "/installations/{installationId}",
      headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> disconnectInstallation(@PathVariable @NotNull UUID installationId) {
    return relay(
        log,
        "disconnect exchange installation (ajax)",
        () -> {
          exchangeClient.disconnectInstallation(installationId);
          return ResponseEntity.noContent().build();
        });
  }
}
