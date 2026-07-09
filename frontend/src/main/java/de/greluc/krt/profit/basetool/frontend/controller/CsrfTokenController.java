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
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authenticated endpoint that hands the current CSRF token to the {@code krtCsrf} client helper
 * (see {@code krt-fetch.js}) so a write that failed with a bare {@code 403} can self-heal.
 *
 * <p>A {@code 403} on an AJAX write means Spring Security's {@code CsrfFilter} rejected the token
 * the page started with — the usual causes are a stale browser tab, the session-id rotation that
 * {@code sessionFixation(changeSessionId)} performs on re-login, or eviction by {@code
 * maximumSessions(10)} (all configured in {@link
 * de.greluc.krt.profit.basetool.frontend.config.SecurityConfig}). {@code krtFetch.write} reacts by
 * calling {@code GET /csrf} once, updating the {@code _csrf} meta tags, and retrying the request,
 * so the user never sees a spurious "action failed".
 *
 * <p>The endpoint is deliberately left under the {@code anyRequest().authenticated()} catch-all: an
 * anonymous caller is redirected to the OIDC entry point rather than handed a token, and {@code
 * krtCsrf.refresh()} treats that non-{@code 2xx} as "could not refresh" and surfaces the original
 * error. Decision 1 of epic #571 keeps the session/{@code
 * XorCsrfTokenRequestAttributeHandler}-based repository unchanged; {@link CsrfToken#getToken()}
 * returns the same BREACH-masked value the {@code _csrf} meta tag is rendered from, so the token
 * this returns is valid for the very next submit.
 */
@RestController
public class CsrfTokenController {

  /**
   * Returns the active CSRF header name and token for the authenticated session.
   *
   * @param token the request-scoped {@link CsrfToken}, resolved by Spring Security's argument
   *     resolver from the {@code CsrfFilter}-populated request attribute; never {@code null} on
   *     this CSRF-protected, authenticated route
   * @return a JSON object {@code {"headerName": "...", "token": "..."}} the client writes back into
   *     the {@code _csrf_header} / {@code _csrf} meta tags before retrying a 403'd write
   */
  @GetMapping("/csrf")
  public Map<String, String> csrf(CsrfToken token) {
    return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
  }
}
