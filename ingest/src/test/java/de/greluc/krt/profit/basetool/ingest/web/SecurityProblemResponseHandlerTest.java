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

package de.greluc.krt.profit.basetool.ingest.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import de.greluc.krt.profit.basetool.ingest.metrics.MetricNames;
import de.greluc.krt.profit.basetool.ingest.support.LogCapture;
import de.greluc.krt.profit.basetool.ingest.support.TestLoggingProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import tools.jackson.databind.json.JsonMapper;

/** Unit tests for the filter-level 401/403 problem responses and their logging. */
class SecurityProblemResponseHandlerTest {

  private static final String URI = "/v1/refinery-extract";

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  private final SecurityProblemResponseHandler handler =
      new SecurityProblemResponseHandler(
          JsonMapper.builder().build(), meterRegistry, TestLoggingProperties.defaults());

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  private static MockHttpServletRequest request() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", URI);
    request.setRequestURI(URI);
    return request;
  }

  private double errorCount(String code) {
    var counter =
        meterRegistry.find(MetricNames.HTTP_ERROR).tag(MetricNames.TAG_CODE, code).counter();
    return counter == null ? 0.0d : counter.count();
  }

  @Test
  void unauthenticatedRequestGetsAProblemBodyWithAStableCode() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.commence(request(), response, new InvalidBearerTokenException("expired"));

    assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    assertThat(response.getContentAsString())
        .contains("\"code\":\"" + MetricNames.CODE_UNAUTHENTICATED + "\"")
        .contains("\"status\":401");
    assertThat(errorCount(MetricNames.CODE_UNAUTHENTICATED)).isEqualTo(1.0d);
  }

  @Test
  void unauthenticatedRequestIsCountedUnderItsBoundedBearerErrorCode() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.commence(request(), response, new InvalidBearerTokenException("expired"));

    assertThat(authFailureCount(MetricNames.AUTH_INVALID_TOKEN)).isEqualTo(1.0d);
  }

  @Test
  void aNonOauthAuthenticationFailureCollapsesToTheBoundedOtherLiteral() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.commence(
        request(),
        response,
        new org.springframework.security.authentication.BadCredentialsException("nope"));

    assertThat(authFailureCount(MetricNames.AUTH_OTHER)).isEqualTo(1.0d);
    assertThat(authFailureCount(MetricNames.AUTH_INVALID_TOKEN)).isZero();
  }

  @Test
  void aRequestWithNoCredentialAtAllReadsAsNoCredentials() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.commence(
        request(),
        response,
        new org.springframework.security.authentication.InsufficientAuthenticationException(
            "Full authentication is required"));

    assertThat(authFailureCount(MetricNames.AUTH_NO_CREDENTIALS)).isEqualTo(1.0d);
    assertThat(authFailureCount(MetricNames.AUTH_OTHER)).isEqualTo(0.0d);
  }

  @Test
  void aRejectedTokenStaysOffTheNoCredentialSeries() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.commence(request(), response, new InvalidBearerTokenException("bad signature"));

    assertThat(authFailureCount(MetricNames.AUTH_INVALID_TOKEN)).isEqualTo(1.0d);
    assertThat(authFailureCount(MetricNames.AUTH_NO_CREDENTIALS)).isEqualTo(0.0d);
  }

  /**
   * Reads the bounded auth-failure counter for one reason.
   *
   * @param reason the bounded {@code MetricNames.AUTH_*} tag value
   * @return the counter value, or {@code 0.0} when the series does not exist
   */
  private double authFailureCount(String reason) {
    var counter =
        meterRegistry
            .find(MetricNames.INGEST_AUTH_FAILURES)
            .tag(MetricNames.TAG_REASON, reason)
            .counter();
    return counter == null ? 0.0 : counter.count();
  }

  @Test
  void unauthenticatedRequestKeepsTheBearerChallenge() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.commence(request(), response, new InvalidBearerTokenException("expired"));

    assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
  }

  @Test
  void unauthenticatedRequestIsLoggedAtDebugAndNeverEchoesTheTokenError() {
    List<ILoggingEvent> events =
        LogCapture.capture(
            SecurityProblemResponseHandler.class,
            Level.DEBUG,
            () ->
                handler.commence(
                    request(),
                    new MockHttpServletResponse(),
                    new InvalidBearerTokenException("eyJhbGciOiJIUzI1NiJ9.secret.sig is expired")));

    assertThat(events).hasSize(1);
    assertThat(events.getFirst().getLevel()).isEqualTo(Level.DEBUG);
    assertThat(events.getFirst().getFormattedMessage())
        .contains("POST /v1/refinery-extract")
        .contains("InvalidBearerTokenException")
        .doesNotContain("eyJ");
  }

  @Test
  void accessDeniedGetsAProblemBodyAndIsLoggedAtWarn() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();

    List<ILoggingEvent> events =
        LogCapture.capture(
            SecurityProblemResponseHandler.class,
            Level.DEBUG,
            () -> handler.handle(request(), response, new AccessDeniedException("nope")));

    assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
    assertThat(response.getContentAsString())
        .contains("\"code\":\"" + MetricNames.CODE_ACCESS_DENIED + "\"");
    assertThat(events).hasSize(1);
    assertThat(events.getFirst().getLevel()).isEqualTo(Level.WARN);
    assertThat(errorCount(MetricNames.CODE_ACCESS_DENIED)).isEqualTo(1.0d);
  }

  @Test
  void theProblemBodyCarriesTheCorrelationIdTheOuterFilterAlreadyMinted() throws Exception {
    MDC.put("correlationId", "cid-991");
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.commence(request(), response, new InvalidBearerTokenException("expired"));

    assertThat(response.getContentAsString()).contains("\"correlationId\":\"cid-991\"");
  }

  @Test
  void anAlreadyCommittedResponseIsLeftAlone() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.setCommitted(true);

    handler.commence(request(), response, new InvalidBearerTokenException("expired"));

    assertThat(response.getContentAsString()).isEmpty();
    assertThat(errorCount(MetricNames.CODE_UNAUTHENTICATED)).isZero();
  }
}
