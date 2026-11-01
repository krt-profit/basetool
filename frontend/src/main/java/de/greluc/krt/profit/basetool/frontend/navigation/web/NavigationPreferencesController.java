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
import de.greluc.krt.profit.basetool.frontend.navigation.model.FavouritesAndHiddenUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.navigation.model.FavouritesUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.navigation.model.HiddenUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationMode;
import de.greluc.krt.profit.basetool.frontend.navigation.model.OrderResetRequest;
import de.greluc.krt.profit.basetool.frontend.navigation.model.OrderScopeUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.navigation.model.TabBarUpdateRequest;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Frontend relay of the preference writes (REQ-UI-032): the star, the hide control and the resets
 * send the new list with the version they last saw and the backend decides, a stale version
 * included.
 */
@Slf4j
@RestController
@RequestMapping("/navigation")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class NavigationPreferencesController {

  private static final String AJAX = "X-Requested-With=XMLHttpRequest";

  /** The key format a mode or scope segment must have before it joins a backend path. */
  private static final Pattern SEGMENT = Pattern.compile("^[a-z][a-z0-9]*([.-][a-z0-9]+)*$");

  /** Sends the preferences to the backend. */
  private final NavigationBackendClient client;

  /**
   * Replaces the caller's favourites.
   *
   * @param request the new keys and the favourites version the caller last saw
   * @return {@code 200} with the stored favourites and their version, {@code 422} with code {@code
   *     VALIDATION} for an incomplete payload, or the relayed backend {@code problem+json}, a
   *     {@code 409} for a stale version included
   */
  @PutMapping(value = "/favourites", headers = AJAX)
  public ResponseEntity<Object> replaceFavourites(
      @RequestBody @Nullable FavouritesUpdateRequest request) {
    if (!complete(request)) {
      return validationError();
    }
    return relayed("replace navigation favourites (ajax)", () -> client.replaceFavourites(request));
  }

  /**
   * Replaces the caller's hidden pages.
   *
   * @param request the new keys and the hidden version the caller last saw
   * @return {@code 200} with the stored hidden pages and their version, {@code 422} with code
   *     {@code VALIDATION} for an incomplete payload, or the relayed backend {@code problem+json}
   */
  @PutMapping(value = "/hidden", headers = AJAX)
  public ResponseEntity<Object> replaceHidden(@RequestBody @Nullable HiddenUpdateRequest request) {
    if (request == null || request.keys() == null || request.version() == null) {
      return validationError();
    }
    return relayed("replace navigation hidden pages (ajax)", () -> client.replaceHidden(request));
  }

  /**
   * Replaces the favourites and the hidden pages together, for a page that moves from one to the
   * other.
   *
   * @param request both sections' new keys and the versions the caller last saw
   * @return {@code 200} with both stored sections, {@code 422} with code {@code VALIDATION} for an
   *     incomplete payload, or the relayed backend {@code problem+json}
   */
  @PutMapping(value = "/favourites-and-hidden", headers = AJAX)
  public ResponseEntity<Object> replaceFavouritesAndHidden(
      @RequestBody @Nullable FavouritesAndHiddenUpdateRequest request) {
    if (request == null
        || !complete(request.favourites())
        || request.hidden() == null
        || request.hidden().keys() == null
        || request.hidden().version() == null) {
      return validationError();
    }
    return relayed(
        "replace navigation favourites and hidden pages (ajax)",
        () -> client.replaceFavouritesAndHidden(request));
  }

  /**
   * Replaces the stored order of one scope of a mode: the groups of the mode or the links of one
   * group.
   *
   * @param mode {@code member} or {@code admin}
   * @param scope {@code groups} or a group key
   * @param request the scope's keys in their new order and the order version the caller last saw
   * @return {@code 200} with the mode's order and its version, {@code 422} with code {@code
   *     VALIDATION} for an incomplete payload or a malformed path, or the relayed backend {@code
   *     problem+json}, a {@code 409} for a stale version included
   */
  @PutMapping(value = "/order/{mode}/{scope}", headers = AJAX)
  public ResponseEntity<Object> replaceOrderScope(
      @PathVariable String mode,
      @PathVariable String scope,
      @RequestBody @Nullable OrderScopeUpdateRequest request) {
    if (!pathSegment(mode)
        || !pathSegment(scope)
        || request == null
        || request.keys() == null
        || request.version() == null) {
      return validationError();
    }
    return relayed(
        "replace navigation order (ajax)", () -> client.replaceOrderScope(mode, scope, request));
  }

  /**
   * Clears the stored order of a mode, back to the default order.
   *
   * @param mode {@code member} or {@code admin}
   * @param request the order version the caller last saw
   * @return {@code 200} with the empty order and its version, {@code 422} with code {@code
   *     VALIDATION} for an incomplete payload or a malformed path, or the relayed backend {@code
   *     problem+json}
   */
  @PostMapping(value = "/order/{mode}/reset", headers = AJAX)
  public ResponseEntity<Object> resetOrder(
      @PathVariable String mode, @RequestBody @Nullable OrderResetRequest request) {
    if (!pathSegment(mode) || request == null || request.version() == null) {
      return validationError();
    }
    return relayed("reset navigation order (ajax)", () -> client.resetOrder(mode, request));
  }

  private static boolean pathSegment(@NotNull String segment) {
    return SEGMENT.matcher(segment).matches();
  }

  /**
   * Replaces the caller's phone tab bar of one mode.
   *
   * @param mode the path segment of the mode, {@code member} or {@code admin}
   * @param request the three slots and the mode's tab-bar version the caller last saw
   * @return {@code 200} with the stored bar and its version, {@code 404} for an unknown mode,
   *     {@code 422} with code {@code VALIDATION} for an incomplete payload, or the relayed backend
   *     {@code problem+json}, a {@code 409} for a stale version included
   */
  @PutMapping(value = "/tab-bar/{mode}", headers = AJAX)
  public ResponseEntity<Object> replaceTabBar(
      @PathVariable String mode, @RequestBody @Nullable TabBarUpdateRequest request) {
    NavigationMode target = NavigationMode.fromSegment(mode).orElse(null);
    if (target == null) {
      return ResponseEntity.notFound().build();
    }
    if (request == null || request.slots() == null || request.version() == null) {
      return validationError();
    }
    return relayed(
        "replace the navigation tab bar (ajax)", () -> client.replaceTabBar(target, request));
  }

  private static boolean complete(@Nullable FavouritesUpdateRequest request) {
    return request != null && request.keys() != null && request.version() != null;
  }

  @NotNull
  private ResponseEntity<Object> relayed(
      @NotNull String action, @NotNull Supplier<? extends Record> write) {
    return relay(
        log,
        action,
        () -> {
          Record stored = write.get();
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
