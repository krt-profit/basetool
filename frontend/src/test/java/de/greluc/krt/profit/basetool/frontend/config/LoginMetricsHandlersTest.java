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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.metrics.MetricNames;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

/**
 * Unit tests for the login-outcome metric handlers ({@link LoginSuccessMetricsHandler} / {@link
 * LoginFailureMetricsHandler}, #1041 item 18): the success/failure counters must be bumped with the
 * right bounded tags and the wrapped navigation/redirect must still run. The failure-reason mapping
 * is exercised directly to pin that only the bounded OAuth2 error code — not the raw description —
 * decides the bucket.
 */
class LoginMetricsHandlersTest {

  /**
   * Reads the {@code basetool_login_total} value for one {@code (outcome, reason)} pair.
   *
   * @param registry the registry under assertion
   * @param outcome the outcome tag value
   * @param reason the reason tag value
   * @return the counter value, or {@code 0.0} when the series is absent
   */
  private static double loginCount(SimpleMeterRegistry registry, String outcome, String reason) {
    var counter =
        registry
            .find(MetricNames.LOGIN)
            .tag(MetricNames.TAG_OUTCOME, outcome)
            .tag(MetricNames.TAG_REASON, reason)
            .counter();
    return counter == null ? 0.0 : counter.count();
  }

  @Test
  void reasonFor_mapsAuthorizationRequestAndStateErrorsToInvalidState() {
    assertThat(LoginFailureMetricsHandler.reasonFor(oauth2("authorization_request_not_found")))
        .isEqualTo(MetricNames.LOGIN_REASON_INVALID_STATE);
    assertThat(LoginFailureMetricsHandler.reasonFor(oauth2("invalid_state_parameter")))
        .isEqualTo(MetricNames.LOGIN_REASON_INVALID_STATE);
  }

  @Test
  void reasonFor_mapsOtherOAuth2ErrorsToProviderError() {
    assertThat(LoginFailureMetricsHandler.reasonFor(oauth2("invalid_grant")))
        .isEqualTo(MetricNames.LOGIN_REASON_PROVIDER_ERROR);
    assertThat(LoginFailureMetricsHandler.reasonFor(oauth2("server_error")))
        .isEqualTo(MetricNames.LOGIN_REASON_PROVIDER_ERROR);
  }

  @Test
  void reasonFor_mapsNonOAuth2ExceptionsToOther() {
    assertThat(LoginFailureMetricsHandler.reasonFor(new BadCredentialsException("nope")))
        .isEqualTo(MetricNames.LOGIN_REASON_OTHER);
  }

  @Test
  void onAuthenticationFailure_countsFailureWithMappedReason_andRedirects() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    LoginFailureMetricsHandler handler = new LoginFailureMetricsHandler(registry, "/?error");
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(request.getContextPath()).thenReturn("");
    when(request.getSession()).thenReturn(mock(HttpSession.class));
    when(response.encodeRedirectURL(anyString())).thenAnswer(inv -> inv.getArgument(0));

    handler.onAuthenticationFailure(request, response, oauth2("invalid_grant"));

    assertThat(
            loginCount(
                registry, MetricNames.OUTCOME_FAILURE, MetricNames.LOGIN_REASON_PROVIDER_ERROR))
        .isEqualTo(1.0);
    verify(response).sendRedirect("/?error");
  }

  @Test
  void onAuthenticationSuccess_countsSuccess_andDelegates() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    AuthenticationSuccessHandler delegate = mock(AuthenticationSuccessHandler.class);
    LoginSuccessMetricsHandler handler = new LoginSuccessMetricsHandler(registry, delegate);
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    Authentication authentication = mock(Authentication.class);

    handler.onAuthenticationSuccess(request, response, authentication);

    assertThat(loginCount(registry, MetricNames.OUTCOME_SUCCESS, MetricNames.LOGIN_REASON_NONE))
        .isEqualTo(1.0);
    verify(delegate).onAuthenticationSuccess(request, response, authentication);
  }

  private static OAuth2AuthenticationException oauth2(String errorCode) {
    return new OAuth2AuthenticationException(new OAuth2Error(errorCode));
  }
}
