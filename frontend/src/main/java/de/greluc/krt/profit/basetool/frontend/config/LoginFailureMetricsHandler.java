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

package de.greluc.krt.profit.basetool.frontend.config;

import de.greluc.krt.profit.basetool.frontend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.logging.LogSafe;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.WebAttributes;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;

/**
 * OAuth2 login failure handler that counts each failure into {@code
 * basetool_login_total{outcome="failure", reason=…}}, logs it once, and redirects to the failure
 * URL (REQ-OBS-011).
 *
 * <p>The {@code reason} is {@code invalid_state} for benign authorization-response failures (see
 * {@link #isStateError(String)}), {@code provider_error} for failures after authorization, and
 * {@code other} for non-OAuth2 exceptions. It never uses the provider's error description. Pairs
 * with {@link LoginSuccessMetricsHandler}.
 */
@Slf4j
public class LoginFailureMetricsHandler extends SimpleUrlAuthenticationFailureHandler {

  /**
   * OAuth2 / OIDC error codes raised at the authorization-response stage, before any token
   * exchange, which count as {@code invalid_state}.
   */
  private static final Set<String> BENIGN_AUTHORIZATION_RESPONSE_ERRORS =
      Set.of(
          "authorization_request_not_found",
          "invalid_state_parameter",
          "invalid_state",
          "invalid_request",
          "login_required",
          "interaction_required",
          "consent_required",
          "account_selection_required");

  /**
   * Character budget for the logged OAuth2 error code. The code is bounded <em>in practice</em> (it
   * comes from the IdP), but on the authorization-response path it reaches us as the {@code error}
   * query parameter of the callback, i.e. from the browser — so it is length-capped and stripped of
   * control characters before it is logged, exactly like any other request-supplied value.
   */
  private static final int MAX_LOGGED_ERROR_CODE = 64;

  /**
   * Hop limit for the root-cause walk, so a self-referential or pathologically deep cause chain
   * cannot spin the request thread.
   */
  private static final int MAX_CAUSE_DEPTH = 16;

  private final MeterRegistry meterRegistry;

  /**
   * The redirect target, kept here because the superclass's own copy is private and its {@code
   * onAuthenticationFailure} — the only thing that reads it — is what this class replaces.
   */
  private final String failureUrl;

  /**
   * Builds the handler with the failure redirect target and the counter registry.
   *
   * @param meterRegistry the registry the {@code basetool_login_total} counter is bumped against
   * @param failureUrl the URL the user is redirected to after a failed login (e.g. {@code /?error})
   */
  public LoginFailureMetricsHandler(
      @NotNull MeterRegistry meterRegistry, @NotNull String failureUrl) {
    super(failureUrl);
    this.meterRegistry = meterRegistry;
    this.failureUrl = failureUrl;
  }

  /**
   * Counts the failed login under its mapped {@code reason}, logs it once at the level that bucket
   * warrants, then delegates to the default redirect-to-failure-URL behaviour.
   *
   * @param request the current HTTP request
   * @param response the HTTP response to redirect
   * @param exception the authentication failure that occurred
   * @throws IOException if writing the redirect fails
   * @throws ServletException if the superclass raises a servlet-layer exception
   */
  @Override
  public void onAuthenticationFailure(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull AuthenticationException exception)
      throws IOException, ServletException {
    String reason = reasonFor(exception);
    meterRegistry
        .counter(
            MetricNames.LOGIN,
            MetricNames.TAG_OUTCOME,
            MetricNames.OUTCOME_FAILURE,
            MetricNames.TAG_REASON,
            reason)
        .increment();
    logFailure(reason, exception);

    redirectWithoutPoisoningTheSession(request, response, exception);
  }

  /**
   * Performs the failure redirect without storing the exception in the session.
   *
   * <p>The exception is not deserializable from the JSON Redis session and would break every later
   * request of that session. It is kept as a request attribute only.
   *
   * @param request the current request.
   * @param response the response to redirect.
   * @param exception the failure, kept for this request only.
   * @throws IOException if writing the redirect fails.
   */
  private void redirectWithoutPoisoningTheSession(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull AuthenticationException exception)
      throws IOException {
    request.setAttribute(WebAttributes.AUTHENTICATION_EXCEPTION, exception);
    getRedirectStrategy().sendRedirect(request, response, failureUrl);
  }

