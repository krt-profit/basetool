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

/**
 * Unit tests for the filter-level 401/403 problem responses. Before this handler the gateway
 * answered both with an <em>empty</em> body and logged nothing, so an extractor with an expired
 * token got a response it could not branch on and the operator saw no trace of it at all.
 */
class SecurityProblemResponseHandlerTest {

  private static final String URI = "/v1/refinery-extract";

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  private final SecurityProblemResponseHandler handler =
      new SecurityProblemResponseHandler(JsonMapper.builder().build(), meterRegistry);

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
  void unauthenticatedRequestKeepsTheBearerChallenge() throws Exception {
    // RFC 6750: the extractor's OAuth client reads WWW-Authenticate to tell "refresh the token"
    // from "you are not allowed". Writing our own body must not cost that header.
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.commence(request(), response, new InvalidBearerTokenException("expired"));

    assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
  }

  @Test
  void unauthenticatedRequestIsLoggedAtDebugAndNeverEchoesTheTokenError() {
    // DEBUG per REQ-OBS-001: on an internet-facing surface a WARN per unauthenticated probe is a
    // log flood, and the counter keeps the signal. The decode message can quote the presented JWT.
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
    // WARN, unlike the 401: "authenticated but not allowed" is the security-relevant case.
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
    // The gateway's CorrelationIdFilter runs OUTSIDE the security chain, so unlike the backend no
    // id has to be minted here — body, log line and the echoed header share the existing one.
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
