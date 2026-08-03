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
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Emits one INFO access-log line per ingest {@code /v1/**} request — method, path, response status
 * and elapsed duration — so a handoff no longer succeeds or fails without a correlated log trace
 * (the gateway previously logged only a sparse relay WARN and nothing at all for a
 * 413/429/success), bringing ingest in line with the backend/frontend one-line-per-request
 * contract. The {@code correlationId} and {@code userId} are rendered from the MDC by the logback
 * pattern and are therefore not duplicated into the message.
 *
 * <p>A request that exceeds {@link LoggingProperties#slowRequestThresholdMs()} is escalated to WARN
 * with the {@code Slow request} marker, exactly as in the backend/frontend (REQ-OBS-001). The
 * gateway needs no counterpart to their SSE-relay carve-out: it serves two short synchronous POSTs
 * and holds no async request open, so no endpoint can cross the threshold by design.
 *
 * <p>{@code getRequestURI()} excludes the query string, so no user-supplied query text reaches the
 * log; the ingest {@code /v1} paths carry no entity ids either, so the path is safe to log
 * verbatim.
 *
 * <p>Ordered just inside {@link CorrelationIdFilter} (so the MDC id is populated when this filter
 * logs in its {@code finally}) but OUTSIDE the size-cap / rate-limit / security filters, so the
 * line captures the final status even when one of them short-circuits (a 413 payload reject, a 429
 * rate limit, a 401/403). {@link #shouldNotFilter} keeps it to {@code /v1/**} so actuator / health
 * / api-docs traffic never pollutes the access log.
 */
@Slf4j
@Component
@Order(RequestLoggingFilter.ORDER)
@RequiredArgsConstructor
public class RequestLoggingFilter extends OncePerRequestFilter {

  /**
   * Just inside {@link CorrelationIdFilter} (id set) but outside the size / rate / security
   * filters.
   */
  public static final int ORDER = CorrelationIdFilter.ORDER + 5;

  private final LoggingProperties loggingProperties;

  @Override
  protected void doFilterInternal(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull FilterChain filterChain)
      throws ServletException, IOException {
    long start = System.nanoTime();
    try {
      filterChain.doFilter(request, response);
    } finally {
      long durationMs = (System.nanoTime() - start) / 1_000_000L;
      String method = request.getMethod();
      String path = request.getRequestURI();
      int status = response.getStatus();
      if (durationMs >= loggingProperties.slowRequestThresholdMs()) {
        log.warn("Slow request {} {} -> {} in {} ms", method, path, status, durationMs);
      } else if (log.isInfoEnabled()) {
        log.info("{} {} -> {} in {} ms", method, path, status, durationMs);
      }
    }
  }

  /**
   * Limits the access log to the ingest endpoints; actuator, health and api-docs are unaffected.
   *
   * <p>Decided on the decoded path via {@link IngestPathScope}. With the raw prefix test an encoded
   * spelling of an ingest path produced no access-log line at all, which is the worst possible
   * pairing with the sibling filters it also skipped: the one request class that evaded the
   * client-identity gate was also the one class that left no trace.
   *
   * @param request the current request
   * @return {@code true} for any path that is not under {@code /v1}
   */
  @Override
  protected boolean shouldNotFilter(@NotNull HttpServletRequest request) {
    return !IngestPathScope.isIngestRequest(request);
  }
}