  /**
   * Writes the single log line for this failure: WARN for {@link
   * MetricNames#LOGIN_REASON_PROVIDER_ERROR}, DEBUG for {@link
   * MetricNames#LOGIN_REASON_INVALID_STATE} and {@link MetricNames#LOGIN_REASON_OTHER}
   * (REQ-OBS-001).
   *
   * <p>Logs only the sanitised OAuth2 error code and the root cause's class name; never the error
   * description, request parameters, tokens or the principal. A WARN burst with {@code
   * access_denied} can be caller-driven and does not by itself mean login is broken.
   *
   * @param reason the bucket {@link #reasonFor(AuthenticationException)} mapped this failure to
   * @param exception the authentication failure being reported
   */
  private static void logFailure(
      @NotNull String reason, @NotNull AuthenticationException exception) {
    String errorCode = errorCodeOf(exception);
    String rootCause = rootCauseType(exception);
    if (MetricNames.LOGIN_REASON_PROVIDER_ERROR.equals(reason)) {
      log.warn(
          "OAuth2 login failed: reason={}, oauth2ErrorCode={}, rootCause={}",
          reason,
          errorCode,
          rootCause);
      return;
    }
    log.debug(
        "OAuth2 login failed: reason={}, oauth2ErrorCode={}, rootCause={}",
        reason,
        errorCode,
        rootCause);
  }

  /**
   * Renders the OAuth2 error code of the failure for the log line, or {@link LogSafe#NONE} when the
   * failure is not an {@link OAuth2AuthenticationException} or carries no {@link
   * org.springframework.security.oauth2.core.OAuth2Error}. Never touches the error description.
   *
   * @param exception the authentication failure being reported
   * @return the sanitised, length-capped error code, or {@code none}
   */
  @NotNull
  private static String errorCodeOf(@NotNull AuthenticationException exception) {
    if (exception instanceof OAuth2AuthenticationException oauth2 && oauth2.getError() != null) {
      return LogSafe.text(oauth2.getError().getErrorCode(), MAX_LOGGED_ERROR_CODE);
    }
    return LogSafe.NONE;
  }

  /**
   * Returns the class simple name of the deepest cause in the chain, which distinguishes an error
   * answer from an unreachable Keycloak. The walk is hop-bounded and cycle-safe; the message is
   * never used.
   *
   * @param exception the authentication failure being reported
   * @return the simple class name of the deepest cause, or of {@code exception} itself when it has
   *     none
   */
  @NotNull
  private static String rootCauseType(@NotNull Throwable exception) {
    Throwable current = exception;
    for (int hop = 0; hop < MAX_CAUSE_DEPTH; hop++) {
      Throwable cause = current.getCause();
      if (cause == null || cause == current) {
        break;
      }
      current = cause;
    }
    return current.getClass().getSimpleName();
  }

  /**
   * Maps an authentication exception to one of the three bounded failure reasons. Only the OAuth2
   * error <b>code</b> is inspected (a bounded value), never the free-text description.
   *
   * @param exception the authentication failure
   * @return {@link MetricNames#LOGIN_REASON_INVALID_STATE}, {@link
   *     MetricNames#LOGIN_REASON_PROVIDER_ERROR} or {@link MetricNames#LOGIN_REASON_OTHER}
   */
  static String reasonFor(@NotNull AuthenticationException exception) {
    if (exception instanceof OAuth2AuthenticationException oauth2) {
      String code = oauth2.getError() != null ? oauth2.getError().getErrorCode() : null;
      return isStateError(code)
          ? MetricNames.LOGIN_REASON_INVALID_STATE
          : MetricNames.LOGIN_REASON_PROVIDER_ERROR;
    }
    return MetricNames.LOGIN_REASON_OTHER;
  }

  /**
   * Recognises OAuth2 error codes raised before any code-to-token exchange, which must not count as
   * {@code provider_error}.
   *
   * <p>These are the state-correlation failures ({@code authorization_request_not_found}, {@code
   * invalid_state_parameter}, {@code invalid_state}), a malformed callback ({@code
   * invalid_request}), and the {@code prompt=none} errors ({@code login_required}, {@code
   * interaction_required}, {@code consent_required}, {@code account_selection_required}). {@code
   * access_denied} is deliberately excluded.
   *
   * @param code the OAuth2 error code, or {@code null}
   * @return {@code true} when the code denotes a benign authorization-response / state failure
   */
  private static boolean isStateError(@Nullable String code) {
    return code != null && BENIGN_AUTHORIZATION_RESPONSE_ERRORS.contains(code);
  }
}
