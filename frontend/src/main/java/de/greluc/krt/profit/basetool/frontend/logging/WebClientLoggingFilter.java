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
 *       status and elapsed time. A {@code 5xx} server fault is logged at WARN; a non-{@code 5xx}
 *       response that merely exceeds {@link LoggingProperties#slowBackendCallThresholdMs()} is
 *       still a success and is logged at INFO with an explicit {@code Slow backend call} marker
 *       rather than escalated to WARN (issue #1204) – backend-call latency is alerted on through
 *       the {@code http.client.requests} p95 histogram, not this log line. Network-level failures
 *       are logged at WARN as well, with the exception class and message only (no stack trace – the
 *       exception is re-thrown and the frontend {@code GlobalExceptionHandler} decides on
 *       user-facing behaviour).
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
   * Returns filter that logs one line per call including method/host/path/status/duration. A {@code
   * 5xx} is logged at WARN; a slow-but-non-{@code 5xx} call is logged at INFO with a {@code Slow
   * backend call} marker rather than escalated to WARN (issue #1204).
   *
   * @return filter that logs one line per call including method/host/path/status/duration, with
   *     only {@code 5xx} responses (and network failures) at WARN.
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
    if (status >= 500) {
      log.warn("Backend call {} {}{} -> {} in {} ms", method, host, path, status, durationMs);
    } else if (durationMs >= loggingProperties.slowBackendCallThresholdMs()) {
      // A non-5xx response that merely took a while is still a success — surface the latency at
      // INFO with an explicit marker instead of crying wolf at WARN (issue #1204). Backend-call
      // slowness is alerted on through the http.client.requests p95 histogram, not this line.
      log.info(
          "Slow backend call {} {}{} -> {} in {} ms (threshold {} ms)",
          method,
          host,
          path,
          status,
          durationMs,
          loggingProperties.slowBackendCallThresholdMs());
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
