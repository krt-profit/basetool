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

package de.greluc.krt.profit.basetool.frontend;

import static org.junit.jupiter.api.Assertions.*;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.reactive.function.client.WebClient;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(
    properties = {
      "app.http.connect-timeout=200ms",
      "app.http.response-timeout=500ms",
      "app.http.read-timeout=500ms",
      "app.http.write-timeout=500ms",
      "resilience4j.retry.instances.backendApi.max-attempts=3",
      "resilience4j.retry.instances.backendApi.wait-duration=50ms",
      "resilience4j.circuitbreaker.instances.backendApi.sliding-window-size=2",
      "resilience4j.circuitbreaker.instances.backendApi.minimum-number-of-calls=2",
      "resilience4j.circuitbreaker.instances.backendApi.failure-rate-threshold=50",
      "resilience4j.circuitbreaker.instances.backendApi.permitted-number-of-calls-in-half-open-state=1",
      "resilience4j.circuitbreaker.instances.backendApi.wait-duration-in-open-state=200ms",
      "resilience4j.timelimiter.instances.backendApi.timeout-duration=400ms",
      "resilience4j.bulkhead.instances.backendApi.max-concurrent-calls=10"
    })
class WebClientResilienceTest {

  private static MockWebServer server;

  @Autowired private WebClient termsDocumentClient;

  @Autowired private CircuitBreakerRegistry circuitBreakerRegistry;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  @MockitoBean private OAuth2AuthorizedClientRepository authorizedClientRepository;

  @BeforeAll
  static void startServer() throws IOException {
    server = new MockWebServer();
    server.start(0);

    Dispatcher dispatcher =
        new Dispatcher() {
          @Override
          public MockResponse dispatch(RecordedRequest request) {
            String path = request.getPath();
            if ("/api/v1/ping".equals(path)) {
              return new MockResponse().setResponseCode(500).setBody("boom");
            }
            if ("/api/v1/slow".equals(path)) {
              return new MockResponse()
                  .setBody("slow")
                  .setBodyDelay(1, java.util.concurrent.TimeUnit.SECONDS)
                  .setResponseCode(200);
            }
            if ("/api/v1/throttled".equals(path)) {
              return new MockResponse().setResponseCode(429).setBody("slow down");
            }
            return new MockResponse().setResponseCode(404);
          }
        };
    server.setDispatcher(dispatcher);
  }

  @DynamicPropertySource
  static void registerProps(DynamicPropertyRegistry registry) {
    registry.add("app.backend-url", () -> "http://localhost:" + server.getPort());
  }

  @AfterAll
  static void stopServer() throws IOException {
    if (server != null) {
      server.shutdown();
    }
  }

  @Test
  void retry_ShouldPerformMultipleAttempts_On5xx() {
    int before = server.getRequestCount();
    try {
      termsDocumentClient.get().uri("/api/v1/ping").retrieve().toBodilessEntity().block();
      fail("Expected exception due to 5xx");
    } catch (Exception ignored) {
    }
    int after = server.getRequestCount();
    assertEquals(before + 3, after, "WebClient should have retried the request");
  }

  @Test
  void circuitBreaker_ShouldOpenAndShortCircuit_SubsequentCalls() {
    for (int i = 0; i < 2; i++) {
      try {
        termsDocumentClient.get().uri("/api/v1/ping").retrieve().toBodilessEntity().block();
        fail("Expected exception");
      } catch (Exception ignored) {
      }
    }
    int before = server.getRequestCount();
    try {
      termsDocumentClient.get().uri("/api/v1/ping").retrieve().toBodilessEntity().block();
      fail("Expected CallNotPermittedException");
    } catch (Exception e) {
      assertTrue(
          e.getCause() instanceof CallNotPermittedException
              || e instanceof CallNotPermittedException,
          "Expected circuit breaker to short-circuit the call");
    }
    int after = server.getRequestCount();
    assertEquals(before, after, "Request should have been short-circuited (no new backend hit)");
  }

  /**
   * A 4xx client error (here a 429 rate-limit) must be treated as a per-call client signal, not a
   * backend-health fault: it is neither retried nor recorded as a circuit-breaker failure. Pins the
   * fix for the 2026-07-06 429 storm, where the shared {@code backendApi} breaker tripped OPEN on
   * rate-limit responses and cascaded a partial throttle into a full "Fehler beim Laden" outage
   * (ADR-0077). The breaker is reset first so an earlier test that tripped it on 5xx cannot mask
   * the assertion.
   */
  @Test
  void clientError4xx_IsNeitherRetriedNorTripsBreaker() {
    circuitBreakerRegistry.circuitBreaker("backendApi").reset();

    int beforeSingle = server.getRequestCount();
    try {
      termsDocumentClient.get().uri("/api/v1/throttled").retrieve().toBodilessEntity().block();
      fail("Expected 429 TooManyRequests");
    } catch (Exception ignored) {
    }
    assertEquals(
        beforeSingle + 1,
        server.getRequestCount(),
        "A 4xx must not be retried (one backend hit, not two)");

    for (int i = 0; i < 8; i++) {
      try {
        termsDocumentClient.get().uri("/api/v1/throttled").retrieve().toBodilessEntity().block();
      } catch (Exception ignored) {
      }
    }
    int beforeFinal = server.getRequestCount();
    try {
      termsDocumentClient.get().uri("/api/v1/throttled").retrieve().toBodilessEntity().block();
      fail("Expected 429 TooManyRequests, not a circuit-breaker short-circuit");
    } catch (Exception e) {
      assertFalse(
          e.getCause() instanceof CallNotPermittedException
              || e instanceof CallNotPermittedException,
          "A 4xx storm must not open the breaker");
    }
    assertEquals(
        beforeFinal + 1,
        server.getRequestCount(),
        "After a 4xx storm the call must still reach the backend (breaker stayed CLOSED)");
  }

