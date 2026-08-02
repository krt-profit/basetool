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
 * Owns the per-request MDC for the whole gateway (REQ-OBS-001/-002): it reads (or mints) the
 * correlation id, seeds the {@code userId} field, echoes the id back on the response, and clears
 * both keys again when the request unwinds. Runs first so the id is present for the bot-protection,
 * size-cap and rate-limit filters too. The inbound header is sanitized to a short safe charset to
 * keep log lines clean and prevent header/log injection.
 *
 * <p>The {@code userId} starts out as {@value #ANONYMOUS} because this filter runs <em>before</em>
 * Spring Security has authenticated anything — which is exactly right for the pre-auth filters that
 * log underneath it. {@link UserIdMdcFilter}, installed inside the security chain, overwrites it
 * with the JWT {@code sub} once the caller is known and deliberately does <b>not</b> clear it
 * again, so the value survives into the {@link RequestLoggingFilter} access-log line that is
 * emitted outside the security chain. This filter's {@code finally} is therefore the single place
 * where both keys are removed, which is what keeps a pooled or virtual thread from bleeding one
 * request's ids into the next.
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
