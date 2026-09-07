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

package de.greluc.krt.profit.basetool.backend.controller;

import de.greluc.krt.profit.basetool.backend.annotation.ApiDeprecation;
import de.greluc.krt.profit.basetool.backend.model.dto.PingResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness/version probe endpoints. Two URI versions exist so the API contract for clients can
 * evolve without breaking the legacy {@code /v1} consumers — the {@code /v1} path is marked
 * deprecated via {@link ApiDeprecation} and emits sunset headers; new clients should target {@code
 * /v2}.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "System", description = "System and API versioning endpoints")
public class SystemController {

  /**
   * Legacy plain-map ping. Kept for old clients; new clients should call {@link #pingV2}.
   *
   * @return {@code {status, version, message}} map
   */
  @GetMapping("/v1/system/ping")
  @PreAuthorize("isAuthenticated()")
  @Operation(
      summary = "System Ping (Legacy)",
      description = "Deprecated. Use /api/v2/system/ping instead.")
  @ApiDeprecation(sunset = "2026-12-31", replacement = "/api/v2/system/ping")
  @ApiResponse(
      responseCode = "200",
      description = "Ping response",
      content = @Content(mediaType = "application/json"))
  public Map<String, String> pingV1() {
    return Map.of("status", "UP", "version", "v1", "message", "pong");
  }

  /**
   * Current ping. Returns a typed {@link PingResponse} record including a UTC timestamp so a client
   * can detect a clock skew between itself and the server without an extra round-trip.
   *
   * @return ping response with UTC timestamp
   */
  @GetMapping("/v2/system/ping")
  @PreAuthorize("isAuthenticated()")
  @Operation(
      summary = "System Ping (Current)",
      description = "Returns system status with UTC timestamp.")
  @ApiResponse(
      responseCode = "200",
      description = "Ping response with timestamp",
      content =
          @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = PingResponse.class)))
  public PingResponse pingV2() {
    return new PingResponse("UP", "v2", "pong", Instant.now());
  }
}
