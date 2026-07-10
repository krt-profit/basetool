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

package de.greluc.krt.profit.basetool.frontend.logging;

import de.greluc.krt.profit.basetool.frontend.config.LoggingProperties;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;

/**
 * Provides {@link ExchangeFilterFunction}s for the frontend {@code WebClient}.
 *
 * <p>Two goals:
 *
 * <ul>
 *   <li><b>Correlation propagation</b> – injects the current request's {@code X-Correlation-Id}
 *       (configurable via {@link LoggingProperties#getCorrelationIdHeader()}) into outbound backend
 *       calls so the backend log line shares the same id.
 *   <li><b>Structured call logging</b> – logs one line per outbound call with method, host, path,
 *       status and elapsed time. Slow calls (above {@link
 *       LoggingProperties#getSlowBackendCallThresholdMs()}) escalate to WARN so they can be flagged
 *       in dashboards. Network-level failures are logged at WARN as well, with the exception class
 *       and message only (no stack trace – the exception is re-thrown and the frontend {@code
 *       GlobalExceptionHandler} decides on user-facing behaviour). A {@link
 *       CallNotPermittedException} is the exception: it means the circuit breaker short-circuited
 *       the call locally (0 ms, no backend hit), an expected self-healing state that repeats for
 *       every call for the whole open window — logged at DEBUG so a routine backend restart/deploy
 *       does not flood WARN (issue #1203, REQ-OBS-001).
 * </ul>
 *
 * <p>Query strings are intentionally excluded from the log line because they may carry PII such as
 * filter expressions containing email fragments or user ids of other users (see AGENTS.md: no
 * information disclosure).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebClientLoggingFilter {

  private final LoggingProperties loggingProperties;

  /**
   * Returns filter that adds the correlation id header if one is bound to the current thread.
   *
   * @return filter that adds the correlation id header if one is bound to the current thread.
   */
  @NotNull
  public ExchangeFilterFunction correlationIdPropagation() {
    return (request, next) -> {
      String correlationId = CorrelationContext.get();
      if (correlationId == null || correlationId.isBlank()) {
        return next.exchange(request);
      }
      ClientRequest propagated =
          ClientRequest.from(request)
              .header(loggingProperties.correlationIdHeader(), correlationId)
              .build();
      return next.exchange(propagated);
    };
  }

  /**
   * Returns filter that logs one line per call including method/host/path/status/duration. Slow
   * calls are escalated to WARN.
   *
   * @return filter that logs one line per call including method/host/path/status/duration. Slow
   *     calls are escalated to WARN.
   */
  @NotNull
  public ExchangeFilterFunction callLogging() {
    return (request, next) -> {
      final long start = System.nanoTime();
      final String method = request.method().name();
      final String host = request.url().getHost();
      final String path = request.url().getPath();
      return next.exchange(request)
          .doOnNext(response -> logCall(method, host, path, response.statusCode().value(), start))
          .doOnError(err -> logError(method, host, path, err, start));
    };
  }

  private void logCall(
      @NotNull String method,
      @NotNull String host,
      @NotNull String path,
      int status,
      long startNanos) {
    long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
    if (durationMs >= loggingProperties.slowBackendCallThresholdMs() || status >= 500) {
      log.warn("Backend call {} {}{} -> {} in {} ms", method, host, path, status, durationMs);
    } else if (log.isInfoEnabled()) {
      log.info("Backend call {} {}{} -> {} in {} ms", method, host, path, status, durationMs);
    }
  }

  private void logError(
      @NotNull String method,
      @NotNull String host,
      @NotNull String path,
      @NotNull Throwable err,
      long startNanos) {
    long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
    // A CallNotPermittedException here is the circuit breaker short-circuiting the call (0 ms, no
    // backend hit) — an expected, self-healing state that repeats for every call for the whole open
    // window, not a backend failure. Log it at DEBUG so a routine backend restart/deploy does not
    // flood WARN with "Backend call … failed" lines (issue #1203). Genuine transport failures
    // (timeout, connection reset) still log at WARN — they are the signal the backend is down.
    if (err instanceof CallNotPermittedException) {
      log.debug(
          "Backend call {} {}{} short-circuited after {} ms (circuit breaker open)",
          method,
          host,
          path,
          durationMs);
      return;
    }
    log.warn(
        "Backend call {} {}{} failed after {} ms: {}: {}",
        method,
        host,
        path,
        durationMs,
        err.getClass().getSimpleName(),
        err.getMessage());
  }
}