  @Test
  void timeLimiter_ShouldTimeoutSlowResponses() {
    int before = server.getRequestCount();
    long start = System.currentTimeMillis();
    try {
      termsDocumentClient.get().uri("/api/v1/slow").retrieve().bodyToMono(String.class).block();
      fail("Expected timeout due to slow response");
    } catch (Exception ignored) {
    }
    long duration = System.currentTimeMillis() - start;
    int after = server.getRequestCount();
    assertTrue(duration < 2000, "Call should time out quickly");
    assertTrue(after >= before + 1, "A request should have been attempted");
  }

  /**
   * Verifies that the unconditional {@code TimeLimiterOperator} in {@link
   * de.greluc.krt.profit.basetool.frontend.config.WebClientConfig#resilienceFilter} also fires on
   * state-changing HTTP verbs. The reactive operator wraps every outbound call regardless of
   * method, so a hanging upstream on POST/PUT/DELETE/PATCH must fail fast — symmetric to the
   * GET-only {@link #timeLimiter_ShouldTimeoutSlowResponses()}. The {@code backendApi} circuit
   * breaker is reset before each iteration so an earlier test that tripped it cannot short-circuit
   * the call ahead of the time limiter and mask the timeout assertion.
   */
  @ParameterizedTest
  @ValueSource(strings = {"POST", "PUT", "DELETE", "PATCH"})
  void timeLimiter_ShouldTimeoutSlowResponses_OnWriteVerbs(String method) {
    circuitBreakerRegistry.circuitBreaker("backendApi").reset();
    int before = server.getRequestCount();
    long start = System.currentTimeMillis();
    try {
      termsDocumentClient
          .method(HttpMethod.valueOf(method))
          .uri("/api/v1/slow")
          .retrieve()
          .bodyToMono(String.class)
          .block();
      fail("Expected timeout for " + method + " due to slow response");
    } catch (Exception ignored) {
    }
    long duration = System.currentTimeMillis() - start;
    int after = server.getRequestCount();
    assertTrue(duration < 2000, method + " should time out quickly, took " + duration + "ms");
    assertTrue(after >= before + 1, "A " + method + " request should have been attempted");
  }

  /**
   * Pins the idempotency verb-guard in {@link
   * de.greluc.krt.profit.basetool.frontend.config.WebClientConfig#resilienceFilter}: the {@code
   * RetryOperator} is wired ONLY for the safe/idempotent verbs (GET/HEAD/OPTIONS/TRACE), so a
   * state-changing POST/PUT/DELETE/PATCH that receives a 5xx must be attempted <b>exactly once</b>
   * and never replayed. Contrast {@link #retry_ShouldPerformMultipleAttempts_On5xx()}, where the
   * same 500 on a GET incurs {@code 1 initial + 2 retries = 3} backend hits.
   *
   * <p>Regression guard: if the verb-guard branch (WebClientConfig ~lines 349-357) is dropped or
   * refactored to retry unconditionally, a write that receives a 500 <i>after</i> the backend
   * already committed the mutation (a bank booking / transfer, a job order) would be silently
   * double-submitted by the retry operator — a financial-correctness defect (double-charge /
   * duplicate order). The {@code backendApi} breaker is reset per iteration so an earlier test that
   * tripped it cannot short-circuit the call and mask the request count.
   */
  @ParameterizedTest
  @ValueSource(strings = {"POST", "PUT", "DELETE", "PATCH"})
  void write5xx_IsAttemptedOnce_NotRetried(String method) {
    circuitBreakerRegistry.circuitBreaker("backendApi").reset();
    int before = server.getRequestCount();
    try {
      termsDocumentClient
          .method(HttpMethod.valueOf(method))
          .uri("/api/v1/ping")
          .retrieve()
          .toBodilessEntity()
          .block();
      fail("Expected 5xx for " + method);
    } catch (Exception ignored) {
    }
    assertEquals(
        before + 1,
        server.getRequestCount(),
        method + " must be attempted exactly once on a 5xx (writes are never retried)");
  }
}
