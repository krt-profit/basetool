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
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Owns the per-request MDC of the gateway (REQ-OBS-001/-002): reads or mints a sanitized
 * correlation id, seeds {@code userId} with {@value #ANONYMOUS}, echoes the id on the response and
 * removes both keys when the request ends. Runs first so every later filter logs with the id.
 */
@Component
@Order(CorrelationIdFilter.ORDER)
@RequiredArgsConstructor
public class CorrelationIdFilter extends OncePerRequestFilter {

  /** Runs before the size, rate-limit and Spring Security filters so every log line is tagged. */
  public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

  /** MDC {@code userId} value for a request that carries no authenticated subject (yet). */
  public static final String ANONYMOUS = "anonymous";

  private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9._-]{1,128}$");

  private final LoggingProperties loggingProperties;

  @Override
  protected void doFilterInternal(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull FilterChain filterChain)
      throws ServletException, IOException {
    String incoming = request.getHeader(loggingProperties.correlationIdHeader());
    String correlationId =
        incoming != null && SAFE.matcher(incoming).matches()
            ? incoming
            : UUID.randomUUID().toString();
    MDC.put(loggingProperties.correlationIdMdcKey(), correlationId);
    MDC.put(loggingProperties.userIdMdcKey(), ANONYMOUS);
    response.setHeader(loggingProperties.correlationIdHeader(), correlationId);
    try {
      filterChain.doFilter(request, response);
    } finally {
      MDC.remove(loggingProperties.correlationIdMdcKey());
      MDC.remove(loggingProperties.userIdMdcKey());
    }
  }
}
