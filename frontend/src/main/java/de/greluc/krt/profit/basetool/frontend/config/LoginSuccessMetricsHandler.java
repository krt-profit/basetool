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
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

/**
 * {@link AuthenticationSuccessHandler} decorator that counts a successful OAuth2 login into {@code
 * basetool_login_total{outcome="success"}} before delegating the actual post-login navigation to
 * the wrapped handler (in production the {@link AssetAwareAuthenticationSuccessHandler}).
 *
 * <p>Pairs with {@link LoginFailureMetricsHandler}: together they give the frontend a
 * success/failure signal so a code-to-token / JWKS / state failure that breaks <b>all</b> logins is
 * visible as failures-with-zero-successes ({@code FrontendLoginBroken}) rather than only inferable
 * from a Keycloak-side event regex that misses code-to-token errors (#1041 item 18, REQ-OBS-011).
 */
public class LoginSuccessMetricsHandler implements AuthenticationSuccessHandler {

  private final MeterRegistry meterRegistry;
  private final AuthenticationSuccessHandler delegate;

  /**
   * Wraps the real success handler with the success counter.
   *
   * @param meterRegistry the registry the {@code basetool_login_total} counter is bumped against
   * @param delegate the success handler that performs the actual post-login navigation
   */
  public LoginSuccessMetricsHandler(
      @NotNull MeterRegistry meterRegistry, @NotNull AuthenticationSuccessHandler delegate) {
    this.meterRegistry = meterRegistry;
    this.delegate = delegate;
  }

  /**
   * Counts the successful login, then delegates the redirect/navigation unchanged.
   *
   * @param request the current HTTP request
   * @param response the HTTP response the delegate may redirect
   * @param authentication the successful authentication
   * @throws IOException if the delegate fails to write the response
   * @throws ServletException if the delegate raises a servlet-layer exception
   */
  @Override
  public void onAuthenticationSuccess(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull Authentication authentication)
      throws IOException, ServletException {
    meterRegistry
        .counter(
            MetricNames.LOGIN,
            MetricNames.TAG_OUTCOME,
            MetricNames.OUTCOME_SUCCESS,
            MetricNames.TAG_REASON,
            MetricNames.LOGIN_REASON_NONE)
        .increment();
    delegate.onAuthenticationSuccess(request, response, authentication);
  }
}
