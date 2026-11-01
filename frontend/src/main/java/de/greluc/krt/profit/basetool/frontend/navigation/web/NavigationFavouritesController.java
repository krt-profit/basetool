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
import de.greluc.krt.profit.basetool.frontend.navigation.model.FavouritesResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.FavouritesUpdateRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Frontend relay of the favourites write (REQ-UI-032): the star sends the new list with the version
 * it last saw through {@code PUT /navigation/favourites} and the backend decides, a stale version
 * included.
 */
@Slf4j
@RestController
@RequestMapping("/navigation/favourites")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class NavigationFavouritesController {

  /** Sends the favourites to the backend. */
  private final NavigationBackendClient client;

  /**
   * Replaces the caller's favourites.
   *
   * @param request the new keys and the favourites version the caller last saw
   * @return {@code 200} with the stored favourites and their version, {@code 422} with code {@code
   *     VALIDATION} for an incomplete payload, or the relayed backend {@code problem+json}, a
   *     {@code 409} for a stale version included
   */
  @PutMapping(headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> replace(@RequestBody @Nullable FavouritesUpdateRequest request) {
    if (request == null || request.keys() == null || request.version() == null) {
      return validationError();
    }
    return relay(
        log,
        "replace navigation favourites (ajax)",
        () -> {
          FavouritesResponse stored = client.replaceFavourites(request);
          return stored != null
              ? ResponseEntity.ok(stored)
              : ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        });
  }

  @NotNull
  private static ResponseEntity<Object> validationError() {
    return ResponseEntity.status(422)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(Map.of("status", 422, "code", "VALIDATION"));
  }
}
