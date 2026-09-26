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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.greluc.krt.profit.basetool.frontend.exception.ReauthenticationRequiredException;
import de.greluc.krt.profit.basetool.frontend.metrics.MetricNames;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.ConnectException;
import java.net.URI;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.ClientAuthorizationRequiredException;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/**
 * Tests how {@link BackendApiClient}'s shared {@code exchange} helper translates Resilience4j and
 * transport failures (open circuit breaker, full bulkhead, timeout, {@code ConnectException}) into
 * {@link BackendServiceException}s.
 *
 * <p>The WebClient chain is mocked explicitly so {@code block()} throws; checked exceptions are
 * wrapped in a {@code RuntimeException} cause chain, as Reactor does.
 */
class BackendApiClientResilienceTest {

  private WebClient webClient;
  private WebClient termsDocumentClient;
  private SimpleMeterRegistry meterRegistry;
  private BackendApiClient client;

  @BeforeEach
  void setUp() {
    webClient = mock(WebClient.class);
    termsDocumentClient = mock(WebClient.class);
    meterRegistry = new SimpleMeterRegistry();
    client =
        new BackendApiClient(
            webClient,
            termsDocumentClient,
            meterRegistry,
            new org.springframework.cache.support.NoOpCacheManager());
  }

  @Nested
  class GetTests {

    @Test
    void circuitBreakerOpen_yields503_serviceUnavailable() {
      Logger clientLogger = (Logger) LoggerFactory.getLogger(BackendApiClient.class);
      Level originalLevel = clientLogger.getLevel();
      ListAppender<ILoggingEvent> appender = new ListAppender<>();
      appender.start();
      clientLogger.addAppender(appender);
      clientLogger.setLevel(Level.DEBUG);
      try {
        stubGet(webClient, "/api/v1/x", mock(CallNotPermittedException.class));

        BackendServiceException ex =
            assertThrows(
                BackendServiceException.class, () -> client.get("/api/v1/x", String.class));

        assertEquals(503, ex.getStatusCode());
        assertEquals(BackendServiceException.CODE_SERVICE_UNAVAILABLE, ex.getProblemCode());
        assertEquals("Backend circuit breaker open", ex.getMessage());
        assertEquals(
            1.0d,
            meterRegistry
                .get(MetricNames.BACKEND_CLIENT_ERRORS)
                .tags(
                    MetricNames.TAG_REASON,
                    MetricNames.REASON_CIRCUIT_OPEN,
                    MetricNames.TAG_METHOD,
                    "GET")
                .counter()
                .count());
        assertThat(appender.list)
            .anyMatch(
                e ->
                    e.getLevel() == Level.DEBUG
                        && e.getFormattedMessage().contains("Circuit breaker open"));
        assertThat(appender.list)
            .noneMatch(
                e ->
                    e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("Circuit breaker open"));
      } finally {
        clientLogger.detachAppender(appender);
        clientLogger.setLevel(originalLevel);
      }
    }

    @Test
    void bulkheadFull_yields503_serviceUnavailable() {
      stubGet(webClient, "/api/v1/x", mock(BulkheadFullException.class));

      BackendServiceException ex =
          assertThrows(BackendServiceException.class, () -> client.get("/api/v1/x", String.class));

      assertEquals(503, ex.getStatusCode());
      assertEquals(BackendServiceException.CODE_SERVICE_UNAVAILABLE, ex.getProblemCode());
      assertEquals("Backend bulkhead full", ex.getMessage());
    }

    @Test
    void timeoutException_yields504_backendTimeout() {
      stubGet(
          webClient,
          "/api/v1/x",
          new RuntimeException("reactor wrapper", new TimeoutException("3s")));

      BackendServiceException ex =
          assertThrows(BackendServiceException.class, () -> client.get("/api/v1/x", String.class));

      assertEquals(504, ex.getStatusCode());
      assertEquals(BackendServiceException.CODE_BACKEND_TIMEOUT, ex.getProblemCode());
      assertEquals("Backend timeout", ex.getMessage());
    }

    @Test
    void connectException_yields504_backendTimeout() {
      stubGet(
          webClient, "/api/v1/x", new RuntimeException("wrap", new ConnectException("refused")));

      BackendServiceException ex =
          assertThrows(BackendServiceException.class, () -> client.get("/api/v1/x", String.class));

      assertEquals(504, ex.getStatusCode());
      assertEquals(BackendServiceException.CODE_BACKEND_TIMEOUT, ex.getProblemCode());
    }

