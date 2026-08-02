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
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.ObjectMapper;

/**
 * Gives the gateway's filter-level {@code 401} / {@code 403} the same RFC 7807 shape as every other
 * ingest error (REQ-API-004). Spring Security's defaults answer a rejected bearer request with an
 * <em>empty body</em> — no {@code code}, no {@code correlationId}, nothing the desktop extractor
 * could branch on or a user could quote in a bug report — and log nothing at all, so an expired
 * token produced a completely silent 401.
 *
 * <p>The {@code WWW-Authenticate} challenge is preserved by delegating to {@link
 * BearerTokenAuthenticationEntryPoint} first: it only sets that header and the status (via {@code
 * setStatus}, so the response stays uncommitted), after which the problem body is written on top.
 * That keeps the RFC 6750 contract the extractor's OAuth client relies on while adding the body.
 *
 * <p>Unlike the backend's handler of the same name, no correlation id has to be minted here: the
 * gateway's {@code CorrelationIdFilter} runs <em>outside</em> the security chain, so the MDC is
 * already populated and the {@code X-Correlation-Id} response header already echoed by the time a
 * rejection happens. {@link ProblemResponseWriter} therefore picks the id straight out of the MDC,
 * and body, log line and header share it.
 *
 * <p>Log levels follow REQ-OBS-001: a {@code 401} is {@code DEBUG} — it is the expected answer for
 * every unauthenticated caller, and on an internet-facing surface scanners and probes would
 * otherwise flood the log — while a {@code 403} is {@code WARN}, because "authenticated but not
 * allowed" is the security-relevant case. Both are counted on {@code basetool_http_error_total}, so
 * the signal survives the demotion (REQ-OBS-011).
 */
@Slf4j
@RequiredArgsConstructor
public class SecurityProblemResponseHandler
    implements AuthenticationEntryPoint, AccessDeniedHandler {

  /** Emits the RFC 6750 {@code WWW-Authenticate} challenge before the problem body is written. */
  private final BearerTokenAuthenticationEntryPoint bearerEntryPoint =
      new BearerTokenAuthenticationEntryPoint();

  private final ObjectMapper objectMapper;
  private final MeterRegistry meterRegistry;

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
    // Sets WWW-Authenticate + the status only; it never writes a body, so ours still fits.
    bearerEntryPoint.commence(request, response, authException);
    // Exception class only: a token-decode message can quote parts of the presented JWT.
    log.debug(
        "Unauthenticated ingest request {} {} ({})",
        request.getMethod(),
        request.getRequestURI(),
        authException.getClass().getSimpleName());
    write(
        response,
        HttpStatus.UNAUTHORIZED,
        "Unauthenticated",
        MetricNames.CODE_UNAUTHENTICATED,
        "A valid bearer token is required.");
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
    // The acting subject is already in the `userId` MDC field, so it is not repeated here.
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
    ProblemResponseWriter.write(response, objectMapper, status, title, code, detail);
  }
}
