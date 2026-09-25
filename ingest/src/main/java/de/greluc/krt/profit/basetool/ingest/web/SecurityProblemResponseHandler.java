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

package de.greluc.krt.profit.basetool.ingest.web;

import de.greluc.krt.profit.basetool.ingest.config.LoggingProperties;
import de.greluc.krt.profit.basetool.ingest.metrics.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes the gateway's filter-level {@code 401} and {@code 403} as RFC 7807 problem bodies like
 * every other ingest error (REQ-API-004), keeping the {@code WWW-Authenticate} challenge of {@link
 * BearerTokenAuthenticationEntryPoint}.
 *
 * <p>The correlation id is taken from the MDC. A {@code 401} is logged at DEBUG and a {@code 403}
 * at WARN (REQ-OBS-001); both are counted on {@code basetool_http_error_total} (REQ-OBS-011).
 */
@Slf4j
@RequiredArgsConstructor
public class SecurityProblemResponseHandler
    implements AuthenticationEntryPoint, AccessDeniedHandler {

  /** Emits the RFC 6750 {@code WWW-Authenticate} challenge before the problem body is written. */
  private final BearerTokenAuthenticationEntryPoint bearerEntryPoint =
      new BearerTokenAuthenticationEntryPoint();

  /** Serializes the problem body. */
  private final ObjectMapper objectMapper;

  /** Counts every 401/403 on the bounded auth-failure and error counters. */
  private final MeterRegistry meterRegistry;

  /** Supplies the MDC key the problem body's {@code correlationId} is read from. */
  private final LoggingProperties loggingProperties;

  /**
   * Answers an unauthenticated request to a protected endpoint with a {@code 401} problem body,
   * keeping the {@code WWW-Authenticate} challenge Spring Security would have sent on its own.
   *
   * @param request the rejected request
   * @param response the response to write the problem body into
   * @param authException the authentication failure Spring Security raised
   * @throws IOException if writing the body fails
   */
  @Override
  public void commence(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull AuthenticationException authException)
      throws IOException {
    if (response.isCommitted()) {
      return;
    }
    bearerEntryPoint.commence(request, response, authException);
    String bearerErrorCode = bearerErrorCode(authException);
    log.debug(
        "Unauthenticated ingest request {} {} ({}, {})",
        request.getMethod(),
        request.getRequestURI(),
        authException.getClass().getSimpleName(),
        bearerErrorCode);
    meterRegistry
        .counter(MetricNames.INGEST_AUTH_FAILURES, MetricNames.TAG_REASON, bearerErrorCode)
        .increment();
    write(
        response,
        HttpStatus.UNAUTHORIZED,
        "Unauthenticated",
        MetricNames.CODE_UNAUTHENTICATED,
        "A valid bearer token is required.");
  }

  /**
   * Maps an authentication failure to its RFC 6750 bearer error code, or to {@link
   * MetricNames#AUTH_NO_CREDENTIALS} for a request without a credential (REQ-OBS-018), keeping the
   * metric label bounded (REQ-OBS-011). The error description is never read, as it can echo token
   * fragments.
   *
   * @param authException the failure Spring Security raised
   * @return one of the bounded {@code MetricNames.AUTH_*} values
   */
  private static @NotNull String bearerErrorCode(@NotNull AuthenticationException authException) {
    if (!(authException instanceof OAuth2AuthenticationException oauth2Exception)) {
      return authException instanceof InsufficientAuthenticationException
              || authException instanceof AuthenticationCredentialsNotFoundException
          ? MetricNames.AUTH_NO_CREDENTIALS
          : MetricNames.AUTH_OTHER;
    }
    String code =
        oauth2Exception.getError() == null ? null : oauth2Exception.getError().getErrorCode();
    if (MetricNames.AUTH_INVALID_TOKEN.equals(code)
        || MetricNames.AUTH_INVALID_REQUEST.equals(code)
        || MetricNames.AUTH_INSUFFICIENT_SCOPE.equals(code)) {
      return code;
    }
    return MetricNames.AUTH_OTHER;
  }

  /**
   * Answers an authenticated-but-not-allowed request with a {@code 403} problem body.
   *
   * @param request the rejected request
   * @param response the response to write the problem body into
   * @param accessDeniedException the authorization failure Spring Security raised
   * @throws IOException if writing the body fails
   */
  @Override
  public void handle(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull AccessDeniedException accessDeniedException)
      throws IOException {
    if (response.isCommitted()) {
      return;
    }
    log.warn("Access denied on ingest request {} {}", request.getMethod(), request.getRequestURI());
    write(
        response,
        HttpStatus.FORBIDDEN,
        "Access denied",
        MetricNames.CODE_ACCESS_DENIED,
        "You are not allowed to use this endpoint.");
  }

  /**
   * Counts the rejection under its stable code and writes the problem body through the same writer
   * the pre-security filters use, so all short-circuited ingest responses share one shape.
   *
   * @param response the response to populate
   * @param status the HTTP status to send
   * @param title the short, stable problem title
   * @param code the stable machine-readable code, also the metric tag
   * @param detail the non-sensitive, human-readable detail
   * @throws IOException if writing the body fails
   */
  private void write(
      HttpServletResponse response, HttpStatus status, String title, String code, String detail)
      throws IOException {
    meterRegistry.counter(MetricNames.HTTP_ERROR, MetricNames.TAG_CODE, code).increment();
    ProblemResponseWriter.write(
        response, objectMapper, loggingProperties, status, title, code, detail);
  }
}
