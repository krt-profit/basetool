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
import org.jetbrains.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Sets the {@code userId} MDC field to the authenticated caller's JWT {@code sub}, never a name
 * (REQ-OBS-001/-004).
 *
 * <p>Installed inside the security chain after {@code BearerTokenAuthenticationFilter}, not as a
 * component. It never removes the key; {@link CorrelationIdFilter} clears it at request end.
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
  @Nullable
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
