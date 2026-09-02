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
 * Attributes every authenticated API request to the client software that made it (A8, REQ-OBS-018).
 *
 * <p>Today the answer is almost always "the web frontend", and that is exactly why the counter is
 * worth having before the API is exposed: once a second first-party client and an
 * internet-reachable vhost exist, "which client is this traffic" is the first question of every
 * abuse investigation, the denominator for a per-client budget, and the only signal a client kill
 * switch could act on. Adding it afterwards would mean asking it of a surface that has no history.
 *
 * <p><b>Observes, never refuses.</b> An unknown client is counted under {@link
 * MetricNames#CLIENT_ID_OTHER} and served exactly as before. That is deliberate: the ingest gateway
 * enforces a client allowlist because it fronts a single approved tool (REQ-INGEST-011), while this
 * surface serves whichever first-party clients the realm carries, and turning an unrecognised
 * {@code azp} into a 403 here would lock out a client the day it is registered in Keycloak and
 * before it is added to a properties file. The gate that matters is the audience check on the token
 * itself; this is the telemetry that makes the gate's effect visible.
 *
 * <p><b>Placement is load-bearing.</b> Registered <em>before</em> {@link ActingMemberFilter}: that
 * filter replaces the {@link org.springframework.security.core.context.SecurityContext} of an
 * on-behalf-of call with an {@code ActingMemberAuthentication}, which carries no token and
 * therefore no {@code azp}, so a gateway request observed after it would be indistinguishable from
 * a browser's. It also sits ahead of the pending-approval, terms, page-size and per-subject gates,
 * so a client that is being refused downstream is still counted — a foreign client hammering the
 * API into 429s must not be able to hide behind its own rejections, which is precisely the case
 * {@code ApiUnknownClient} exists to catch.
 */
@RequiredArgsConstructor
public class ApiClientMetricsFilter extends OncePerRequestFilter {

  /** The surface this filter attributes; nothing outside it is an API call. */
  private static final PathPattern API_SCOPE = PathPatternParser.defaultInstance.parse("/api/**");

  private final ClientAttribution clientAttribution;
  private final MeterRegistry meterRegistry;

  /**
   * Skips everything that is not an API request.
   *
   * <p>The scope is matched against the <b>decoded</b> path (REQ-SEC-029): {@code getRequestURI()}
   * is percent-encoded, so an encoded spelling must not drop out of the attribution — a caller that
   * can make its requests invisible to the counter defeats the counter.
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
    // Anonymous callers are deliberately not counted: they have no client identity to attribute,
    // and a series that lumped every guest request under one literal would only dilute the ratio
    // this metric exists to show. "Has a subject" is asked through the seam rather than through an
    // instanceof, so a future authentication type cannot silently drop out of the count (ADR-0129).
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
