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

package de.greluc.krt.profit.basetool.backend.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Adds conservative Cache-Control headers for idempotent API GET responses to enable client-side
 * revalidation via ETag while avoiding stale caches for dynamic data.
 *
 * <p>The {@code /api} scope is a parsed {@link PathPattern} matched against the decoded path rather
 * than a raw {@code getRequestURI().startsWith("/api/")} test: {@code getRequestURI()} is the raw
 * percent-encoded URI while Spring MVC routes on the decoded path, so an encoded spelling such as
 * {@code /%61pi/v1/users} reached the API handler with no revalidation headers at all — the one
 * response class that must never be served stale from an intermediary.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ApiCacheControlFilter extends OncePerRequestFilter {

  /** The API surface whose GET responses get revalidation headers, parsed once. */
  private static final PathPattern API_SCOPE = PathPatternParser.defaultInstance.parse("/api/**");

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
    if (!HttpMethod.GET.matches(request.getMethod())) {
      return true;
    }
    String uri = request.getRequestURI();
    return uri == null || !API_SCOPE.matches(PathContainer.parsePath(uri));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    // Set revalidation headers for dynamic GET endpoints
    response.setHeader("Cache-Control", "no-cache, must-revalidate");
    response.addHeader("Vary", "Accept-Encoding");
    filterChain.doFilter(request, response);
  }
}
