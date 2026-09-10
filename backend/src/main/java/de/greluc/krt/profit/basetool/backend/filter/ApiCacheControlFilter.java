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
 * relies on conditional requests for these families. Spring agrees: {@code
 * ShallowEtagHeaderFilter#isEligibleForEtag} refuses to generate an ETag once {@code Cache-Control}
 * carries {@code no-store}, so on these families the header genuinely does not exist. That is why
 * {@link NoStoreApiScopes} — the list this filter used to own privately — now also drives {@code
 * StreamAwareShallowEtagHeaderFilter}: a response that provably cannot carry an ETag has no reason
 * to be buffered for one.
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
    return NoStoreApiScopes.matches(uri) ? NO_STORE : REVALIDATE;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    response.setHeader("Cache-Control", cacheControlFor(request.getRequestURI()));
    // Accept joined Accept-Encoding when the API gained a second representation (ADR-0161 8.5):
    // the same path now answers CBOR or JSON depending on what the caller asked for, and a cache
    // keyed only on the URL would hand a CBOR body to a JSON client. The revalidate families are
    // the ones this protects -- the no-store families are not stored anywhere to begin with.
    response.addHeader("Vary", "Accept");
    response.addHeader("Vary", "Accept-Encoding");
    filterChain.doFilter(request, response);
  }
}
