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
import reactor.core.publisher.Mono;

/**
 * Resilience-error classification tests for {@link BackendApiClient}.
 *
 * <p>The existing {@code BackendApiClientProblemJsonTest} covers Problem+JSON decoding via
 * MockWebServer. This sibling exercises the {@code catch (Exception)} branch in {@code executeGet}
 * / {@code executePost} etc., which is the seam through which Resilience4j failures (CircuitBreaker
 * open, Bulkhead saturated, Timeout, ConnectException) are translated to {@link
 * BackendServiceException}s. A regression here silently degrades every page in the frontend.
 *
 * <p>The fluent WebClient chain is mocked explicitly (not via deep-stubs) because the production
 * code calls {@code .get().uri(...).retrieve() .bodyToMono(...).block()} and we need the terminal
 * {@code block()} to throw the resilience exception.
 *
 * <p>Checked exceptions ({@code TimeoutException}, {@code ConnectException}) are wrapped in a
 * {@code RuntimeException} cause-chain because {@code Mono.block()} does not declare them — but the
 * production code's {@code unwrap()} walks the cause chain to find them. This is exactly the
 * production path: Reactor wraps low-level checked exceptions in {@code RuntimeExceptionWrapper}s.
 */
class BackendApiClientResilienceTest {

  private WebClient webClient;
  private WebClient publicWebClient;
  private SimpleMeterRegistry meterRegistry;
  private BackendApiClient client;

  @BeforeEach
  void setUp() {
    webClient = mock(WebClient.class);
    publicWebClient = mock(WebClient.class);
    meterRegistry = new SimpleMeterRegistry();
    client =
        new BackendApiClient(
            webClient,
            publicWebClient,
            meterRegistry,
            new org.springframework.cache.support.NoOpCacheManager());
  }

  // ---------------------------------------------------------------
  // GET — every resilience branch
  // ---------------------------------------------------------------

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
        // The failure is counted once under the bounded circuit-open reason + GET verb
        // (REQ-OBS-011).
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
        // The circuit-open line is DEBUG, not WARN: it fires for every call blocked while the
        // breaker stays open, so at WARN a routine backend restart floods the log (issue #1203,
        // REQ-OBS-001). The reason=circuit_open metric above keeps the count either way.
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
      // Wrap the checked TimeoutException — Reactor does this in real life
      // (RuntimeExceptionWrapper). unwrap() must still find it.
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
      // The OAuth2 manager throws this when the session has no usable token; it must be
      // reclassified
      // (not mapped to a generic 500) so GlobalExceptionHandler can bounce the user to re-login.
      stubGet(webClient, "/api/v1/x", new ClientAuthorizationRequiredException("keycloak"));

      assertThrows(
          ReauthenticationRequiredException.class, () -> client.get("/api/v1/x", String.class));
    }

    @Test
    void wrappedClientAuthorizationException_isUnwrappedToReauthenticationRequired() {
      // Reactor wraps the cause; the cause-chain walk must still detect the auth failure.
      stubGet(
          webClient,
          "/api/v1/x",
          new RuntimeException(
              "reactor wrap", new ClientAuthorizationRequiredException("keycloak")));

      assertThrows(
          ReauthenticationRequiredException.class, () -> client.get("/api/v1/x", String.class));
    }

    @Test
    void publicWebClient_isUsedWhenIsPublicTrue() {
      stubGet(
          publicWebClient,
          "/api/v1/public-data",
          new RuntimeException("wrap", new TimeoutException("public-3s")));

      BackendServiceException ex =
          assertThrows(
              BackendServiceException.class,
              () -> client.get("/api/v1/public-data", String.class, /* isPublic= */ true));
      assertEquals(
          504,
          ex.getStatusCode(),
          "stubbing publicWebClient (not webClient) must surface the same exception "
              + "as if isPublic=true routed through it");
    }
  }

  // ---------------------------------------------------------------
  // unwrap — cause-chain traversal
  // ---------------------------------------------------------------

  @Nested
  class UnwrapChainTests {

    @Test
    void deeplyWrappedTimeoutException_isUnwrapped() {
      // 3-level wrap: RuntimeException -> RuntimeException -> TimeoutException
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
      // A pathological self-referencing throwable: cause==this. The unwrap loop
      // must terminate cleanly and return the outer throwable, mapped via the
      // catch-all to UNKNOWN/500.
      SelfCausingException loopy = new SelfCausingException();
      stubGet(webClient, "/api/v1/x", loopy);

      BackendServiceException ex =
          assertThrows(BackendServiceException.class, () -> client.get("/api/v1/x", String.class));

      assertEquals(500, ex.getStatusCode());
      assertEquals(BackendServiceException.CODE_UNKNOWN, ex.getProblemCode());
    }
  }

  // ---------------------------------------------------------------
  // POST — spot check that resilience classification works for write ops too
  // ---------------------------------------------------------------

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

  // ---------------------------------------------------------------
  // clearStaticDataCache — smoke test
  // ---------------------------------------------------------------

  @Test
  void clearStaticDataCache_completesWithoutError() {
    client.clearStaticDataCache();
    // @CacheEvict is a no-op outside a Spring context; method body only logs.
    // Calling it must not throw — the smallest reachable assertion.
  }

  // ---------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------

  /**
   * Stubs the GET fluent chain on the given WebClient so that {@code
   * client.get().uri(uri).retrieve().bodyToMono(String.class).block()} throws the supplied
   * exception.
   */
  @SuppressWarnings("unchecked")
  private static void stubGet(WebClient targetClient, String uri, Throwable toThrow) {
    WebClient.RequestHeadersUriSpec<?> uriSpec = mock(WebClient.RequestHeadersUriSpec.class);
    WebClient.RequestHeadersSpec<?> headersSpec = mock(WebClient.RequestHeadersSpec.class);
    WebClient.ResponseSpec respSpec = mock(WebClient.ResponseSpec.class);
    Mono<String> body = (Mono<String>) mock(Mono.class);

    when(targetClient.get()).thenAnswer(inv -> uriSpec);
    when(uriSpec.uri(uri)).thenAnswer(inv -> headersSpec);
    when(headersSpec.retrieve()).thenReturn(respSpec);
    when(respSpec.bodyToMono(String.class)).thenReturn(body);

    // Mockito refuses to "throw checked exception" on a method that doesn't
    // declare it. Mono.block() only throws RuntimeException — so wrap any
    // checked exception in a RuntimeException cause chain. The production
    // unwrap() finds it via getCause() traversal, which matches what Reactor
    // does in real life.
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
