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

package de.greluc.krt.profit.basetool.backend.config;

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.support.ProblemResponseFactory;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.channels.UnresolvedAddressException;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.slf4j.MDC;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns an unreachable identity provider during token validation into a retryable {@code 503}
 * instead of a {@code 500} (REQ-SEC-024).
 *
 * <p>Runs before the bearer-token filter and maps only an {@link AuthenticationServiceException}
 * with a transport or upstream-5xx cause; token rejections and other failures pass unchanged. The
 * response is an RFC 7807 problem document with {@code Retry-After}, logged at {@code WARN}.
 */
@Slf4j
@RequiredArgsConstructor
public class IdentityProviderUnavailableFilter extends OncePerRequestFilter {

  /** Stable RFC-7807 code echoed in the body and used as the metric tag value. */
  static final String CODE_SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";

  /** Problem-type suffix appended to {@link AppProblemProperties#getBaseUri()}. */
  private static final String TYPE_SUFFIX = "service-unavailable";

  /** {@code Retry-After} value (seconds) advertised to the client for a transient IdP outage. */
  private static final String RETRY_AFTER_SECONDS = "5";

  /** App-wide correlation-id response header, mirroring {@code LoggingProperties} default. */
  private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

  /** MDC key the correlation-id filter populates; reused here when already assigned. */
  private static final String MDC_CORRELATION_ID = "correlationId";

  /** Bounded depth for the cause-chain walk — guards against a self-referential cause cycle. */
  private static final int MAX_CAUSE_DEPTH = 12;

  /** Resolves the localized {@code problem.service_unavailable.*} title/detail. */
  private final @NotNull MessageSource messageSource;

  /** Assembles the RFC-7807 {@link ProblemDetail} body. */
  private final @NotNull ProblemResponseFactory problemResponseFactory;

  /** Serializes the {@link ProblemDetail} with uniform JSON escaping. */
  private final @NotNull ObjectMapper objectMapper;

  /** Counts the re-mapped 503 on {@code basetool_http_error_total}. */
  private final @NotNull MeterRegistry meterRegistry;

  /**
   * Runs the downstream chain and answers a retryable 503 problem document when an {@link
   * AuthenticationServiceException} stems from an unreachable identity provider; every other
   * exception propagates unchanged.
   *
   * @param request the current request
   * @param response the response to write the 503 into
   * @param filterChain the downstream chain, including the bearer-token filter
   * @throws ServletException propagated from the downstream chain
   * @throws IOException propagated from the chain or raised while writing the body
   */
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
   * Whether the bounded cause chain contains a transport or upstream-5xx failure towards the
   * identity provider.
   *
   * @param throwable the caught {@link AuthenticationServiceException}
   * @return {@code true} when a transport or 5xx cause is present
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
   * Writes the RFC 7807 503 body as UTF-8, sets {@code Retry-After}, mirrors the correlation id and
   * increments the error counter; localizes from {@code request.getLocale()}.
   *
   * @param request the failed request, whose URI becomes the {@code instance}
   * @param response the response to populate
   * @param cause the classified failure, logged by class name at WARN
   * @throws IOException if serializing or writing the body fails
   */
  private void writeServiceUnavailable(
      HttpServletRequest request, HttpServletResponse response, Throwable cause)
      throws IOException {
    String correlationId = correlationId();
    Locale locale = request.getLocale();
    final String title =
        messageSource.getMessage(
            "problem.service_unavailable.title", null, "Service Unavailable", locale);
    final String detail =
        messageSource.getMessage(
            "problem.service_unavailable.detail",
            null,
            "The authentication service is temporarily unreachable. Please retry shortly.",
            locale);

    log.warn(
        "Identity provider unreachable for {} {} [cause={}, correlationId={}] — returning 503",
        request.getMethod(),
        request.getRequestURI(),
        cause.getClass().getSimpleName(),
        correlationId);

    meterRegistry
        .counter(MetricNames.HTTP_ERROR, MetricNames.TAG_CODE, CODE_SERVICE_UNAVAILABLE)
        .increment();

    response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setHeader(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
    response.setHeader(CORRELATION_ID_HEADER, correlationId);
    ProblemDetail problem =
        problemResponseFactory.problem(
            HttpStatus.SERVICE_UNAVAILABLE,
            title,
            detail,
            request.getRequestURI(),
            TYPE_SUFFIX,
            CODE_SERVICE_UNAVAILABLE,
            correlationId);
    response.getOutputStream().write(objectMapper.writeValueAsBytes(problem));
  }

  /**
   * Reuses the request-scoped correlation id from the SLF4J {@code MDC} when the correlation filter
   * has already run, otherwise mints a fresh UUID so the 503 stays traceable end to end.
   *
   * @return the correlation id to stamp on the body, header and log line
   */
  private static @NotNull String correlationId() {
    String existing = MDC.get(MDC_CORRELATION_ID);
    if (existing != null && !existing.isBlank()) {
      return existing;
    }
    return UUID.randomUUID().toString();
  }
}
