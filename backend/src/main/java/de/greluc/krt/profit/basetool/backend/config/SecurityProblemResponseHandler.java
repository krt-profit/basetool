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
 * Renders Spring Security's filter-level {@code 401}/{@code 403} rejections as RFC&nbsp;7807
 * problem bodies by handing them to {@code GlobalExceptionHandler} (REQ-API-004).
 *
 * <p>Serves as both {@link AuthenticationEntryPoint} and {@link AccessDeniedHandler}. While writing
 * a rejection it stamps a correlation id and the authenticated {@code sub} into the MDC when absent
 * (never the callsign or e-mail, REQ-OBS-004), and uses the {@link SecurityContextHolder} for the
 * subject.
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
   * Creates the handler.
   *
   * @param resolver the {@code handlerExceptionResolver} composite that fronts {@code
   *     GlobalExceptionHandler}
   * @param meterRegistry carries {@code basetool_auth_failures_total}
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
   * Maps an authentication failure onto a bounded metric value: its RFC 6750 bearer error code,
   * {@link MetricNames#AUTH_NO_CREDENTIALS} when no credential was presented, else {@link
   * MetricNames#AUTH_OTHER}.
   *
   * <p>Never reads the error description, which can quote token fragments (REQ-OBS-004). The
   * no-credential case covers {@link InsufficientAuthenticationException} and {@link
   * AuthenticationCredentialsNotFoundException}; a non-{@link OAuth2AuthenticationException}
   * failure never yields {@link MetricNames#AUTH_INVALID_TOKEN}.
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
   * Hands {@code ex} to the MVC exception resolver so the matching {@code @ExceptionHandler} writes
   * the problem body, falling back to {@code sendError} when no handler matched or the response is
   * committed.
   *
   * <p>Sets the {@code correlationId} and {@code userId} MDC keys when absent and removes exactly
   * the keys it added. Both {@link AuthenticationException} and {@link AccessDeniedException} are
   * expected.
   *
   * @param request the rejected request
   * @param response the response to write into
   * @param ex the security exception to map
   * @param fallbackStatus the status for the fallback {@code sendError}
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
   * Puts the authenticated caller's subject into the {@code userId} MDC key, unless the key is
   * already set or the caller has no readable subject.
   *
   * <p>A token-less acting member (ADR-0129) is stamped; a username/password caller is not, because
   * its name is a callsign (REQ-OBS-004).
   *
   * @return {@code true} when this call stamped the key and must remove it again
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