    /**
     * A {@code WebClientResponseException} with a success status wrapping a body-side transport
     * failure is classified by its cause, as 504 {@code BACKEND_TIMEOUT}, not by its status line.
     */
    @Test
    void successStatusWrappingABodySideTransportFailure_yields504_backendTimeout() {
      WebClientResponseException wrapped =
          WebClientResponseException.create(
              200,
              "OK",
              org.springframework.http.HttpHeaders.EMPTY,
              new byte[0],
              java.nio.charset.StandardCharsets.UTF_8);
      wrapped.initCause(new java.io.IOException("Connection prematurely closed DURING response"));
      stubGet(webClient, "/api/v1/x", wrapped);

      BackendServiceException ex =
          assertThrows(BackendServiceException.class, () -> client.get("/api/v1/x", String.class));

      assertEquals(504, ex.getStatusCode());
      assertEquals(BackendServiceException.CODE_BACKEND_TIMEOUT, ex.getProblemCode());
      assertEquals(
          1.0d,
          meterRegistry
              .get(MetricNames.BACKEND_CLIENT_ERRORS)
              .tags(
                  MetricNames.TAG_REASON, MetricNames.REASON_TIMEOUT, MetricNames.TAG_METHOD, "GET")
              .counter()
              .count(),
          "the transport fault is counted as one, not as a 4xx the caller caused");
    }

    @Test
    void webClientRequestException_yields504_backendTimeout() {
      WebClientRequestException wcre =
          new WebClientRequestException(
              new ConnectException("refused"),
              org.springframework.http.HttpMethod.GET,
              URI.create("https://backend.test/api/v1/x"),
              org.springframework.http.HttpHeaders.EMPTY);
      stubGet(webClient, "/api/v1/x", wcre);

      BackendServiceException result =
          assertThrows(BackendServiceException.class, () -> client.get("/api/v1/x", String.class));

      assertEquals(504, result.getStatusCode());
      assertEquals(BackendServiceException.CODE_BACKEND_TIMEOUT, result.getProblemCode());
    }

    @Test
    void unexpectedRuntimeException_yields500_unknown() {
      stubGet(webClient, "/api/v1/x", new IllegalStateException("oops"));

      BackendServiceException ex =
          assertThrows(BackendServiceException.class, () -> client.get("/api/v1/x", String.class));

      assertEquals(500, ex.getStatusCode());
      assertEquals(
          BackendServiceException.CODE_UNKNOWN,
          ex.getProblemCode(),
          "the catch-all branch must map unknown exceptions to UNKNOWN, not crash");
      assert ex.getMessage().contains("GET");
      assert ex.getMessage().contains("backend");
    }

    @Test
    void clientAuthorizationRequired_yieldsReauthenticationRequiredException() {
      stubGet(webClient, "/api/v1/x", new ClientAuthorizationRequiredException("keycloak"));

      assertThrows(
          ReauthenticationRequiredException.class, () -> client.get("/api/v1/x", String.class));
    }

    @Test
    void wrappedClientAuthorizationException_isUnwrappedToReauthenticationRequired() {
      stubGet(
          webClient,
          "/api/v1/x",
          new RuntimeException(
              "reactor wrap", new ClientAuthorizationRequiredException("keycloak")));

      assertThrows(
          ReauthenticationRequiredException.class, () -> client.get("/api/v1/x", String.class));
    }

    @Test
    void theTermsDocumentIsTheOneReadThatGoesOutOnTheAnonymousClient() {
      stubGet(
          termsDocumentClient,
          "/api/v1/terms/document",
          de.greluc.krt.profit.basetool.frontend.model.dto.TermsDocumentDto.class,
          new RuntimeException("wrap", new TimeoutException("terms-3s")));

      BackendServiceException ex =
          assertThrows(BackendServiceException.class, () -> client.getTermsDocumentAnonymously());
      assertEquals(
          504,
          ex.getStatusCode(),
          "getTermsDocumentAnonymously must go out on the anonymous client");
    }

    @Test
    void anOrdinaryGetNeverTouchesTheAnonymousClient() {
      stubGet(
          webClient, "/api/v1/x", new RuntimeException("wrap", new TimeoutException("auth-3s")));

      assertThrows(BackendServiceException.class, () -> client.get("/api/v1/x", String.class));
      org.mockito.Mockito.verifyNoInteractions(termsDocumentClient);
    }
  }

  @Nested
  class UnwrapChainTests {

    @Test
    void deeplyWrappedTimeoutException_isUnwrapped() {
      Exception wrapped =
          new RuntimeException(
              "outer", new RuntimeException("middle", new TimeoutException("inner timeout")));
      stubGet(webClient, "/api/v1/x", wrapped);

      BackendServiceException ex =
          assertThrows(BackendServiceException.class, () -> client.get("/api/v1/x", String.class));

      assertEquals(
          504,
          ex.getStatusCode(),
          "unwrap() must drill through the wrapper RuntimeExceptions to find the Timeout");
    }

