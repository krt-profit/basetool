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

import de.greluc.krt.profit.basetool.backend.filter.NoStoreApiScopes;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.web.filter.ShallowEtagHeaderFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * The ETag filter, bypassed for Server-Sent-Event endpoints and for the {@link NoStoreApiScopes}
 * families.
 *
 * <p>{@link ShallowEtagHeaderFilter} buffers the response body and skips its write-back once async
 * processing started, which drops every byte of an SSE stream. A {@code no-store} response is never
 * eligible for an ETag ({@link ShallowEtagHeaderFilter#isEligibleForEtag}), so buffering it only
 * costs memory; without the buffer such a response may ship chunked instead of with a {@code
 * Content-Length}.
 */
public class StreamAwareShallowEtagHeaderFilter extends ShallowEtagHeaderFilter {

  /** The only surface the no-store question can be about, checked before the fourteen patterns. */
  private static final PathPattern API_SCOPE = PathPatternParser.defaultInstance.parse("/api/**");

  /**
   * The streaming endpoints, matched exactly so the correctness guard cannot be re-scoped by a
   * prefix.
   */
  private static final List<PathPattern> STREAMING_PATHS =
      List.of(
          PathPatternParser.defaultInstance.parse("/api/v1/notifications/stream"),
          PathPatternParser.defaultInstance.parse("/api/v1/live-sync/stream"));

  /**
   * Answers whether this request bypasses the ETag buffer.
   *
   * <p>Matched on the parsed request URI without normalisation, so an unnormalised spelling of an
   * endpoint is buffered like any other response.
   *
   * @param request the request
   * @return {@code true} for a Server-Sent-Event endpoint, or for a family whose response can never
   *     carry an ETag
   */
  @Override
  protected boolean shouldNotFilter(@NotNull HttpServletRequest request) {
    String uri = request.getRequestURI();
    if (uri == null) {
      return false;
    }
    PathContainer path = PathContainer.parsePath(uri);
    if (STREAMING_PATHS.stream().anyMatch(pattern -> pattern.matches(path))) {
      return true;
    }
    return HttpMethod.GET.matches(request.getMethod())
        && API_SCOPE.matches(path)
        && NoStoreApiScopes.matches(path);
  }
}
