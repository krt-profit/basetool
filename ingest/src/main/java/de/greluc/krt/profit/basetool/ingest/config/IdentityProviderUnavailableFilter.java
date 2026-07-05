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
 * Translates an <em>identity-provider unreachable</em> failure into a retryable {@code 503 Service
 * Unavailable} instead of the opaque {@code 500} it produces by default (REQ-SEC-024). Module-local
 * twin of the backend filter of the same name (the ingest gateway is a separate module); it uses
 * the gateway's own {@link ProblemResponseWriter} (no i18n) rather than the backend's
 * message-source variant.
 *
 * <p>The gateway is a JWT resource server: when Keycloak's JWKS endpoint is slow or down, {@code
 * NimbusJwtDecoder} fails the key fetch, {@code JwtAuthenticationProvider} wraps it into an {@link
 * AuthenticationServiceException}, and Spring Security's {@code
 * AuthenticationEntryPointFailureHandler} re-throws it (a server-side error, not a credential
 * failure), so it escapes the bearer-token filter unhandled and Tomcat renders {@code 500} on every
 * authenticated {@code /v1/**} call. Installed before the bearer-token filter, this filter catches
 * that {@link AuthenticationServiceException} and, only when the cause chain shows a transport /
 * upstream-5xx failure ({@link IOException} — incl. socket/connect/unknown-host/closed-channel,
 * {@link UnresolvedAddressException} for a Docker-DNS strand, {@link ResourceAccessException}, or
 * an upstream {@link HttpStatusCodeException} 5xx), re-maps it to a retryable {@code 503} (RFC-7807
 * problem+json, {@code Retry-After}, code {@code SERVICE_UNAVAILABLE}). It is WARN-logged and
 * counted on {@code basetool_http_error_total{code="SERVICE_UNAVAILABLE"}} so a Keycloak blip does
 * not masquerade as an application error (REQ-OBS-011/-013). A genuine token rejection never
 * reaches here (bad tokens → {@code 401} inside the entry point); any other cause is re-thrown
 * unchanged, preserving the {@code 500} behaviour.
 */
@Slf4j
@RequiredArgsConstructor
public class IdentityProviderUnavailableFilter extends OncePerRequestFilter {

  /** {@code Retry-After} value (seconds) advertised to the client for a transient IdP outage. */
  private static final String RETRY_AFTER_SECONDS = "5";

  /** Bounded depth for the cause-chain walk — guards against a self-referential cause cycle. */
  private static final int MAX_CAUSE_DEPTH = 12;

  private final ObjectMapper objectMapper;
  private final MeterRegistry meterRegistry;

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
   * Walks the (bounded) cause chain and reports whether the authentication failure stems from a
   * transport-level or upstream-5xx problem talking to the identity provider — the signature of an
   * unreachable Keycloak / JWKS endpoint — rather than a programming error that also surfaced as an
   * {@link AuthenticationServiceException}.
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
   * Counts the event, sets {@code Retry-After}, WARN-logs (cause class only — never the message or
   * stack, which may carry a URL) and writes the retryable RFC-7807 503 through {@link
   * ProblemResponseWriter}.
   *
   * @param request the failed request (used only for the diagnostic log line)
   * @param response the response to populate
   * @param cause the classified failure, logged by class name for diagnosis
   * @throws IOException if writing the body fails
   */
  private void writeServiceUnavailable(
      HttpServletRequest request, HttpServletResponse response, Throwable cause)
      throws IOException {
    meterRegistry
        .counter(MetricNames.HTTP_ERROR, MetricNames.TAG_CODE, MetricNames.CODE_SERVICE_UNAVAILABLE)
        .increment();
    // WARN, not ERROR: an unreachable identity provider is an availability event, not an
    // application
    // fault — keeping it out of ERROR avoids inflating the logback error-rate signal.
    log.warn(
        "Identity provider unreachable for {} {} [cause={}] — returning 503",
        request.getMethod(),
        request.getRequestURI(),
        cause.getClass().getSimpleName());
    response.setHeader(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
    ProblemResponseWriter.write(
        response,
        objectMapper,
        HttpStatus.SERVICE_UNAVAILABLE,
        "Service unavailable",
        MetricNames.CODE_SERVICE_UNAVAILABLE,
        "The authentication service is temporarily unreachable. Please retry shortly.");
  }
}
