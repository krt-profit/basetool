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

package de.greluc.krt.profit.basetool.frontend.dashboard.web;

import static de.greluc.krt.profit.basetool.frontend.kernel.web.BackendErrorResponses.relay;

import de.greluc.krt.profit.basetool.frontend.dashboard.client.DashboardBackendClient;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardLayoutRequest;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardMode;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardOptionsRequest;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Relays the dashboard writes of the home page to the backend (REQ-UI-034): the layout of a mode,
 * the options of one instance and the restore of the default. A stale version surfaces as the
 * backend's {@code 409}.
 */
@Slf4j
@RestController
@RequestMapping("/dashboard/{mode}")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class DashboardLayoutController {

  /** Sends the writes to the backend. */
  private final DashboardBackendClient client;

  /**
   * Replaces the caller's layout of a mode.
   *
   * @param mode the mode's path segment
   * @param request the instances in display order and the layout version the caller last saw
   * @return {@code 200} with the stored dashboard, {@code 404} for an unknown mode, {@code 422}
   *     with code {@code VALIDATION} for an incomplete payload, or the relayed backend {@code
   *     problem+json}
   */
  @PutMapping(value = "/layout", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> replaceLayout(
      @PathVariable String mode, @RequestBody @Nullable DashboardLayoutRequest request) {
    if (request == null || request.widgets() == null || request.version() == null) {
      return validationError();
    }
    return withMode(
        mode,
        resolved ->
            relay(
                log,
                "replace dashboard layout (ajax)",
                () -> okOrBadGateway(client.replaceLayout(resolved, request))));
  }

  /**
   * Replaces the options of one instance.
   *
   * @param mode the mode's path segment
   * @param widgetId the instance
   * @param request the new options and the option version the caller last saw
   * @return {@code 200} with the stored instance, {@code 404} for an unknown mode, {@code 422} with
   *     code {@code VALIDATION} for an incomplete payload, or the relayed backend {@code
   *     problem+json}
   */
  @PutMapping(value = "/widgets/{widgetId}/options", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> replaceOptions(
      @PathVariable String mode,
      @PathVariable UUID widgetId,
      @RequestBody @Nullable DashboardOptionsRequest request) {
    if (request == null || request.version() == null) {
      return validationError();
    }
    return withMode(
        mode,
        resolved ->
            relay(
                log,
                "replace dashboard options (ajax)",
                () -> okOrBadGateway(client.replaceOptions(resolved, widgetId, request))));
  }

  /**
   * Restores a mode's default dashboard.
   *
   * @param mode the mode's path segment
   * @param version the layout version the caller last saw
   * @return {@code 200} with the default dashboard, {@code 404} for an unknown mode, or the relayed
   *     backend {@code problem+json}
   */
  @DeleteMapping(headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> reset(@PathVariable String mode, @RequestParam long version) {
    return withMode(
        mode,
        resolved ->
            relay(
                log,
                "reset dashboard (ajax)",
                () -> okOrBadGateway(client.resetDashboard(resolved, version))));
  }

  private static ResponseEntity<Object> withMode(
      String slug, Function<DashboardMode, ResponseEntity<Object>> action) {
    Optional<DashboardMode> mode = DashboardMode.fromSlug(slug);
    return mode.isPresent() ? action.apply(mode.get()) : ResponseEntity.notFound().build();
  }

  @NotNull
  private static ResponseEntity<Object> okOrBadGateway(@Nullable Object stored) {
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
