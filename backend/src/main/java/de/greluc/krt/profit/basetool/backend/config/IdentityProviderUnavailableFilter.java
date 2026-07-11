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
 * Translates an <em>identity-provider unreachable</em> failure into a retryable {@code 503 Service
 * Unavailable} instead of the opaque {@code 500} it produces by default (REQ-SEC-024).
 *
 * <p>Background: the backend is a JWT resource server. When Keycloak's JWKS endpoint is slow or
 * down, {@code NimbusJwtDecoder} fails the key fetch with a {@code JwtException}, which {@code
 * JwtAuthenticationProvider} wraps into an {@link AuthenticationServiceException}. Spring
 * Security's {@code AuthenticationEntryPointFailureHandler} deliberately <b>re-throws</b> {@code
 * AuthenticationServiceException} (it denotes a server-side error, not a credential failure), so it
 * escapes the bearer-token filter unhandled and Tomcat's error dispatch renders it as {@code 500
 * INTERNAL_ERROR} on <em>every</em> authenticated endpoint — a transient Keycloak blip thus looks
 * like an application crash and trips the {@code Http5xxRateHigh} alert.
 *
 * <p>This filter is installed <b>before</b> the {@code BearerTokenAuthenticationFilter} so its
 * {@code try/catch} wraps that filter's execution. It catches only {@link
 * AuthenticationServiceException} and only re-maps it to {@code 503} when the cause chain shows a
 * transport / 5xx failure ({@link IOException} — incl. socket/connect/unknown-host/closed-channel,
 * {@link UnresolvedAddressException} for a Docker-DNS strand, Spring's {@link
 * ResourceAccessException}, or an upstream {@link HttpStatusCodeException} with a 5xx status). A
 * genuine token rejection never reaches here — bad/expired tokens throw {@code BadJwtException} →
 * {@code InvalidBearerTokenException} → {@code 401} inside the entry point, untouched. Any {@code
 * AuthenticationServiceException} without a transport cause is re-thrown unchanged, preserving the
 * existing {@code 500} behaviour.
 *
 * <p>The {@code 503} carries a {@code Retry-After} header and the same RFC&nbsp;7807 {@code
 * application/problem+json} shape the rest of the API uses (mirrors {@code
 * PendingApprovalAccessFilter} and {@code BasetoolErrorController}), so the frontend's existing
 * {@code SERVICE_UNAVAILABLE} handling renders a "temporarily unavailable, retry" page rather than
 * a generic error. It is logged at {@code WARN} (not {@code ERROR}) and counted on {@code
 * basetool_http_error_total{code="SERVICE_UNAVAILABLE"}} so an identity-provider outage is
 * measurable without polluting the {@code logback_events_total} error-rate signal the {@code
 * LogbackErrorSpike} alert watches (REQ-OBS-011/-013).
 */
@Slf4j
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

  private final MessageSource messageSource;
  private final ProblemResponseFactory problemResponseFactory;
  private final ObjectMapper objectMapper;
  private final MeterRegistry meterRegistry;

  /**
   * Creates the filter with the collaborators needed to render and count a localized RFC-7807 503.
   *
   * @param messageSource resolves the localized {@code problem.service_unavailable.*} title/detail
   * @param problemResponseFactory assembles the RFC-7807 {@link ProblemDetail} body
   * @param objectMapper serializes the {@link ProblemDetail} with uniform JSON escaping
   * @param meterRegistry counts the re-mapped 503 on {@code basetool_http_error_total}
   */
  public IdentityProviderUnavailableFilter(
      @NotNull MessageSource messageSource,
      @NotNull ProblemResponseFactory problemResponseFactory,
      @NotNull ObjectMapper objectMapper,
      @NotNull MeterRegistry meterRegistry) {
    this.messageSource = messageSource;
    this.problemResponseFactory = problemResponseFactory;
    this.objectMapper = objectMapper;
    this.meterRegistry = meterRegistry;
  }

  /**
   * Runs the downstream chain and, on an {@link AuthenticationServiceException} whose cause chain
   * denotes an unreachable identity provider, short-circuits with a retryable 503 problem document;
   * every other exception (and every {@code AuthenticationServiceException} without a transport
   * cause) propagates unchanged so existing 401/403/500 semantics are preserved.
   *
   * @param request the current request
   * @param response the response to write the 503 into when the IdP is unreachable
   * @param filterChain the downstream chain (includes the bearer-token authentication filter)
   * @throws ServletException propagated from the downstream chain
   * @throws IOException propagated from the downstream chain or raised while writing the body
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
   * Walks the (bounded) cause chain and reports whether the authentication failure stems from a
   * transport-level or upstream-5xx problem talking to the identity provider — the signature of an
   * unreachable Keycloak / JWKS endpoint — as opposed to a programming error that also surfaced as
   * an {@link AuthenticationServiceException}.
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
   * Writes the retryable RFC-7807 503 body, sets {@code Retry-After}, mirrors the correlation id
   * and increments the error counter. Localizes from {@code request.getLocale()} because {@code
   * LocaleContextHolder} is not yet populated this early in the chain, and writes raw UTF-8 bytes
   * so German umlauts in the localized text survive (the servlet writer defaults to ISO-8859-1).
   *
   * @param request the failed request (its URI becomes the {@code instance})
   * @param response the response to populate
   * @param cause the classified failure, logged (class name only, at WARN) for diagnosis
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

    // WARN, not ERROR: an unreachable identity provider is an availability event, not an
    // application fault — keeping it out of ERROR avoids inflating the logback error-rate signal
    // (LogbackErrorSpike). Log the cause class only, never the message/stack (may carry a URL).
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
