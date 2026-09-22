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
 * Emits one log line per outbound backend relay — method, host, path, status and elapsed time — the
 * outbound twin of {@code RequestLoggingFilter}'s inbound access log and the ingest counterpart of
 * the frontend {@code WebClientLoggingFilter} (REQ-OBS-001). Without it a slow relay was invisible
 * in the gateway log: only a mapped failure produced a line, and it carried no duration.
 *
 * <p>A {@code RestClient} interceptor since the gateway left WebFlux (ADR-0204); it used to be a
 * {@code WebClient} exchange filter with the same levels and wording. It runs on the request
 * thread, so the MDC — correlation id, trace ids — is simply there; no Reactor context propagation
 * is involved any more.
 *
 * <p>Levels are chosen so a relay failure is still logged <b>exactly once at the level its status
 * warrants</b> (REQ-OBS-001). {@code GlobalExceptionHandler} already owns that decision — it WARNs
 * the backend 5xx it collapses into a 502 and the transport failure that opens the breaker, and
 * DEBUGs the short-circuit of an already-open breaker (the #1203 flood lesson). This interceptor
 * would otherwise double every one of those lines, so it logs:
 *
 * <ul>
 *   <li><b>INFO</b> for a completed relay, with the {@code Slow backend call} marker once it
 *       exceeds {@link LoggingProperties#slowBackendCallThresholdMs()}. Relay latency is alerted on
 *       through the {@code http.client.requests} p95 histogram, not this line, so a slow-but-
 *       successful call is never escalated to WARN (issue #1204).
 *   <li><b>DEBUG</b> for a 5xx response and for a transport failure — the diagnostic detail is
 *       welcome when the level is turned up, but the operator-facing line is the handler's.
 * </ul>
 *
 * <p>The elapsed time runs until the response headers arrive, as the exchange filter's did. Only
 * the method, host and path are logged; the query string and the request body are excluded (the
 * extract carries no PII, but the rule is unconditional — REQ-OBS-004), and the forwarded bearer
 * never appears at any level.
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
   * Logs a completed relay: INFO normally, INFO with the {@code Slow backend call} marker past the
   * threshold, DEBUG for a 5xx (whose operator-facing WARN belongs to {@code
   * GlobalExceptionHandler}).
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
   * Logs a failed relay at DEBUG with the exception class only — no message and no stack trace,
   * since the error is propagated and {@code GlobalExceptionHandler} emits the operator-facing WARN
   * (and the 502) for it.
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
