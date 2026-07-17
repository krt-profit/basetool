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

package de.greluc.krt.profit.basetool.backend.logging;

import de.greluc.krt.profit.basetool.backend.config.LoggingProperties;
import de.greluc.krt.profit.basetool.backend.config.NotificationStreamObservationPredicate;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Emits a single structured access-log line per request on INFO (or WARN for slow requests).
 *
 * <p>The line includes HTTP method, path, response status and elapsed duration. Correlation id and
 * user id are intentionally not duplicated into the message body because they are already rendered
 * via the MDC pattern in {@code logback-spring.xml}. This avoids noisy, redundant log output and
 * keeps structured-JSON fields clean.
 *
 * <p>Slow requests (duration above {@link LoggingProperties#getSlowRequestThresholdMs()}) are
 * logged at WARN so operators can flag them in dashboards / alerting. The notification SSE relay
 * ({@value #STREAM_PATH}) is exempt from that escalation: Spring MVC books the async request's
 * whole lifetime as the elapsed duration, so a stream held open for up to 30 minutes ({@code
 * NotificationStreamService.EMITTER_TIMEOUT_MS}) would cross the threshold on every close and flood
 * the access log with false-positive WARN lines. It still gets its one INFO access-log line. This
 * is the access-log mirror of {@link NotificationStreamObservationPredicate} dropping the same
 * endpoint from {@code http.server.requests} (REQ-OBS-001/-009).
 *
 * <p>Runs slightly earlier than {@link CorrelationIdFilter} in terms of filter order but since both
 * filters run once per request, the MDC values set by the correlation filter are still available
 * when this filter logs in its {@code finally} block – the correlation filter wraps this one via
 * the servlet chain.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RequestLoggingFilter extends OncePerRequestFilter implements Ordered {

  /** Request path of the long-lived notification SSE relay, excluded from slow-request WARNs. */
  private static final String STREAM_PATH = "/api/v1/notifications/stream";

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
      int status = response.getStatus();
      String method = request.getMethod();
      String path = request.getRequestURI();
      boolean slow = durationMs >= loggingProperties.getSlowRequestThresholdMs();
      if (slow && !STREAM_PATH.equals(path)) {
        log.warn("Slow request {} {} -> {} in {} ms", method, path, status, durationMs);
      } else if (log.isInfoEnabled()) {
        log.info("{} {} -> {} in {} ms", method, path, status, durationMs);
      }
    }
  }

  /**
   * Skip static resources and the actuator to keep the request log focused on real business
   * traffic. Swagger-UI assets would otherwise dominate the log during development.
   */
  @Override
  protected boolean shouldNotFilter(@NotNull HttpServletRequest request) {
    String uri = request.getRequestURI();
    return uri.startsWith("/actuator/")
        || uri.startsWith("/swagger-ui")
        || uri.startsWith("/v3/api-docs")
        || uri.startsWith("/webjars/")
        || uri.equals("/favicon.ico");
  }

  /** Run just inside the correlation filter so the MDC is populated when we log. */
  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE - 50;
  }
}
