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
import de.greluc.krt.profit.basetool.backend.support.AuthenticatedSubject;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Routes Spring Security's filter-level {@code 401}/{@code 403} rejections through the same {@code
 * GlobalExceptionHandler} the rest of the API uses, so they carry an RFC&nbsp;7807 {@code
 * application/problem+json} body with a stable {@code code} and a {@code correlationId} instead of
 * Spring's default bare {@code WWW-Authenticate}-only 401 / empty-body 403 (RFC-7807 hardening,
 * REQ-API-004 / REQ-SEC).
 *
 * <p>A missing/invalid bearer token or an access-denied verdict raised inside the security filter
 * chain never reaches the {@code DispatcherServlet}, so the {@code @ControllerAdvice} handler would
 * otherwise not see it. Wired as both the {@link AuthenticationEntryPoint} and the {@link
 * AccessDeniedHandler} (globally via {@code HttpSecurity.exceptionHandling(...)} for the
 * no-token/anonymous case and on the resource server for the bearer-token case), this component
 * hands the exception to the MVC {@code handlerExceptionResolver}. That resolver dispatches it to
 * {@code GlobalExceptionHandler.handleAuthentication} (401, code {@code UNAUTHENTICATED}) or {@code
 * handleAccessDenied} (403, code {@code ACCESS_DENIED}), reusing the exact problem-body shape,
 * i18n, {@code correlationId} minting and structured WARN logging already contracted there — no
 * duplicated body-building.
 *
 * <p>Because the security chain runs before {@code CorrelationIdFilter}, no request-scoped
 * correlation id exists yet; {@code GlobalExceptionHandler} mints a fresh one for the body and the
 * log line, so a client-reported 401/403 is still traceable to a single server log entry.
 *
 * <p>The same ordering also leaves the {@code userId} MDC key unset, and {@code
 * CorrelationIdFilter} is its only writer in the backend. A filter-level 403 (URL-matrix denial)
 * would therefore render as the logback pattern's {@code anonymous} default even though the {@link
 * SecurityContextHolder} already holds the caller's authentication — while a controller-thrown
 * {@code AccessDeniedException} renders the real {@code sub} in a byte-identical line. That makes
 * the value actively misleading, so {@link #delegate} stamps the {@code sub} for the duration of
 * the rejection write using the same own-then-remove discipline as the correlation id. It stays
 * unset when there is no authenticated subject, so a genuine anonymous 401 still reads {@code
 * anonymous} truthfully. Only the {@code sub} is stamped, never the callsign or e-mail
 * (REQ-OBS-004).
 */
@Slf4j
@Component
public class SecurityProblemResponseHandler
    implements AuthenticationEntryPoint, AccessDeniedHandler {

  /** SLF4J MDC key the correlation-id filter uses; also read by {@code GlobalExceptionHandler}. */
  private static final String MDC_CORRELATION_ID = "correlationId";

  /**
   * SLF4J MDC key the logback pattern renders as {@code [<userId>]}, mirroring {@code
   * LoggingProperties}' default. Hardcoded rather than injected for the same reason {@link
   * #MDC_CORRELATION_ID} is: it is a wire constant shared with the logback pattern, not a
   * per-deployment override.
   */
  private static final String MDC_USER_ID = "userId";

  /** App-wide correlation-id response header, mirroring {@code LoggingProperties} default. */
  private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

  private final HandlerExceptionResolver resolver;
  private final MeterRegistry meterRegistry;

  /**
   * Injects the composite MVC exception resolver that fronts the {@code @ControllerAdvice} handler
   * methods, and the registry the bearer-error breakdown is counted on.
   *
   * @param resolver the {@code handlerExceptionResolver} bean (the {@code
   *     HandlerExceptionResolverComposite} that includes the {@code
   *     ExceptionHandlerExceptionResolver} processing {@code GlobalExceptionHandler}); qualified by
   *     name because several {@link HandlerExceptionResolver} beans exist in the context
   * @param meterRegistry carries {@code basetool_auth_failures_total} (A8, REQ-OBS-018)
   */
  public SecurityProblemResponseHandler(
      @Qualifier("handlerExceptionResolver") @NotNull HandlerExceptionResolver resolver,
      @NotNull MeterRegistry meterRegistry) {
    this.resolver = resolver;
    this.meterRegistry = meterRegistry;
  }

  /**
   * Entry point for an unauthenticated request to a protected endpoint (no token, or a token the
   * resource server rejected): renders the {@code 401} problem body by delegating {@code
   * authException} to {@code GlobalExceptionHandler.handleAuthentication}.
   *
   * @param request the rejected request
   * @param response the response to write the problem body into
   * @param authException the authentication failure Spring Security raised
   * @throws IOException if writing the fallback container error fails
   */
  @Override
  public void commence(
      HttpServletRequest request,
      HttpServletResponse response,
      AuthenticationException authException)
      throws IOException {
    meterRegistry
        .counter(MetricNames.AUTH_FAILURES, MetricNames.TAG_REASON, bearerErrorCode(authException))
        .increment();
    delegate(request, response, authException, HttpServletResponse.SC_UNAUTHORIZED);
  }

  /**
   * Maps an authentication failure onto its RFC 6750 bearer error code, kept to the fixed set the
   * spec defines so the metric label stays bounded (REQ-OBS-006), with one deliberate addition
   * below the RFC set for the request that presented no credential at all.
   *
   * <p>Only the code is taken, never {@code OAuth2Error#getDescription()}: Spring puts the raw
   * decode failure in there ("An error occurred while attempting to decode the Jwt: …"), which can
   * quote fragments of the presented token and must never reach a label or an appender
   * (REQ-OBS-004).
   *
   * <p><b>The no-credential case is the common one and has no RFC code.</b> A request with no
   * {@code Authorization} header never reaches {@code BearerTokenAuthenticationFilter}'s failure
   * path; {@code ExceptionTranslationFilter} raises a plain {@link
   * InsufficientAuthenticationException} instead, and method security raises {@link
   * AuthenticationCredentialsNotFoundException}. Neither is an {@link
   * OAuth2AuthenticationException} and RFC 6750 §3.1 says to omit the error code entirely for them,
   * so both once collapsed into {@link MetricNames#AUTH_OTHER} — which on production meant
   * <em>every single</em> 401 did: 6&nbsp;618 of 6&nbsp;618 on 2026-09-13, 8.5&nbsp;% of all
   * backend traffic, with the counter that exists to answer "why" answering nothing. They now carry
   * {@link MetricNames#AUTH_NO_CREDENTIALS}, which leaves {@link MetricNames#AUTH_INVALID_TOKEN} —
   * a token that was presented and rejected — as a series quiet enough to alert on (REQ-OBS-018).
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
    OAuth2Error error = oauth2Exception.getError();
    String code = error == null ? null : error.getErrorCode();
    if (MetricNames.AUTH_INVALID_TOKEN.equals(code)
        || MetricNames.AUTH_INVALID_REQUEST.equals(code)
        || MetricNames.AUTH_INSUFFICIENT_SCOPE.equals(code)) {
      return code;
    }
    return MetricNames.AUTH_OTHER;
  }

  /**
   * Access-denied handler for an authenticated caller lacking the required authority at the filter
   * level: renders the {@code 403} problem body by delegating {@code accessDeniedException} to
   * {@code GlobalExceptionHandler.handleAccessDenied}.
   *
   * @param request the rejected request
   * @param response the response to write the problem body into
   * @param accessDeniedException the authorization failure Spring Security raised
   * @throws IOException if writing the fallback container error fails
   */
  @Override
  public void handle(
      HttpServletRequest request,
      HttpServletResponse response,
      AccessDeniedException accessDeniedException)
      throws IOException {
    delegate(request, response, accessDeniedException, HttpServletResponse.SC_FORBIDDEN);
  }

  /**
   * Hands {@code ex} to the MVC exception resolver so the matching {@code @ExceptionHandler}
   * produces the problem body, falling back to a plain {@code sendError} only if no handler matched
   * (never expected: {@code GlobalExceptionHandler} covers both {@link AuthenticationException} and
   * {@link AccessDeniedException}) or the response is already committed.
   *
   * <p>Owns the {@code correlationId} and {@code userId} MDC keys for the duration of the write —
   * minting the former and stamping the JWT {@code sub} into the latter when nothing populated them
   * yet — and removes exactly the keys it added, so nothing bleeds into the next request on a
   * pooled or virtual thread.
   *
   * @param request the rejected request
   * @param response the response to write into
   * @param ex the security exception to map to a problem response
   * @param fallbackStatus the status to {@code sendError} with if the resolver does not handle it
   * @throws IOException if the fallback {@code sendError} fails
   */
  private void delegate(
      HttpServletRequest request, HttpServletResponse response, Exception ex, int fallbackStatus)
      throws IOException {
    if (response.isCommitted()) {
      return;
    }
    boolean mdcOwned = false;
    String correlationId = MDC.get(MDC_CORRELATION_ID);
    if (correlationId == null || correlationId.isBlank()) {
      correlationId = UUID.randomUUID().toString();
      MDC.put(MDC_CORRELATION_ID, correlationId);
      mdcOwned = true;
    }
    boolean userIdOwned = stampAuthenticatedSub();
    try {
      response.setHeader(CORRELATION_ID_HEADER, correlationId);
      if (resolver.resolveException(request, response, null, ex) == null) {
        log.warn(
            "No problem+json mapping for {} on {} {}; falling back to sendError({})",
            ex.getClass().getSimpleName(),
            request.getMethod(),
            request.getRequestURI(),
            fallbackStatus);
        response.sendError(fallbackStatus);
      }
    } finally {
      if (mdcOwned) {
        MDC.remove(MDC_CORRELATION_ID);
      }
      if (userIdOwned) {
        MDC.remove(MDC_USER_ID);
      }
    }
  }

  /**
   * Puts the authenticated caller's subject into the {@code userId} MDC key, unless something
   * already populated that key (then the existing value wins, exactly as the correlation id above)
   * or the caller has no readable subject (then the key stays unset so the logback pattern's {@code
   * anonymous} default is the truth rather than a cover-up).
   *
   * <p>A token-less acting member (ADR-0129) has a readable subject and is stamped, so a refusal of
   * one is attributable; a username/password caller is not, because its name is a callsign that
   * REQ-OBS-004 keeps out of the log.
   *
   * <p>Deliberately duplicated in {@code PendingApprovalAccessFilter} instead of extracted into the
   * {@code logging} package: {@code logging.CorrelationIdFilter} already depends on {@code
   * config.LoggingProperties}, so a {@code config -> logging} helper call would close a package
   * cycle (ADR-0047).
   *
   * @return {@code true} when this call stamped the key and must therefore remove it again, {@code
   *     false} when nothing was stamped
   */
  private static boolean stampAuthenticatedSub() {
    String existing = MDC.get(MDC_USER_ID);
    if (existing != null && !existing.isBlank()) {
      return false;
    }
    String sub =
        AuthenticatedSubject.of(SecurityContextHolder.getContext().getAuthentication())
            .orElse(null);
    if (sub == null) {
      return false;
    }
    MDC.put(MDC_USER_ID, sub);
    return true;
  }
}
