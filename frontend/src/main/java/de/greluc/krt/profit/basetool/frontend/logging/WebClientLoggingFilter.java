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
 * <ul>
 *   <li>correlation propagation of the {@code X-Correlation-Id} header ({@link
 *       LoggingProperties#getCorrelationIdHeader()}) to backend calls;
 *   <li>one log line per call with method, host, path, status and elapsed time: {@code 5xx} and
 *       network failures at WARN, a slow non-{@code 5xx} call at INFO, and a {@link
 *       CallNotPermittedException} at DEBUG.
 * </ul>
 *
 * <p>Query strings are never logged.
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
   * Returns a filter that logs one line per call with method, host, path, status and duration;
   * {@code 5xx} at WARN, a slow non-{@code 5xx} call at INFO with a {@code Slow backend call}
   * marker.
   *
   * @return the call-logging filter, with only {@code 5xx} responses (and network failures) at
   *     WARN.
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
