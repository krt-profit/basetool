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

package de.greluc.krt.profit.basetool.backend.config;

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.support.AuthenticatedSubject;
import de.greluc.krt.profit.basetool.backend.support.ClientAttribution;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.server.PathContainer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Counts every authenticated API request by the client ({@code azp}) that made it (REQ-OBS-018).
 *
 * <p>Observes only: an unknown client is counted under {@link MetricNames#CLIENT_ID_OTHER} and
 * served normally. Must run before {@link ActingMemberFilter}, which replaces the token-carrying
 * authentication, and before the refusing gates, so refused requests are counted too.
 */
@RequiredArgsConstructor
public class ApiClientMetricsFilter extends OncePerRequestFilter {

  /** The surface this filter attributes; nothing outside it is an API call. */
  private static final PathPattern API_SCOPE = PathPatternParser.defaultInstance.parse("/api/**");

  private final ClientAttribution clientAttribution;
  private final MeterRegistry meterRegistry;

  /**
   * Skips every request whose decoded path lies outside {@code /api/**} (REQ-SEC-029).
   *
   * @param request the incoming request.
   * @return {@code true} when the request is outside {@code /api/**}.
   */
  @Override
  protected boolean shouldNotFilter(@NotNull HttpServletRequest request) {
    String uri = request.getRequestURI();
    return uri == null || !API_SCOPE.matches(PathContainer.parsePath(uri));
  }

  @Override
  protected void doFilterInternal(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull FilterChain chain)
      throws ServletException, IOException {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (AuthenticatedSubject.of(authentication).isPresent()) {
      meterRegistry
          .counter(
              MetricNames.API_CLIENT_REQUESTS,
              MetricNames.TAG_CLIENT_ID,
              clientAttribution.labelOf(authentication))
          .increment();
    }
    chain.doFilter(request, response);
  }
}