    @Test
    void wrappedCircuitBreakerException_isUnwrapped() {
      Exception wrapped = new RuntimeException("outer", mock(CallNotPermittedException.class));
      stubGet(webClient, "/api/v1/x", wrapped);

      BackendServiceException ex =
          assertThrows(BackendServiceException.class, () -> client.get("/api/v1/x", String.class));

      assertEquals(503, ex.getStatusCode());
      assertEquals(BackendServiceException.CODE_SERVICE_UNAVAILABLE, ex.getProblemCode());
    }

    @Test
    void selfReferencingCauseChain_doesNotLoopForever() {
      SelfCausingException loopy = new SelfCausingException();
      stubGet(webClient, "/api/v1/x", loopy);

      BackendServiceException ex =
          assertThrows(BackendServiceException.class, () -> client.get("/api/v1/x", String.class));

      assertEquals(500, ex.getStatusCode());
      assertEquals(BackendServiceException.CODE_UNKNOWN, ex.getProblemCode());
    }
  }

  @Test
  void post_circuitBreakerOpen_yields503() {
    WebClient.RequestBodyUriSpec uriSpec = mock(WebClient.RequestBodyUriSpec.class);
    WebClient.RequestBodySpec bodySpec = mock(WebClient.RequestBodySpec.class);
    WebClient.RequestHeadersSpec<?> headersSpec = mock(WebClient.RequestHeadersSpec.class);
    WebClient.ResponseSpec respSpec = mock(WebClient.ResponseSpec.class);
    @SuppressWarnings("unchecked")
    Mono<String> body = (Mono<String>) mock(Mono.class);

    when(webClient.post()).thenReturn(uriSpec);
    when(uriSpec.uri("/api/v1/x")).thenReturn(bodySpec);
    when(bodySpec.bodyValue("{}")).thenAnswer(inv -> headersSpec);
    when(headersSpec.retrieve()).thenReturn(respSpec);
    when(respSpec.bodyToMono(String.class)).thenReturn(body);
    when(body.block()).thenThrow(mock(CallNotPermittedException.class));

    BackendServiceException ex =
        assertThrows(
            BackendServiceException.class, () -> client.post("/api/v1/x", "{}", String.class));

    assertEquals(503, ex.getStatusCode());
    assert ex.getMessage().contains("circuit breaker");
  }

  @Test
  void clearStaticDataCache_completesWithoutError() {
    client.clearStaticDataCache();
  }

  /**
   * Stubs the GET fluent chain on the given WebClient so that {@code
   * client.get().uri(uri).retrieve().bodyToMono(String.class).block()} throws the supplied
   * exception.
   */
  @SuppressWarnings("unchecked")
  private static void stubGet(WebClient targetClient, String uri, Throwable toThrow) {
    stubGet(targetClient, uri, String.class, toThrow);
  }

  /**
   * Like {@link #stubGet(WebClient, String, Throwable)}, for a call decoding a type other than
   * {@code String}.
   *
   * @param targetClient the WebClient mock to stub
   * @param uri the URI the call is expected to request
   * @param bodyType the type the call decodes
   * @param toThrow what {@code block()} should raise
   */
  private static void stubGet(
      WebClient targetClient, String uri, Class<?> bodyType, Throwable toThrow) {
    WebClient.RequestHeadersUriSpec<?> uriSpec = mock(WebClient.RequestHeadersUriSpec.class);
    WebClient.RequestHeadersSpec<?> headersSpec = mock(WebClient.RequestHeadersSpec.class);
    WebClient.ResponseSpec respSpec = mock(WebClient.ResponseSpec.class);
    Mono<?> body = mock(Mono.class);

    when(targetClient.get()).thenAnswer(inv -> uriSpec);
    when(uriSpec.uri(uri)).thenAnswer(inv -> headersSpec);
    when(headersSpec.retrieve()).thenReturn(respSpec);
    when(respSpec.bodyToMono(bodyType)).thenAnswer(inv -> body);

    RuntimeException toThrowRuntime =
        (toThrow instanceof RuntimeException re) ? re : new RuntimeException("test-wrap", toThrow);
    when(body.block()).thenThrow(toThrowRuntime);
  }

  /** Self-referential cause chain (cause == this) to test the unwrap loop's termination guard. */
  private static class SelfCausingException extends RuntimeException {
    @Override
    public synchronized Throwable getCause() {
      return this;
    }
  }
}
