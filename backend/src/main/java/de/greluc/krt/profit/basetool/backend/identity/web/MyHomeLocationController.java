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

package de.greluc.krt.profit.basetool.backend.identity.web;

import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import de.greluc.krt.profit.basetool.backend.identity.internal.MemberHomeLocationService;
import de.greluc.krt.profit.basetool.backend.identity.internal.MyHomeLocationRequest;
import de.greluc.krt.profit.basetool.backend.identity.internal.MyHomeLocationResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The calling member's profile setting "my home location" (REQ-PATCH-030), which prefills the
 * location of the stock booking, the refinery store and a new ship. The JWT identifies the member,
 * so a caller only ever reads or changes their own.
 */
@RestController
@RequestMapping("/api/v1/users/me/home-location")
@RequiredArgsConstructor
public class MyHomeLocationController {

  private final UserService userService;
  private final MemberHomeLocationService homeLocations;

  /**
   * Returns the caller's home location with the setting's version.
   *
   * @param jwt the caller's token; never {@code null} behind the {@code @PreAuthorize}
   * @return the chosen home location, or none at version 0 when the caller made no choice
   */
  @NotNull
  @GetMapping
  @PreAuthorize("isAuthenticated()")
  public MyHomeLocationResponse getMyHomeLocation(@AuthenticationPrincipal Jwt jwt) {
    return homeLocations.homeLocationOf(userService.getUserIdFromJwt(jwt));
  }

  /**
   * Sets the caller's home location from the curated home locations; it moves nothing.
   *
   * @param jwt the caller's token; never {@code null} behind the {@code @PreAuthorize}
   * @param request the chosen home location and the version the caller last read
   * @return the stored setting with its new version
   */
  @NotNull
  @PutMapping
  @PreAuthorize("isAuthenticated()")
  public MyHomeLocationResponse updateMyHomeLocation(
      @AuthenticationPrincipal Jwt jwt,
      @NotNull @RequestBody @Valid MyHomeLocationRequest request) {
    return homeLocations.choose(
        userService.getUserIdFromJwt(jwt), request.homeLocationId(), request.version());
  }
}
