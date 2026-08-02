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
import de.greluc.krt.profit.basetool.ingest.ratelimit.RateLimitedException;
import de.greluc.krt.profit.basetool.ingest.support.LogCapture;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests the backend-relay branch of {@link GlobalExceptionHandler} (security audit gap-fill): a
 * backend 4xx must surface only the backend problem's sanitised {@code detail}
 * (content-type-checked + length-capped), never the raw response body, and a backend 5xx must
 * collapse to a generic 502.
 */
class GlobalExceptionHandlerTest {

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  private final GlobalExceptionHandler handler =
      new GlobalExceptionHandler(JsonMapper.builder().build(), meterRegistry);

  private static WebClientResponseException backendError(
      int status, MediaType contentType, String body) {
    HttpHeaders headers = new HttpHeaders();
    if (contentType != null) {
      headers.setContentType(contentType);
    }
    return WebClientResponseException.create(
        status,
        "status",
        headers,
        body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8),
        StandardCharsets.UTF_8);
  }

  @Test
  void backend4xxProblemJson_relaysOnlyTheDetail() {
    WebClientResponseException ex =
        backendError(
            400,
            MediaType.APPLICATION_PROBLEM_JSON,
            "{\"title\":\"Bad Request\",\"detail\":\"Mission is already finalized.\","
                + "\"status\":400,\"code\":\"BUSINESS_CONFLICT\"}");

    ProblemDetail problem = handler.handleBackendResponse(ex);

    assertThat(problem.getStatus()).isEqualTo(400);
    assertThat(problem.getDetail()).isEqualTo("Mission is already finalized.");
    // A backend 4xx reject is counted once under the bounded backend_reject reason (REQ-OBS-011).
    assertThat(
            meterRegistry
                .get(MetricNames.INGEST_HANDOFF_ERRORS)
                .tag(MetricNames.TAG_REASON, MetricNames.REASON_BACKEND_REJECT)
                .counter()
                .count())
        .isEqualTo(1.0d);
  }

  @Test
  void backend4xxNonProblemJson_doesNotRelayRawBody() {
    WebClientResponseException ex =
        backendError(
            400, MediaType.TEXT_HTML, "<html><body>nginx internal 400 — /admin</body></html>");

    ProblemDetail problem = handler.handleBackendResponse(ex);

    assertThat(problem.getStatus()).isEqualTo(400);
    assertThat(problem.getDetail()).isEqualTo("The import backend rejected the request.");
    assertThat(problem.getDetail()).doesNotContain("nginx", "/admin");
  }

  @Test
  void backend4xxOversizedDetail_isCappedAt500() {
    String longDetail = "x".repeat(2000);
    WebClientResponseException ex =
        backendError(
            422, MediaType.APPLICATION_PROBLEM_JSON, "{\"detail\":\"" + longDetail + "\"}");

    ProblemDetail problem = handler.handleBackendResponse(ex);

    assertThat(problem.getDetail()).hasSize(500);
  }

  @Test
  void backend5xx_collapsesToGeneric502() {
    WebClientResponseException ex =
        backendError(
            500, MediaType.APPLICATION_PROBLEM_JSON, "{\"detail\":\"backend stacktrace boom\"}");

    ProblemDetail problem = handler.handleBackendResponse(ex);

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY.value());
    assertThat(problem.getDetail()).doesNotContain("boom");
    assertThat(relayFailures(MetricNames.REASON_BACKEND_UNAVAILABLE)).isEqualTo(1.0d);
  }

  @Test
  void backend4xxWithoutContentType_fallsBackToTheGenericDetail() {
    WebClientResponseException ex = backendError(400, null, "{\"detail\":\"leaky\"}");

    ProblemDetail problem = handler.handleBackendResponse(ex);

    assertThat(problem.getDetail()).isEqualTo("The import backend rejected the request.");
  }

  @Test
  void backend4xxWithBlankProblemBody_fallsBackToTheGenericDetail() {
    WebClientResponseException ex = backendError(400, MediaType.APPLICATION_PROBLEM_JSON, "   ");

    ProblemDetail problem = handler.handleBackendResponse(ex);

    assertThat(problem.getDetail()).isEqualTo("The import backend rejected the request.");
  }

  @Test
  void backend4xxWithUnparseableProblemBody_fallsBackToTheGenericDetail() {
    WebClientResponseException ex =
        backendError(400, MediaType.APPLICATION_PROBLEM_JSON, "{not json at all");

    ProblemDetail problem = handler.handleBackendResponse(ex);

    assertThat(problem.getDetail()).isEqualTo("The import backend rejected the request.");
  }

  @Test
  void backend4xxWithoutDetail_fallsBackToTheProblemTitle() {
    WebClientResponseException ex =
        backendError(409, MediaType.APPLICATION_PROBLEM_JSON, "{\"title\":\"Conflict\"}");

    ProblemDetail problem = handler.handleBackendResponse(ex);

    assertThat(problem.getStatus()).isEqualTo(409);
    assertThat(problem.getDetail()).isEqualTo("Conflict");
  }

  @Test
  void backend4xxWithNeitherDetailNorTitle_fallsBackToTheGenericDetail() {
    WebClientResponseException ex =
        backendError(400, MediaType.APPLICATION_PROBLEM_JSON, "{\"status\":400}");

    ProblemDetail problem = handler.handleBackendResponse(ex);

    assertThat(problem.getDetail()).isEqualTo("The import backend rejected the request.");
  }

  @Test
  void gatewayDetectedBadRequest_becomesA400WithItsOwnMessage() {
    ProblemDetail problem =
        handler.handleBadRequest(
            new BadRequestException("The blueprint export must be a JSON" + " object."));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(problem.getDetail()).isEqualTo("The blueprint export must be a JSON object.");
    assertThat(problem.getProperties()).containsEntry("code", "BAD_REQUEST");
    // A gateway-side reject is not a relay failure and must not inflate the handoff-error counter.
    assertThat(meterRegistry.find(MetricNames.INGEST_HANDOFF_ERRORS).counters()).isEmpty();
  }

  @Test
  void rateLimited_becomesA429CarryingRetryAfter() {
    ResponseEntity<ProblemDetail> response =
        handler.handleRateLimited(new RateLimitedException(42));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("42");
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().getProperties()).containsEntry("code", "RATE_LIMITED");
    // A pre-relay throttle is not a handoff failure either.
    assertThat(meterRegistry.find(MetricNames.INGEST_HANDOFF_ERRORS).counters()).isEmpty();
  }

  @Test
  void transportFailure_becomesA502AndCountsBackendUnavailable() {
    WebClientRequestException ex =
        new WebClientRequestException(
            new java.net.ConnectException("connection refused"),
            HttpMethod.POST,
            URI.create("https://backend:11261/api/v1/refinery-orders/import-extract"),
            HttpHeaders.EMPTY);

    ProblemDetail problem = handler.handleBackendTransportFailure(ex);

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY.value());
    assertThat(problem.getProperties()).containsEntry("code", "BACKEND_RELAY_FAILED");
    assertThat(relayFailures(MetricNames.REASON_BACKEND_UNAVAILABLE)).isEqualTo(1.0d);
  }

  @Test
  void openCircuit_becomesA502ButIsLoggedAtDebugNotWarn() {
    // REQ-OBS-001 / issue #1203: the breaker rejects every call for its whole open window, so a
    // per-call WARN would flood the log during a routine backend restart.
    CallNotPermittedException ex =
        CallNotPermittedException.createCallNotPermittedException(
            CircuitBreakerRegistry.ofDefaults().circuitBreaker("backend"));

    List<ILoggingEvent> events =
        LogCapture.capture(
            GlobalExceptionHandler.class,
            Level.DEBUG,
            () -> {
              ProblemDetail problem = handler.handleBackendCircuitOpen(ex);
              assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY.value());
            });

    assertThat(events).isNotEmpty();
    assertThat(events).allMatch(e -> e.getLevel() == Level.DEBUG);
    assertThat(relayFailures(MetricNames.REASON_BACKEND_UNAVAILABLE)).isEqualTo(1.0d);
  }

  @Test
  void unexpectedFailure_becomesAGeneric500ThatLeaksNothing() {
    ProblemDetail problem =
        handler.handleUnexpected(new IllegalStateException("jdbc://user:pw@host exploded"));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
    assertThat(problem.getDetail()).isEqualTo("An unexpected error occurred.");
    assertThat(problem.getDetail()).doesNotContain("jdbc", "pw");
    assertThat(relayFailures(MetricNames.REASON_INTERNAL)).isEqualTo(1.0d);
  }

  @Test
  void problemsCarryTheCurrentCorrelationId() {
    MDC.put("correlationId", "cid-77");
    try {
      ProblemDetail problem = handler.handleBadRequest(new BadRequestException("nope"));

      assertThat(problem.getProperties()).containsEntry("correlationId", "cid-77");
    } finally {
      MDC.remove("correlationId");
    }
  }

  @Test
  void problemsOmitTheCorrelationIdWhenTheMdcIsEmpty() {
    MDC.remove("correlationId");

    ProblemDetail problem = handler.handleBadRequest(new BadRequestException("nope"));

    assertThat(problem.getProperties()).doesNotContainKey("correlationId");
  }

  @Test
  void redisStagingOutage_becomesARetryable503RatherThanAGeneric500() {
    // Redis is the gateway's only data store; an outage there is an availability event that
    // self-heals, so the caller must be told to retry instead of being handed a dead 500.
    ResponseEntity<ProblemDetail> response =
        handler.handleStagingUnavailable(
            new RedisConnectionFailureException("Unable to connect to Redis"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().getProperties())
        .containsEntry("code", MetricNames.CODE_SERVICE_UNAVAILABLE);
    // Counted apart from a genuine internal fault so the dashboard can tell the two outages apart.
    assertThat(relayFailures(MetricNames.REASON_STAGING_UNAVAILABLE)).isEqualTo(1.0d);
    assertThat(relayFailures(MetricNames.REASON_INTERNAL)).isZero();
    assertThat(
            meterRegistry
                .get(MetricNames.HTTP_ERROR)
                .tag(MetricNames.TAG_CODE, MetricNames.CODE_SERVICE_UNAVAILABLE)
                .counter()
                .count())
        .isEqualTo(1.0d);
  }

  @Test
  void redisStagingOutage_isWarnedNotErrored_andNeverEchoesTheEndpoint() {
    // ERROR would inflate logback_events_total{level="error"} and trip LogbackErrorSpike on an
    // outage that is not an application fault (REQ-OBS-013); the Lettuce message names the host.
    List<ILoggingEvent> events =
        LogCapture.capture(
            GlobalExceptionHandler.class,
            Level.DEBUG,
            () ->
                handler.handleStagingUnavailable(
                    new RedisConnectionFailureException("Unable to connect to redis:6379")));

    assertThat(events).hasSize(1);
    assertThat(events.getFirst().getLevel()).isEqualTo(Level.WARN);
    assertThat(events.getFirst().getFormattedMessage())
        .contains("RedisConnectionFailureException")
        .doesNotContain("redis:6379");
  }

  @Test
  void backend4xx_leavesADebugBreadcrumbSoItIsNotMistakenForOurOwnValidation() {
    List<ILoggingEvent> events =
        LogCapture.capture(
            GlobalExceptionHandler.class,
            Level.DEBUG,
            () -> handler.handleBackendResponse(backendError(400, MediaType.TEXT_HTML, "")));

    assertThat(events).hasSize(1);
    assertThat(events.getFirst().getLevel()).isEqualTo(Level.DEBUG);
    assertThat(events.getFirst().getFormattedMessage())
        .isEqualTo("Backend relay rejected the import with 400");
  }

  /**
   * Reads the {@code basetool_ingest_handoff_errors_total} count for one bounded reason.
   *
   * @param reason the {@code MetricNames.REASON_*} tag value
   * @return the counter value, or {@code 0.0} when the counter was never created
   */
  private double relayFailures(String reason) {
    var counter =
        meterRegistry
            .find(MetricNames.INGEST_HANDOFF_ERRORS)
            .tag(MetricNames.TAG_REASON, reason)
            .counter();
    return counter == null ? 0.0d : counter.count();
  }
}
