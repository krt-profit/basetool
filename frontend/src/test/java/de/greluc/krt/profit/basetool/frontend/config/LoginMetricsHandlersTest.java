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
 * Unit tests for the login-outcome metric handlers ({@link LoginSuccessMetricsHandler} / {@link
 * LoginFailureMetricsHandler}, #1041 item 18): the success/failure counters must be bumped with the
 * right bounded tags and the wrapped navigation/redirect must still run. The failure-reason mapping
 * is exercised directly to pin that only the bounded OAuth2 error code — not the raw description —
 * decides the bucket.
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
   * Drives one failure through the handler with a stubbed request/response so the superclass
   * redirect does not blow up, and returns the registry it counted into.
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
    // invalid_request is raised by OAuth2LoginAuthenticationFilter for a bare/partial callback (a
    // scanner/probe or stale bookmark hitting /login/oauth2/code/*) BEFORE any token exchange, so
    // it
    // must be a benign state failure, not provider_error — otherwise it false-trips
    // FrontendLoginBroken.
    assertThat(LoginFailureMetricsHandler.reasonFor(oauth2("invalid_request")))
        .isEqualTo(MetricNames.LOGIN_REASON_INVALID_STATE);
  }

  /**
   * The OIDC {@code prompt=none} error set must land in the benign bucket.
   * SsoReAuthenticationEntryPoint probes Keycloak with {@code prompt=none} on every unauthenticated
   * top-level navigation, and Keycloak answers {@code login_required} whenever the browser carries
   * no live SSO cookie — an authorization-response error raised before any token exchange. Counting
   * those as provider_error made a path-scanning bot trip FrontendLoginBroken overnight with login
   * perfectly healthy (2026-07-28).
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
    // access_denied is an authorization-response error too, but it means an explicit refusal rather
    // than routine "no session yet" noise, so it deliberately stays a provider_error.
    assertThat(LoginFailureMetricsHandler.reasonFor(oauth2("access_denied")))
        .isEqualTo(MetricNames.LOGIN_REASON_PROVIDER_ERROR);
  }

  /**
   * An {@link OAuth2AuthenticationException} carrying no {@link OAuth2Error} must map to
   * provider_error without throwing. This pins a real hazard of the set-based lookup: {@code
   * Set.of(…).contains(null)} throws {@link NullPointerException}, so the null guard in {@code
   * isStateError} is load-bearing (the superseded chain of {@code "literal".equals(code)} calls was
   * null-safe by construction). {@code new OAuth2Error(null)} is rejected by Spring, so the null
   * error can only be reached through a stub.
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
   * The bucket {@code FrontendLoginBroken} fires on must reach the log at WARN and must carry the
   * two fields that make it triageable: the bounded OAuth2 error code and the root cause's type.
   * Before audit finding H3 the handler had no logger at all, so the alert's own "check the
   * frontend logs" instruction pointed at nothing.
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
}
