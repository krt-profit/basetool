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

package de.greluc.krt.profit.basetool.ingest.filter;

import de.greluc.krt.profit.basetool.ingest.config.LoggingProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refines the {@code userId} MDC field from {@link CorrelationIdFilter#ANONYMOUS} to the
 * authenticated caller's JWT {@code sub} so every gateway log line is attributable to one subject —
 * the ingest half of the backend/frontend MDC contract (REQ-OBS-001/-002). Restricted to {@code
 * sub} by design: a Keycloak {@code preferred_username} / callsign is a name and must never reach
 * the appenders, whereas the {@code sub} UUID is not PII (REQ-OBS-004).
 *
 * <p>Installed <em>inside</em> the Spring Security chain, immediately after {@code
 * BearerTokenAuthenticationFilter} (see {@code SecurityConfig}), because the shared servlet filters
 * all run before authentication and would only ever see an empty {@link SecurityContextHolder}. It
 * is deliberately <b>not</b> a {@code @Component}: Boot would then also auto-register it as a plain
 * servlet filter, where it would run too early and be useless.
 *
 * <p>The filter never removes the key. {@link CorrelationIdFilter} wraps the whole request from
 * outside the security chain and owns the MDC lifecycle, so leaving the value in place is what lets
 * the {@link RequestLoggingFilter} access-log line — emitted after the security chain has already
 * unwound and cleared its {@code SecurityContext} — still carry the subject.
 */
@RequiredArgsConstructor
public class UserIdMdcFilter extends OncePerRequestFilter {

  private final LoggingProperties loggingProperties;

  @Override
  protected void doFilterInternal(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull FilterChain filterChain)
      throws ServletException, IOException {
    String sub = authenticatedSubject();
    if (sub != null) {
      MDC.put(loggingProperties.userIdMdcKey(), sub);
    }
    filterChain.doFilter(request, response);
  }

  /**
   * Reads the JWT {@code sub} of the current authentication, if there is one.
   *
   * @return the non-blank {@code sub} claim, or {@code null} when the request is unauthenticated,
   *     authenticated by something other than a bearer token, or carries a token without a subject
   *     — in which case the {@link CorrelationIdFilter#ANONYMOUS} seed stands
   */
  private static String authenticatedSubject() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
      Jwt jwt = jwtAuthentication.getToken();
      String sub = jwt.getSubject();
      if (sub != null && !sub.isBlank()) {
        return sub;
      }
    }
    return null;
  }
}
