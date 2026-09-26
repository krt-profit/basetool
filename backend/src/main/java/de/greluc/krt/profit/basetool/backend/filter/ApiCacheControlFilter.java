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
import org.jetbrains.annotations.NotNull;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Adds Cache-Control headers to API GET responses: {@code no-cache, must-revalidate} for ordinary
 * data and {@code private, no-store} for the families listed in {@link NoStoreApiScopes}
 * (REQ-SEC-031).
 *
 * <p>The {@code /api} scope is a {@link PathPattern} matched against the decoded path, so a
 * percent-encoded spelling cannot bypass it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ApiCacheControlFilter extends OncePerRequestFilter {

  /** The API surface whose GET responses get revalidation headers, parsed once. */
  private static final PathPattern API_SCOPE = PathPatternParser.defaultInstance.parse("/api/**");

  /**
   * Header value for a {@link NoStoreApiScopes} family: no intermediary, disk cache or proxy may
   * keep the body.
   */
  private static final String NO_STORE = "private, no-store";

  /**
   * Header value for everything else under {@code /api}: storable, but never reused unvalidated.
   */
  private static final String REVALIDATE = "no-cache, must-revalidate";

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
    if (!HttpMethod.GET.matches(request.getMethod())) {
      return true;
    }
    String uri = request.getRequestURI();
    return uri == null || !API_SCOPE.matches(PathContainer.parsePath(uri));
  }

  /**
   * Chooses the Cache-Control directive for a request path, matched against the decoded path
   * (REQ-SEC-029).
   *
   * @param uri the raw request URI; never {@code null} here
   * @return {@link #NO_STORE} for a sensitive family, {@link #REVALIDATE} otherwise
   */
  @NotNull
  private static String cacheControlFor(String uri) {
    return NoStoreApiScopes.matches(PathContainer.parsePath(uri)) ? NO_STORE : REVALIDATE;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    response.setHeader("Cache-Control", cacheControlFor(request.getRequestURI()));
    response.addHeader("Vary", "Accept");
    response.addHeader("Vary", "Accept-Encoding");
    filterChain.doFilter(request, response);
  }
}
