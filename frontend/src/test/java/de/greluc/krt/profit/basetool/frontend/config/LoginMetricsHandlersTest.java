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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.greluc.krt.profit.basetool.frontend.metrics.MetricNames;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.net.ConnectException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

/**
 * Unit tests for {@link LoginSuccessMetricsHandler} and {@link LoginFailureMetricsHandler}: the
 * counters carry the right bounded tags, the wrapped redirect still runs, and only the OAuth2 error
 * code decides the failure reason.
 */
class LoginMetricsHandlersTest {

  private ListAppender<ILoggingEvent> appender;
  private Logger failureLogger;

  @BeforeEach
  void attachAppender() {
    failureLogger = (Logger) LoggerFactory.getLogger(LoginFailureMetricsHandler.class);
    appender = new ListAppender<>();
    appender.start();
    failureLogger.addAppender(appender);
    failureLogger.setLevel(Level.DEBUG);
  }

  @AfterEach
  void detachAppender() {
    failureLogger.detachAppender(appender);
  }

  /**
   * Drives one failure through the handler with a stubbed request and response.
   *
   * @param exception the failure to report
   * @return the registry the counter was bumped against
   * @throws Exception if the handler's redirect fails
   */
  private static SimpleMeterRegistry handleFailure(AuthenticationException exception)
      throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    LoginFailureMetricsHandler handler = new LoginFailureMetricsHandler(registry, "/?error");
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(request.getContextPath()).thenReturn("");
    when(request.getSession()).thenReturn(mock(HttpSession.class));
    when(response.encodeRedirectURL(anyString())).thenAnswer(inv -> inv.getArgument(0));
    handler.onAuthenticationFailure(request, response, exception);
    return registry;
  }

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
    assertThat(LoginFailureMetricsHandler.reasonFor(oauth2("invalid_request")))
        .isEqualTo(MetricNames.LOGIN_REASON_INVALID_STATE);
  }

  /**
   * Verifies that the OIDC {@code prompt=none} errors, such as {@code login_required}, land in the
   * benign bucket rather than {@code provider_error}.
   */
  @Test
  void reasonFor_mapsPromptNoneSilentSsoErrorsToInvalidState() {
    assertThat(LoginFailureMetricsHandler.reasonFor(oauth2("login_required")))
        .isEqualTo(MetricNames.LOGIN_REASON_INVALID_STATE);
    assertThat(LoginFailureMetricsHandler.reasonFor(oauth2("interaction_required")))
        .isEqualTo(MetricNames.LOGIN_REASON_INVALID_STATE);
    assertThat(LoginFailureMetricsHandler.reasonFor(oauth2("consent_required")))
        .isEqualTo(MetricNames.LOGIN_REASON_INVALID_STATE);
    assertThat(LoginFailureMetricsHandler.reasonFor(oauth2("account_selection_required")))
        .isEqualTo(MetricNames.LOGIN_REASON_INVALID_STATE);
  }

  @Test
  void reasonFor_mapsOtherOAuth2ErrorsToProviderError() {
    assertThat(LoginFailureMetricsHandler.reasonFor(oauth2("invalid_grant")))
        .isEqualTo(MetricNames.LOGIN_REASON_PROVIDER_ERROR);
    assertThat(LoginFailureMetricsHandler.reasonFor(oauth2("server_error")))
        .isEqualTo(MetricNames.LOGIN_REASON_PROVIDER_ERROR);
    assertThat(LoginFailureMetricsHandler.reasonFor(oauth2("invalid_token_response")))
        .isEqualTo(MetricNames.LOGIN_REASON_PROVIDER_ERROR);
    assertThat(LoginFailureMetricsHandler.reasonFor(oauth2("access_denied")))
        .isEqualTo(MetricNames.LOGIN_REASON_PROVIDER_ERROR);
  }

  /**
   * Verifies that an {@link OAuth2AuthenticationException} without an {@link OAuth2Error} maps to
   * {@code provider_error} without throwing.
   */
  @Test
  void reasonFor_mapsMissingErrorToProviderErrorWithoutThrowing() {
    OAuth2AuthenticationException noError = mock(OAuth2AuthenticationException.class);
    when(noError.getError()).thenReturn(null);

    assertThat(LoginFailureMetricsHandler.reasonFor(noError))
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

  /**
   * Verifies that a {@code provider_error} failure is logged at WARN with the OAuth2 error code and
   * the root cause's type.
   */
  @Test
  void onAuthenticationFailure_logsProviderErrorAtWarnWithCodeAndRootCause() throws Exception {
    handleFailure(
        new OAuth2AuthenticationException(
            new OAuth2Error("invalid_token_response"),
            new IllegalStateException("wrapper", new ConnectException("connection refused"))));

    assertThat(appender.list)
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage())
                  .contains(MetricNames.LOGIN_REASON_PROVIDER_ERROR)
                  .contains("invalid_token_response")
                  .contains("ConnectException");
            });
  }

  /**
   * The benign buckets are scanner- and probe-driven — every bare hit on {@code
   * /login/oauth2/code/*} and every {@code prompt=none} SSO probe without a Keycloak cookie lands
   * there — so they must stay at DEBUG. At WARN a single path-scanning bot would flood the log
   * (REQ-OBS-001).
   */
  @Test
  void onAuthenticationFailure_logsBenignBucketsAtDebug() throws Exception {
    handleFailure(oauth2("login_required"));
    handleFailure(new BadCredentialsException("nope"));

    assertThat(appender.list)
        .hasSize(2)
        .allSatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.DEBUG));
    assertThat(appender.list.get(0).getFormattedMessage())
        .contains(MetricNames.LOGIN_REASON_INVALID_STATE);
    assertThat(appender.list.get(1).getFormattedMessage()).contains(MetricNames.LOGIN_REASON_OTHER);
  }

  /**
   * The OAuth2 error <em>description</em> is provider-supplied free text and therefore a
   * log-injection surface (CWE-117): it must never reach the log line, sanitised or not. The
   * bounded error code may.
   */
  @Test
  void onAuthenticationFailure_neverLogsTheProviderSuppliedDescription() throws Exception {
    handleFailure(
        new OAuth2AuthenticationException(
            new OAuth2Error(
                "invalid_grant",
                "forged\nERROR --- [main] a.b.C : login succeeded",
                "https://example.invalid/err")));

    assertThat(appender.list)
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.getFormattedMessage()).contains("invalid_grant");
              assertThat(event.getFormattedMessage())
                  .doesNotContain("forged")
                  .doesNotContain("login succeeded")
                  .doesNotContain("example.invalid");
              assertThat(event.getFormattedMessage()).doesNotContain("\n");
            });
  }

  private static OAuth2AuthenticationException oauth2(String errorCode) {
    return new OAuth2AuthenticationException(new OAuth2Error(errorCode));
  }

  @Test
  void aFailedLoginDoesNotParkTheExceptionInTheSession() throws Exception {
    org.springframework.mock.web.MockHttpServletRequest request =
        new org.springframework.mock.web.MockHttpServletRequest();
    org.springframework.mock.web.MockHttpServletResponse response =
        new org.springframework.mock.web.MockHttpServletResponse();
    request.getSession(true);

    new LoginFailureMetricsHandler(new SimpleMeterRegistry(), "/?error")
        .onAuthenticationFailure(request, response, new BadCredentialsException("nope"));

    assertThat(request.getSession(false)).isNotNull();
    assertThat(
            request
                .getSession(false)
                .getAttribute(
                    org.springframework.security.web.WebAttributes.AUTHENTICATION_EXCEPTION))
        .isNull();
  }

  @Test
  void aFailedLoginStillRedirectsAndStillExposesTheFailureForThisRequest() throws Exception {
    org.springframework.mock.web.MockHttpServletRequest request =
        new org.springframework.mock.web.MockHttpServletRequest();
    org.springframework.mock.web.MockHttpServletResponse response =
        new org.springframework.mock.web.MockHttpServletResponse();

    new LoginFailureMetricsHandler(new SimpleMeterRegistry(), "/?error")
        .onAuthenticationFailure(request, response, new BadCredentialsException("nope"));

    assertThat(response.getRedirectedUrl()).isEqualTo("/?error");
    assertThat(
            request.getAttribute(
                org.springframework.security.web.WebAttributes.AUTHENTICATION_EXCEPTION))
        .isNotNull();
  }
}
