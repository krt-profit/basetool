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
import de.greluc.krt.profit.basetool.logging.LogSafe;
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
 * Emits one INFO access-log line per ingest {@code /v1/**} request with method, path, status and
 * duration, escalated to WARN with the {@code Slow request} marker past {@link
 * LoggingProperties#slowRequestThresholdMs()} (REQ-OBS-001).
 *
 * <p>Runs just inside {@link CorrelationIdFilter} and outside the rate-limit, size-cap and security
 * filters, so it records their short-circuit statuses too. The query string is never logged.
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
      String path = LogSafe.text(request.getRequestURI(), 256);
      int status = response.getStatus();
      if (durationMs >= loggingProperties.slowRequestThresholdMs()) {
        log.warn("Slow request {} {} -> {} in {} ms", method, path, status, durationMs);
      } else if (log.isInfoEnabled()) {
        log.info("{} {} -> {} in {} ms", method, path, status, durationMs);
      }
    }
  }

  /**
   * Limits the access log to the ingest endpoints, decided on the decoded path via {@link
   * IngestPathScope}.
   *
   * @param request the current request
   * @return {@code true} for any path that is not under {@code /v1}
   */
  @Override
  protected boolean shouldNotFilter(@NotNull HttpServletRequest request) {
    return !IngestPathScope.isIngestRequest(request);
  }
}
