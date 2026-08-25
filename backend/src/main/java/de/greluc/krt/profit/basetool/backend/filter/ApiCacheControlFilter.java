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
import java.util.List;
import java.util.stream.Stream;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Adds Cache-Control headers to API GET responses: revalidation for ordinary data, and outright
 * {@code no-store} for the families whose bodies must never be written down anywhere (REQ-SEC-031).
 *
 * <p><b>Why two levels.</b> {@code no-cache, must-revalidate} permits an intermediary to
 * <em>store</em> the body as long as it revalidates before reuse. That is the right trade for
 * master data and mission lists, and the wrong one for a bank ledger, a member's personal data or
 * someone's notifications: those must not sit in any store at all. While the backend was reachable
 * only from the frontend over an internal network there was no intermediary to worry about; a
 * public API vhost makes proxies, corporate middleboxes and browser disk caches plausible, and the
 * header is the only thing that tells them no.
 *
 * <p>{@code no-store} makes the ETag on these paths inert rather than contradictory — a client that
 * honours it keeps no copy, so it never sends {@code If-None-Match} and never gets a 304. Nothing
 * relies on conditional requests for these families.
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

  /**
   * The families whose GET bodies must not be stored by anyone.
   *
   * <p>Deliberately a short, explicit list rather than a configuration key: which data is sensitive
   * is a property of the domain, not of a deployment, and a header this load-bearing should not be
   * switchable by an env var nobody reviews. Extending it is a one-line change.
   *
   * <ul>
   *   <li>{@code bank} and {@code org-units/bank} — account balances, bookings and the ledger. Both
   *       spellings, because they are <em>different</em> surfaces: {@code /api/v1/bank/**} is the
   *       bank-employee one, while the member-facing account a client actually reads lives under
   *       {@code /api/v1/org-units/bank/**} and its transaction rows carry a {@code holderHandle};
   *   <li>{@code users} and {@code me} — member records, the only PII the API serves;
   *   <li>{@code notifications} — one member's personal feed, including the SSE stream;
   *   <li>{@code finance-entries} (both the standalone write family and the per-mission read) and
   *       {@code operations} — the mission/operation payout ledgers and their rollups;
   *   <li>{@code personal-inventory}, {@code personal-blueprints}, {@code inventory}, {@code
   *       hangar} and {@code refinery-orders} — a member's own holdings and the org stock/fleet
   *       they name members in;
   *   <li>{@code promotion} — a member's own evaluation and eligibility record.
   * </ul>
   *
   * <p>The Materialbörse ({@code material-exchange} / {@code material-requests}) is deliberately
   * <em>not</em> here: it is an org-wide shared board, and the handles it carries are the same
   * public callsign tuple the public mission roster already serves, so it belongs in the revalidate
   * bucket with the other shared listings.
   *
   * <p><b>This list is load-bearing, not advisory</b> (REQ-SEC-031). Because this filter runs at
   * {@code HIGHEST_PRECEDENCE + 20} — ahead of the Spring Security chain — it sets {@code
   * Cache-Control} before {@code CacheControlHeadersWriter} would, and that writer only acts when
   * the header is unset. So a sensitive family missing from this list does not merely fail to opt
   * in: it is actively <em>downgraded</em> from the framework's default {@code no-store} to the
   * storable {@code must-revalidate}. Adding a sensitive GET family means adding it here.
   */
  private static final List<PathPattern> NO_STORE_SCOPES =
      Stream.of(
              "/api/v1/bank/**",
              "/api/v1/org-units/bank/**",
              "/api/v1/users/**",
              "/api/v1/me/**",
              "/api/v1/notifications/**",
              "/api/v1/finance-entries/**",
              "/api/v1/missions/*/finance-entries/**",
              "/api/v1/operations/**",
              "/api/v1/personal-inventory/**",
              "/api/v1/personal-blueprints/**",
              "/api/v1/inventory/**",
              "/api/v1/hangar/**",
              "/api/v1/refinery-orders/**",
              "/api/v1/promotion/**")
          .map(PathPatternParser.defaultInstance::parse)
          .toList();

  /**
   * Header value for the families above: no intermediary, disk cache or proxy may keep the body.
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
   * Chooses the directive for a request path.
   *
   * <p>Matched against the decoded path for the same reason the {@code /api} scope is
   * (REQ-SEC-029): {@code getRequestURI()} is percent-encoded, and a spelling such as {@code
   * /api/v1/%62ank/accounts} must not slip out of the stricter bucket.
   *
   * @param uri the raw request URI; never {@code null} here, {@code shouldNotFilter} rejects null.
   * @return {@link #NO_STORE} for a sensitive family, {@link #REVALIDATE} otherwise.
   */
  private static String cacheControlFor(String uri) {
    PathContainer path = PathContainer.parsePath(uri);
    for (PathPattern scope : NO_STORE_SCOPES) {
      if (scope.matches(path)) {
        return NO_STORE;
      }
    }
    return REVALIDATE;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    response.setHeader("Cache-Control", cacheControlFor(request.getRequestURI()));
    response.addHeader("Vary", "Accept-Encoding");
    filterChain.doFilter(request, response);
  }
}
