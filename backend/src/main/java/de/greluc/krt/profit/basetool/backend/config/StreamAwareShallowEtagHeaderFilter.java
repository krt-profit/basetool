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

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.server.PathContainer;
import org.springframework.web.filter.ShallowEtagHeaderFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * The ETag filter, kept away from the Server-Sent-Event endpoints.
 *
 * <p><strong>This exists because the plain filter silently killed every SSE stream in this
 * application.</strong> {@link ShallowEtagHeaderFilter} computes a shallow ETag by buffering the
 * whole response body in a content-caching wrapper and writing it back afterwards — and its
 * write-back is explicitly skipped when the request has started async processing:
 *
 * <pre>{@code
 * filterChain.doFilter(request, responseToUse);
 * if (!isAsyncStarted(request)) {
 *   updateResponse(request, responseToUse);
 * }
 * }</pre>
 *
 * <p>An {@code SseEmitter} is exactly that case. Every event written to the emitter landed in the
 * wrapper's buffer, async was started, the write-back never ran, and the bytes were dropped. From
 * outside, the endpoint answered {@code 200} and then produced nothing at all — not the body, not
 * even the status line — for as long as the connection was held.
 *
 * <p>The failure had no signal. {@code basetool_sse_connections} counts emitters that were
 * <em>created</em>, not bytes that arrived, so a push channel accepting connections and delivering
 * nothing reads as healthy on every dashboard; {@code SsePushChannelDead} watches for zero
 * connections and there were plenty. It was found by walking a device: the backend's own {@code
 * basetool_livesync_delivered_total} incremented while the app on the other end of that connection
 * logged nothing (#1653).
 *
 * <p>Skipping the filter outright for these paths, rather than relying on Spring's streaming
 * awareness, is deliberate. That mechanism keys off a request attribute the caching wrapper checks
 * at write time, so it depends on the attribute being set before the first write on a path this
 * filter has already wrapped — a coupling that was evidently not holding here and that nothing in
 * our own code controls. Not wrapping a stream at all has no such condition, and an ETag over a
 * response with no end was never meaningful anyway.
 */
public class StreamAwareShallowEtagHeaderFilter extends ShallowEtagHeaderFilter {

  /**
   * The streaming endpoints, matched exactly.
   *
   * <p>Exact patterns rather than a prefix: the notification family carries ordinary reads that
   * benefit from an ETag, and only its {@code /stream} member must escape the buffer.
   */
  private static final List<PathPattern> STREAMING_PATHS =
      List.of(
          PathPatternParser.defaultInstance.parse("/api/v1/notifications/stream"),
          PathPatternParser.defaultInstance.parse("/api/v1/live-sync/stream"));

  /**
   * Answers whether this request must bypass the ETag buffer.
   *
   * <p>Matched on the parsed request URI, the same idiom the per-subject rate limiter uses. It does
   * not collapse dot segments or decode escapes, so an unnormalised spelling of one of these
   * endpoints is buffered like any other response — bounded and deliberate: it costs that one
   * client its stream and exposes nothing, and a stricter normalisation belongs in both filters at
   * once rather than in this one alone.
   *
   * @param request the request
   * @return {@code true} for a Server-Sent-Event endpoint
   */
  @Override
  protected boolean shouldNotFilter(@NotNull HttpServletRequest request) {
    String uri = request.getRequestURI();
    if (uri == null) {
      return false;
    }
    PathContainer path = PathContainer.parsePath(uri);
    return STREAMING_PATHS.stream().anyMatch(pattern -> pattern.matches(path));
  }
}
