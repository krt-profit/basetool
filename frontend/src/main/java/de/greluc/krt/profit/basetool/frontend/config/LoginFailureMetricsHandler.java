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
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;

/**
 * OAuth2 login failure handler that counts every failed login into {@code
 * basetool_login_total{outcome="failure", reason=…}} before performing the usual redirect to the
 * configured failure URL ({@code /?error}).
 *
 * <p>The {@code reason} tag is derived from the exception <b>type</b> and, for an {@link
 * OAuth2AuthenticationException}, its bounded OAuth2 error <b>code</b> — never the raw, provider-
 * supplied error description (which could be arbitrary and would blow up the metric cardinality).
 * It collapses to three buckets: {@code invalid_state} (the authorization-request / state check
 * failed), {@code provider_error} (any other OAuth2 error — a bad IdP response or a failed
 * code-to-token exchange, the failure class {@code KeycloakLoginErrorSpike}'s event regex misses)
 * and {@code other} (a non-OAuth2 authentication exception). See {@link LoginSuccessMetricsHandler}
 * for the paired success signal (#1041 item 18, REQ-OBS-011).
 */
public class LoginFailureMetricsHandler extends SimpleUrlAuthenticationFailureHandler {

  private final MeterRegistry meterRegistry;

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
  }

  /**
   * Counts the failed login under its mapped {@code reason}, then delegates to the default
   * redirect-to-failure-URL behaviour.
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
    meterRegistry
        .counter(
            MetricNames.LOGIN,
            MetricNames.TAG_OUTCOME,
            MetricNames.OUTCOME_FAILURE,
            MetricNames.TAG_REASON,
            reasonFor(exception))
        .increment();
    super.onAuthenticationFailure(request, response, exception);
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
   * Recognises the OAuth2 error codes Spring Security raises when the authorization request cannot
   * be correlated to the callback — i.e. the {@code state} check failed or the saved request was
   * lost. Anything else maps to {@code provider_error}.
   *
   * @param code the OAuth2 error code, or {@code null}
   * @return {@code true} when the code denotes an authorization-request / state failure
   */
  private static boolean isStateError(@Nullable String code) {
    return "authorization_request_not_found".equals(code)
        || "invalid_state_parameter".equals(code)
        || "invalid_state".equals(code);
  }
}
