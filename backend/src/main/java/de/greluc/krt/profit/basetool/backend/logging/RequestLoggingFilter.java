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
 * Emits one access-log line per request (method, path, status, duration) at INFO, or at WARN above
 * {@link LoggingProperties#getSlowRequestThresholdMs()}.
 *
 * <p>The notification SSE stream ({@value #STREAM_PATH}) never escalates to WARN, since its
 * duration is the stream's lifetime (REQ-OBS-009). Correlation and user ids come from the MDC.
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
      boolean slow = durationMs >= loggingProperties.slowRequestThresholdMs();
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
