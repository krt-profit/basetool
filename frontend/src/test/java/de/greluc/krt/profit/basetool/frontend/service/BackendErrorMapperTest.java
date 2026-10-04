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

package de.greluc.krt.profit.basetool.frontend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.greluc.krt.profit.basetool.frontend.exception.ReauthenticationRequiredException;
import de.greluc.krt.profit.basetool.frontend.metrics.MetricNames;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Unit tests for {@link BackendErrorMapper}: one case per {@link BackendErrorMapper.Outcome}, each
 * checking the classification, the exception handed back, the log level and the {@code
 * basetool_backend_client_errors_total} labels.
 */
class BackendErrorMapperTest {

  private static final String URI_TEMPLATE = "/api/v1/missions/{id}";

  private SimpleMeterRegistry meterRegistry;
  private ListAppender<ILoggingEvent> appender;
  private BackendErrorMapper mapper;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    Logger logger = new LoggerContext().getLogger("mapper-under-test");
    logger.setLevel(Level.DEBUG);
    appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    mapper = new BackendErrorMapper(meterRegistry, logger);
  }

  @Test
  void aClientErrorIsAProblemLoggedAtWarnAndCountedAs4xx() {
    WebClientResponseException response =
        response(
            409, "{\"code\":\"CONFLICT_VERSION\",\"detail\":\"stale\",\"correlationId\":\"c1\"}");

    assertThat(BackendErrorMapper.classify(response))
        .isInstanceOf(BackendErrorMapper.Problem.class);
    RuntimeException mapped = mapper.map(response, "PUT", URI_TEMPLATE);

    assertThat(mapped).isInstanceOf(BackendServiceException.class);
    BackendServiceException problem = (BackendServiceException) mapped;
    assertThat(problem.getStatusCode()).isEqualTo(409);
    assertThat(problem.getProblemCode()).isEqualTo("CONFLICT_VERSION");
    assertThat(problem.getProblemDetail()).isEqualTo("stale");
    assertThat(problem.getCorrelationId()).isEqualTo("c1");
    assertThat(levels()).containsExactly(Level.WARN);
    assertThat(count(MetricNames.REASON_BACKEND_4XX, "PUT")).isEqualTo(1.0d);
  }

  @Test
  void aServerErrorIsAProblemLoggedAtErrorAndCountedAs5xx() {
    RuntimeException mapped = mapper.map(response(500, ""), "GET", URI_TEMPLATE);

    assertThat(((BackendServiceException) mapped).getStatusCode()).isEqualTo(500);
    assertThat(((BackendServiceException) mapped).getProblemCode())
        .isEqualTo(BackendServiceException.CODE_UNKNOWN);
    assertThat(levels()).containsExactly(Level.ERROR);
    assertThat(count(MetricNames.REASON_BACKEND_5XX, "GET")).isEqualTo(1.0d);
  }

  @Test
  void anAccessGateRefusalIsLoggedAtDebugAndNotCounted() {
    RuntimeException mapped =
        mapper.map(response(403, "{\"code\":\"TERMS_NOT_ACCEPTED\"}"), "GET", URI_TEMPLATE);

    assertThat(((BackendServiceException) mapped).getProblemCode())
        .isEqualTo(BackendServiceException.CODE_TERMS_NOT_ACCEPTED);
    assertThat(levels()).containsExactly(Level.DEBUG);
    assertThat(meterRegistry.find(MetricNames.BACKEND_CLIENT_ERRORS).counters()).isEmpty();
  }

  @Test
  void aMissingTokenIsTheReauthenticationSignal() {
    RuntimeException error =
        new RuntimeException(
            "wrapped", new ClientAuthorizationException(new OAuth2Error("invalid_grant"), "kc"));

    assertThat(BackendErrorMapper.classify(error))
        .isInstanceOf(BackendErrorMapper.Reauthentication.class);
    RuntimeException mapped = mapper.map(error, "POST", URI_TEMPLATE);

    assertThat(mapped)
        .isInstanceOf(ReauthenticationRequiredException.class)
        .hasMessage("Re-authentication required for POST " + URI_TEMPLATE)
        .hasCause(error);
    assertThat(levels()).containsExactly(Level.DEBUG);
    assertThat(meterRegistry.find(MetricNames.BACKEND_CLIENT_ERRORS).counters()).isEmpty();
  }

  @Test
  void anOpenCircuitIsServiceUnavailable() {
    RuntimeException error = new RuntimeException("outer", mock(CallNotPermittedException.class));

    assertThat(BackendErrorMapper.classify(error))
        .isInstanceOf(BackendErrorMapper.CircuitOpen.class);
    assertUnavailable(mapper.map(error, "GET", URI_TEMPLATE), error);
    assertThat(levels()).containsExactly(Level.DEBUG);
    assertThat(count(MetricNames.REASON_CIRCUIT_OPEN, "GET")).isEqualTo(1.0d);
  }

  @Test
  void aFullBulkheadIsServiceUnavailable() {
    RuntimeException error = mock(BulkheadFullException.class);

    assertThat(BackendErrorMapper.classify(error))
        .isInstanceOf(BackendErrorMapper.BulkheadFull.class);
    assertUnavailable(mapper.map(error, "DELETE", URI_TEMPLATE), error);
    assertThat(levels()).containsExactly(Level.WARN);
    assertThat(count(MetricNames.REASON_BULKHEAD_FULL, "DELETE")).isEqualTo(1.0d);
  }

  @Test
  void aTimeoutOrTransportFailureIsBackendTimeout() {
    List<Throwable> failures =
        List.of(
            new RuntimeException(new TimeoutException("slow")),
            new WebClientRequestException(
                new IOException("refused"), HttpMethod.GET, URI.create("/x"), new HttpHeaders()),
            new IllegalStateException(new IOException("reset")));

    for (Throwable failure : failures) {
      assertThat(BackendErrorMapper.classify(failure))
          .isInstanceOf(BackendErrorMapper.Timeout.class);
      BackendServiceException mapped =
          (BackendServiceException) mapper.map(failure, "PATCH", URI_TEMPLATE);
      assertThat(mapped.getStatusCode()).isEqualTo(504);
      assertThat(mapped.getProblemCode()).isEqualTo(BackendServiceException.CODE_BACKEND_TIMEOUT);
      assertThat(mapped.getCause()).isSameAs(failure);
    }
    assertThat(levels()).containsOnly(Level.WARN).hasSize(failures.size());
    assertThat(count(MetricNames.REASON_TIMEOUT, "PATCH")).isEqualTo(failures.size());
  }

  @Test
  void anythingElseIsAnUnexpected500() {
    IllegalArgumentException error = new IllegalArgumentException("bad template");

    assertThat(BackendErrorMapper.classify(error))
        .isInstanceOf(BackendErrorMapper.Unexpected.class);
    BackendServiceException mapped =
        (BackendServiceException) mapper.map(error, "GET", URI_TEMPLATE);

    assertThat(mapped.getStatusCode()).isEqualTo(500);
    assertThat(mapped.getProblemCode()).isEqualTo(BackendServiceException.CODE_UNKNOWN);
    assertThat(mapped).hasMessage("Error on GET data from backend").hasCause(error);
    assertThat(levels()).containsExactly(Level.ERROR);
    assertThat(count(MetricNames.REASON_UNKNOWN, "GET")).isEqualTo(1.0d);
  }

  @Test
  void aResponseWithoutAnErrorStatusIsNotAProblem() {
    WebClientResponseException redirect =
        WebClientResponseException.create(
            302, "Found", new HttpHeaders(), new byte[0], StandardCharsets.UTF_8);

    assertThat(BackendErrorMapper.classify(redirect))
        .isInstanceOf(BackendErrorMapper.Unexpected.class);
  }

  @Test
  void anErrorResponseWinsOverAReauthenticationCauseInItsChain() {
    WebClientResponseException response =
        WebClientResponseException.create(
            401, "Unauthorized", new HttpHeaders(), new byte[0], StandardCharsets.UTF_8);
    response.initCause(new ClientAuthorizationException(new OAuth2Error("invalid_grant"), "kc"));

    assertThat(BackendErrorMapper.classify(response))
        .isInstanceOf(BackendErrorMapper.Problem.class);
  }

  private static WebClientResponseException response(int status, String body) {
    return WebClientResponseException.create(
        status, "status", new HttpHeaders(), body.getBytes(StandardCharsets.UTF_8), null);
  }

  private static void assertUnavailable(RuntimeException mapped, Throwable cause) {
    assertThat(mapped).isInstanceOf(BackendServiceException.class).hasCause(cause);
    BackendServiceException unavailable = (BackendServiceException) mapped;
    assertThat(unavailable.getStatusCode()).isEqualTo(503);
    assertThat(unavailable.getProblemCode())
        .isEqualTo(BackendServiceException.CODE_SERVICE_UNAVAILABLE);
  }

  private List<Level> levels() {
    return appender.list.stream().map(ILoggingEvent::getLevel).toList();
  }

  private double count(String reason, String method) {
    Counter counter =
        meterRegistry
            .find(MetricNames.BACKEND_CLIENT_ERRORS)
            .tag(MetricNames.TAG_REASON, reason)
            .tag(MetricNames.TAG_METHOD, method)
            .counter();
    return counter == null ? 0.0d : counter.count();
  }
}
