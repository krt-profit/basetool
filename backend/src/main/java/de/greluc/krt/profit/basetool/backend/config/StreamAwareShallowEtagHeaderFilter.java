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
 *
 * <h2>The second exemption: responses that can never carry an ETag</h2>
 *
 * <p>The streaming paths were a correctness fix. {@link NoStoreApiScopes} is a cost fix, and it
 * rests on a fact rather than a judgement: {@link ShallowEtagHeaderFilter#isEligibleForEtag}
 * returns {@code false} as soon as the response carries {@code Cache-Control: no-store}, and {@code
 * ApiCacheControlFilter} sets exactly that on those fourteen families — from {@code
 * HIGHEST_PRECEDENCE + 20}, ahead of this filter's write-back. <b>So on those paths no ETag is
 * generated today either.</b> What still happens is the whole point: the response is buffered into
 * a {@code ContentCachingResponseWrapper} on the way out and copied back afterwards, in full, in
 * memory — for a header the framework has already decided not to emit.
 *
 * <p>That makes this exemption free of behavioural risk in a way the alternatives are not. Not one
 * response header changes: the families that lose the buffer had no ETag to lose. Skipping the
 * <em>catalogue</em> paths would have been the larger saving — the materials matrix that tipped the
 * buffer at 16 MB revalidates rather than {@code no-store}s, so it does get an ETag — and it was
 * deliberately not taken here, because that ETag is inert only for as long as no client sends
 * {@code If-None-Match}. That is a property of today's clients, not of the response, and removing
 * it would quietly foreclose the mobile read cache in {@code docs/WIRE_PROTOCOL_EVALUATION.md}
 * §8.3.
 *
 * <p>The list is not copied here. Both filters read {@link NoStoreApiScopes}, so a family added to
 * one is added to the other — ADR-0135's argument about a second copy of a rule, applied to a rule
 * about caching rather than authorisation.
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
   * <p>Two reasons to bypass, and they are not the same reason. A streaming path <b>must</b> escape
   * the buffer or its bytes are dropped (#1653). A {@link NoStoreApiScopes} path <b>gains
   * nothing</b> from it, because {@code Cache-Control: no-store} already makes the response
   * ineligible for an ETag — the buffer is paid for and then thrown away. Both are answered here so
   * a caller sees one decision rather than two half-filters.
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
    return NoStoreApiScopes.matches(uri);
  }
}
