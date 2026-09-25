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

package de.greluc.krt.profit.basetool.ingest.logging;

import de.greluc.krt.profit.basetool.ingest.config.LoggingProperties;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;

/**
 * Logs each outbound backend relay with method, host, path, status and elapsed time until response
 * headers (REQ-OBS-001).
 *
 * <ul>
 *   <li>INFO for a completed relay, with the {@code Slow backend call} marker past {@link
 *       LoggingProperties#slowBackendCallThresholdMs()}.
 *   <li>DEBUG for a 5xx response and a transport failure, which {@code GlobalExceptionHandler} logs
 *       at operator level.
 * </ul>
 *
 * <p>The query string, body and bearer token are never logged (REQ-OBS-004).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BackendCallLoggingInterceptor implements ClientHttpRequestInterceptor {

  private final LoggingProperties loggingProperties;

  /**
   * Times the relay and logs its outcome, then hands the response back unchanged; a transport
   * failure is logged at DEBUG and rethrown untouched.
   *
   * @param request the outbound request
   * @param body the buffered request body; never logged
   * @param execution the rest of the interceptor chain
   * @return the backend's response
   * @throws IOException when the exchange fails; propagated as-is
   */
  @Override
  public @NotNull ClientHttpResponse intercept(
      @NotNull HttpRequest request,
      byte @NotNull [] body,
      @NotNull ClientHttpRequestExecution execution)
      throws IOException {
    final long start = System.nanoTime();
    final String method = request.getMethod().name();
    final String host = String.valueOf(request.getURI().getHost());
    final String path = String.valueOf(request.getURI().getPath());
    ClientHttpResponse response;
    try {
      response = execution.execute(request, body);
    } catch (IOException | RuntimeException error) {
      logError(method, host, path, error, start);
      throw error;
    }
    logCall(method, host, path, response.getStatusCode().value(), start);
    return response;
  }

  /**
   * Logs a completed relay: INFO, with the {@code Slow backend call} marker past the threshold, or
   * DEBUG for a 5xx.
   *
   * @param method the outbound HTTP method
   * @param host the backend host
   * @param path the backend path (no query string)
   * @param status the response status code
   * @param startNanos the {@link System#nanoTime()} reading taken before the exchange
   */
  private void logCall(
      @NotNull String method,
      @NotNull String host,
      @NotNull String path,
      int status,
      long startNanos) {
    long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
    if (status >= 500) {
      log.debug("Backend call {} {}{} -> {} in {} ms", method, host, path, status, durationMs);
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

  /**
   * Logs a failed relay at DEBUG with the exception class only.
   *
   * @param method the outbound HTTP method
   * @param host the backend host
   * @param path the backend path (no query string)
   * @param error the propagated failure
   * @param startNanos the {@link System#nanoTime()} reading taken before the exchange
   */
  private void logError(
      @NotNull String method,
      @NotNull String host,
      @NotNull String path,
      @NotNull Throwable error,
      long startNanos) {
    long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
    log.debug(
        "Backend call {} {}{} failed after {} ms: {}",
        method,
        host,
        path,
        durationMs,
        error.getClass().getSimpleName());
  }
}
