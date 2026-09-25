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

import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hands the current CSRF token to the {@code krtCsrf} client helper, so a write rejected with
 * {@code 403} for a stale token can refresh the {@code _csrf} meta tags and retry once.
 *
 * <p>Authenticated only; an anonymous caller is redirected to the OIDC entry point and never gets a
 * token (REQ-SEC-010).
 */
@RestController
@PreAuthorize("isAuthenticated()")
public class CsrfTokenController {

  /**
   * Returns the active CSRF header name and token for the authenticated session.
   *
   * @param token the request-scoped {@link CsrfToken}; never {@code null} on this route
   * @return {@code {"headerName": "...", "token": "..."}} for the {@code _csrf_header} / {@code
   *     _csrf} meta tags
   */
  @NotNull
  @Unmodifiable
  @GetMapping("/csrf")
  public Map<String, String> csrf(@NotNull CsrfToken token) {
    return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
  }
}
