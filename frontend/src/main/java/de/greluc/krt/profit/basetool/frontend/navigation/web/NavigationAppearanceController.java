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

package de.greluc.krt.profit.basetool.frontend.navigation.web;

import static de.greluc.krt.profit.basetool.frontend.kernel.web.BackendErrorResponses.relay;

import de.greluc.krt.profit.basetool.frontend.navigation.client.NavigationBackendClient;
import de.greluc.krt.profit.basetool.frontend.navigation.model.AppearanceResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.AppearanceUpdateRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Frontend relay of the appearance section (REQ-UI-026, REQ-UI-032): the switch sends the chosen
 * desktop mode with the version it last saw through {@code PUT /navigation/appearance}, and the
 * backend decides, a stale version included. A {@code GET} re-reads the stored section after a
 * conflict.
 */
@Slf4j
@RestController
@RequestMapping("/navigation/appearance")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class NavigationAppearanceController {

  /** Sends the appearance to the backend. */
  private final NavigationBackendClient client;

  /**
   * Replaces the caller's appearance.
   *
   * @param request the chosen desktop mode and the appearance version the caller last saw
   * @return {@code 200} with the stored appearance and its version, {@code 422} with code {@code
   *     VALIDATION} for an incomplete payload, or the relayed backend {@code problem+json}, a
   *     {@code 409} for a stale version included
   */
  @PutMapping(headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> replace(@RequestBody @Nullable AppearanceUpdateRequest request) {
    if (request == null || request.desktopMode() == null || request.version() == null) {
      return validationError();
    }
    return relay(
        log, "replace navigation appearance (ajax)", () -> ok(client.replaceAppearance(request)));
  }

  /**
   * Reads the stored appearance.
   *
   * @return {@code 200} with the stored appearance and its version, or the relayed backend problem
   */
  @GetMapping(headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> read() {
    return relay(
        log,
        "read navigation appearance (ajax)",
        () -> {
          var stored = client.preferences();
          return ok(stored == null ? null : stored.appearance());
        });
  }

  @NotNull
  private static ResponseEntity<Object> ok(@Nullable AppearanceResponse stored) {
    return stored != null
        ? ResponseEntity.ok(stored)
        : ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
  }

  @NotNull
  private static ResponseEntity<Object> validationError() {
    return ResponseEntity.status(422)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(Map.of("status", 422, "code", "VALIDATION"));
  }
}
