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
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * States which Android builds the server still serves (REQ-API-010, app issue #67).
 *
 * <p><strong>Anonymous, decided by the owner on 2026-08-24.</strong> The API vhost's stance is to
 * open no anonymous paths (plan Q8), and this is the one exception, taken with the reason written
 * down: a version gate that only answers after a successful login is silent in precisely the case
 * it exists for. When the breaking change is in the auth flow itself — a token shape, a scope, a
 * client-id — the old build cannot log in, and an authenticated policy endpoint would leave it
 * showing an authentication error instead of „Update erforderlich". The member would then be told
 * their credentials are wrong, which they are not.
 *
 * <p>It publishes nothing: three integers and the URL of a public GitHub release page. There is no
 * caller identity in the request and none in the answer, so it is also the rare {@code /api} path
 * with nothing to redact.
 *
 * <p>The values come from configuration rather than a table — see {@link AndroidClientProperties}
 * for why. Raising the floor is an env var and a restart, which is what an operator can do at the
 * moment a contract breaks.
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
   * <p>Always {@code 200}, including on a server that has never configured the policy: an unset
   * floor answers {@code 0}, which the app reads as "no floor". A failure here must never present
   * as a forced update, so there is no error branch to get that wrong.
   *
   * @return the policy in force, never {@code null}
   */
  @GetMapping
  @PreAuthorize("permitAll()")
  // REQ-SEC-052: the ONLY two operations in the document that answer without a token, and the
  // only two carrying an empty `security` list. The global requirement declared in OpenApiConfig
  // applies to every other operation; an empty list here overrides it, so a generated client does
  // not attach a bearer it may not have yet — and OpenApiAnonymousOperationsTest asserts that
  // exactly these two carry it.
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
            properties.getMinimumVersionCode(),
            properties.getLatestVersionCode(),
            properties.getReleasesUrl()));
  }
}
