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

package de.greluc.krt.profit.basetool.ingest.config;

import de.greluc.krt.profit.basetool.ingest.metrics.MetricNames;
import de.greluc.krt.profit.basetool.ingest.web.ProblemResponseWriter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.channels.UnresolvedAddressException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Maps an identity-provider-unreachable authentication failure to a retryable {@code 503} instead
 * of a {@code 500} (REQ-SEC-024).
 *
 * <p>Installed before the bearer-token filter, it catches {@link AuthenticationServiceException}
 * and, only when the cause chain shows a transport or upstream-5xx failure, writes an RFC-7807
 * {@code 503} with {@code Retry-After} and code {@code SERVICE_UNAVAILABLE}, WARN-logged and
 * counted on {@code basetool_http_error_total}. Any other cause is re-thrown unchanged.
 */
@Slf4j
@RequiredArgsConstructor
public class IdentityProviderUnavailableFilter extends OncePerRequestFilter {

  /** {@code Retry-After} value (seconds) advertised to the client for a transient IdP outage. */
  private static final String RETRY_AFTER_SECONDS = "5";

  /** Bounded depth for the cause-chain walk — guards against a self-referential cause cycle. */
  private static final int MAX_CAUSE_DEPTH = 12;

  /** Serializes the 503 problem body. */
  private final ObjectMapper objectMapper;

  /** Counts the 503 on {@code basetool_http_error_total}. */
  private final MeterRegistry meterRegistry;

  /** Supplies the MDC key the problem body's {@code correlationId} is read from. */
  private final LoggingProperties loggingProperties;

  @Override
  protected void doFilterInternal(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull FilterChain filterChain)
      throws ServletException, IOException {
    try {
      filterChain.doFilter(request, response);
    } catch (AuthenticationServiceException ex) {
      if (!response.isCommitted() && isIdentityProviderUnreachable(ex)) {
        writeServiceUnavailable(request, response, ex);
        return;
      }
      throw ex;
    }
  }

  /**
   * Reports whether the bounded cause chain contains a transport-level or upstream-5xx failure
   * reaching the identity provider.
   *
   * @param throwable the caught {@link AuthenticationServiceException}
   * @return {@code true} when a transport / 5xx cause is present, {@code false} otherwise
   */
  private static boolean isIdentityProviderUnreachable(@NotNull Throwable throwable) {
    Throwable cause = throwable;
    for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
      if (cause instanceof IOException
          || cause instanceof UnresolvedAddressException
          || cause instanceof ResourceAccessException) {
        return true;
      }
      if (cause instanceof HttpStatusCodeException http
          && http.getStatusCode().is5xxServerError()) {
        return true;
      }
      Throwable next = cause.getCause();
      if (next == cause) {
        break;
      }
      cause = next;
    }
    return false;
  }

  /**
   * Counts the event, sets {@code Retry-After}, WARN-logs the cause class only and writes the
   * RFC-7807 503 through {@link ProblemResponseWriter}.
   *
   * @param request the failed request (used only for the diagnostic log line)
   * @param response the response to populate
   * @param cause the classified failure, logged by class name
   * @throws IOException if writing the body fails
   */
  private void writeServiceUnavailable(
      HttpServletRequest request, HttpServletResponse response, Throwable cause)
      throws IOException {
    meterRegistry
        .counter(MetricNames.HTTP_ERROR, MetricNames.TAG_CODE, MetricNames.CODE_SERVICE_UNAVAILABLE)
        .increment();
    log.warn(
        "Identity provider unreachable for {} {} [cause={}] — returning 503",
        request.getMethod(),
        request.getRequestURI(),
        cause.getClass().getSimpleName());
    response.setHeader(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
    ProblemResponseWriter.write(
        response,
        objectMapper,
        loggingProperties,
        HttpStatus.SERVICE_UNAVAILABLE,
        "Service unavailable",
        MetricNames.CODE_SERVICE_UNAVAILABLE,
        "The authentication service is temporarily unreachable. Please retry shortly.");
  }
}
