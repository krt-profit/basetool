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

import de.greluc.krt.profit.basetool.backend.config.AndroidClientProperties;
import de.greluc.krt.profit.basetool.backend.model.dto.AppVersionPolicyDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * States which Android builds the server still serves (REQ-API-010).
 *
 * <p>Anonymous by design, so a build that can no longer log in still learns it must update. It
 * exposes only configured version integers from {@link AndroidClientProperties} and a public
 * release URL.
 */
@RestController
@RequestMapping("/api/v1/app/version-policy")
@RequiredArgsConstructor
@Tag(name = "App", description = "What the Android client needs before it can be trusted to run.")
public class AppVersionPolicyController {

  private final AndroidClientProperties properties;

  /**
   * Returns the served-version floor, the newest published build and where to get it.
   *
   * <p>Always {@code 200}; an unset floor answers {@code 0}, meaning no floor.
   *
   * @return the policy in force, never {@code null}
   */
  @NotNull
  @GetMapping
  @PreAuthorize("permitAll()")
  @SecurityRequirements
  @Operation(
      summary = "Which Android builds the server still serves",
      description =
          "Returns the minimum supported versionCode, the newest published one, and the release "
              + "page. Anonymous by design: an app too old to authenticate must still be able to "
              + "learn that it is too old.")
  @ApiResponse(responseCode = "200", description = "The policy in force")
  public ResponseEntity<AppVersionPolicyDto> versionPolicy() {
    return ResponseEntity.ok(
        new AppVersionPolicyDto(
            properties.minimumVersionCode(),
            properties.latestVersionCode(),
            properties.releasesUrl()));
  }
}
