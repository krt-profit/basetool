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

package de.greluc.krt.profit.basetool.frontend.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.net.Socket;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509TrustManager;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.ssl.NoSuchSslBundleException;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.core.env.Environment;

/**
 * Unit tests for the frontend's {@link BackendHealthIndicator}. The indicator is driven against an
 * in-process {@link MockWebServer} so the backend's {@code /actuator/health/readiness} response —
 * including failure modes such as 4xx/5xx upstream codes and connection-refused — can be staged
 * deterministically without a real backend.
 *
 * <p>Three behaviour classes are covered:
 *
 * <ol>
 *   <li>Happy path — a 2xx response from {@code /actuator/health/readiness} yields {@link
 *       Status#UP}.
 *   <li>Upstream error — a 4xx/5xx response yields {@link Status#DOWN} with the upstream status
 *       code attached as a detail for log correlation; this models the case where the backend is
 *       reachable but its OWN readiness is degraded (e.g. backend cannot reach Keycloak, Postgres,
 *       or its disk).
 *   <li>Transport failure — the upstream port is closed (server shut down before the probe), so the
 *       {@code JdkClientHttpRequestFactory} surfaces an I/O failure that maps to {@link
 *       Status#DOWN} with the exception class as a detail.
 * </ol>
 *
 * <p>Each test pins the request path to {@code /actuator/health/readiness} so a future refactor
 * that accidentally pointed the indicator at the bare {@code /actuator/health} (different
 * semantics) or some other actuator endpoint would fail loud. The trailing-slash trimming in the
 * constructor is also exercised so a {@code BACKEND_URL=https://backend:11261/} setup cannot
 * produce a double-slash URL.
 *
 * <p>End-to-end TLS is NOT exercised here — {@link MockWebServer} defaults to plain HTTP, which is
 * sufficient for the behavioural coverage above. The per-profile trust resolution added by audit
 * L-5 is covered separately below: the missing-bundle fallback (which must degrade to trust-all,
 * never the default JVM trust store) and the pinned-path hostname-skip routing inside {@code
 * HostnameAgnosticTrustManager}.
 */
class BackendHealthIndicatorTest {

  /**
   * Short timeouts keep transport-failure tests millisecond-fast — the production indicator uses
   * 2&nbsp;s / 3&nbsp;s but we do not need those margins against an in-process server.
   */
  private static final Duration TEST_CONNECT_TIMEOUT = Duration.ofMillis(500);

  private static final Duration TEST_READ_TIMEOUT = Duration.ofMillis(500);

  private MockWebServer server;
  private String backendUrl;

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    // No trailing slash, like the prod `BACKEND_URL=https://backend:11261`.
    backendUrl = server.url("").toString().replaceAll("/+$", "");
  }

  @AfterEach
  void tearDown() throws Exception {
    server.shutdown();
  }

  @Test
  void health_returns_up_when_backend_readiness_responds_200() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"status\":\"UP\"}"));
    BackendHealthIndicator indicator =
        new BackendHealthIndicator(backendUrl, TEST_CONNECT_TIMEOUT, TEST_READ_TIMEOUT);

    Health health = indicator.health();

    assertEquals(Status.UP, health.getStatus());
    assertEquals("backend-readiness", health.getDetails().get("endpoint"));

    RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
    assertNotNull(req, "indicator did not issue an HTTP request");
    assertEquals("GET", req.getMethod());
    assertEquals("/actuator/health/readiness", req.getPath());
  }

  @Test
  void health_returns_down_when_backend_readiness_responds_503() throws Exception {
    // Models the realistic chain: backend is reachable but its OWN readiness probe says DOWN
    // because (e.g.) Keycloak is unreachable from the backend's network position. Spring Boot
    // serves 503 with body `{"status":"DOWN"}` in that case.
    server.enqueue(
        new MockResponse()
            .setResponseCode(503)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"status\":\"DOWN\"}"));
    BackendHealthIndicator indicator =
        new BackendHealthIndicator(backendUrl, TEST_CONNECT_TIMEOUT, TEST_READ_TIMEOUT);

    Health health = indicator.health();

    assertEquals(Status.DOWN, health.getStatus());
    assertEquals(503, health.getDetails().get("status"));
    assertEquals("backend-readiness", health.getDetails().get("endpoint"));

    RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
    assertNotNull(req);
    assertEquals("/actuator/health/readiness", req.getPath());
  }

  @Test
  void health_returns_down_when_backend_readiness_responds_404() {
    // Models a misconfiguration where BACKEND_URL points at a service that does not expose
    // /actuator/health/readiness (e.g. wrong port, wrong host, actuator path disabled).
    server.enqueue(new MockResponse().setResponseCode(404));
    BackendHealthIndicator indicator =
        new BackendHealthIndicator(backendUrl, TEST_CONNECT_TIMEOUT, TEST_READ_TIMEOUT);

    Health health = indicator.health();

    assertEquals(Status.DOWN, health.getStatus());
    assertEquals(404, health.getDetails().get("status"));
  }

  @Test
  void health_returns_down_when_server_is_unreachable() {
    String deadBackendUrl = server.url("").toString().replaceAll("/+$", "");
    try {
      server.shutdown();
    } catch (Exception ex) {
      throw new IllegalStateException("server shutdown failed during arrange", ex);
    }
    BackendHealthIndicator indicator =
        new BackendHealthIndicator(deadBackendUrl, TEST_CONNECT_TIMEOUT, TEST_READ_TIMEOUT);

    Health health = indicator.health();

    assertEquals(Status.DOWN, health.getStatus());
    assertEquals("backend-readiness", health.getDetails().get("endpoint"));
    Object error = health.getDetails().get("error");
    assertNotNull(error, "transport failure must record an `error` detail for log correlation");
    assertNotEquals("", error.toString(), "the recorded error class name must not be empty");
  }

  @Test
  void constructor_trims_trailing_slash_so_the_probe_url_has_no_double_slash() throws Exception {
    // Same MockWebServer, but feed it WITH a trailing slash to verify the constructor's
    // canonicalisation. Without the trim, the probe would hit `//actuator/health/readiness`
    // which most servers would respond to with 404.
    String backendUrlWithTrailingSlash = server.url("/").toString();
    server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"status\":\"UP\"}"));
    BackendHealthIndicator indicator =
        new BackendHealthIndicator(
            backendUrlWithTrailingSlash, TEST_CONNECT_TIMEOUT, TEST_READ_TIMEOUT);

    Health health = indicator.health();

    assertEquals(Status.UP, health.getStatus());
    RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
    assertNotNull(req);
    assertEquals(
        "/actuator/health/readiness",
        req.getPath(),
        "trailing slash on BACKEND_URL must not propagate to the probe path");
  }

  // ─── TLS trust policy (audit L-5 follow-up) ─────────────────────────────

  @Test
  void prodWithoutBackendTrustBundle_fallsBackToWorkingTrustAllProbe() throws Exception {
    // Regression for the L-5 follow-up: in prod a MISSING `backend-trust` bundle must fall back to
    // trust-all (mirroring WebClientConfig), NOT the default JVM trust store -- the latter can
    // never
    // validate the backend's self-signed internal cert and silently forced the probe DOWN, flapping
    // the deploy. MockWebServer is plain HTTP here, so this asserts the prod constructor path
    // resolves a WORKING probe when getBundle(...) throws NoSuchSslBundleException: it must not
    // throw
    // at construction and the probe must function.
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"status\":\"UP\"}"));
    SslBundles sslBundles = mock(SslBundles.class);
    when(sslBundles.getBundle("backend-trust"))
        .thenThrow(
            new NoSuchSslBundleException("backend-trust", "not configured for this profile"));
    Environment environment = mock(Environment.class);
    when(environment.getActiveProfiles()).thenReturn(new String[] {"prod"});

    BackendHealthIndicator indicator =
        new BackendHealthIndicator(backendUrl, sslBundles, environment);

    assertEquals(Status.UP, indicator.health().getStatus());
  }

  @Test
  void hostnameAgnosticTrustManager_routesEndpointChecksThroughChainValidation() {
    // The fix's core: on the JDK HttpClient (which forces HTTPS endpoint identification and ignores
    // SSLParameters), hostname verification can only be dropped inside the trust manager. This
    // guard
    // asserts the Socket/SSLEngine-aware checkServerTrusted overloads -- the ones that would
    // perform
    // the hostname check -- delegate to the host-agnostic two-arg variant, so chain validation is
    // preserved while endpoint identity is not enforced.
    RecordingTrustManager recorder = new RecordingTrustManager();
    BackendHealthIndicator.HostnameAgnosticTrustManager tm =
        new BackendHealthIndicator.HostnameAgnosticTrustManager(recorder);
    X509Certificate[] chain = new X509Certificate[0];

    assertNotNull(tm.getAcceptedIssuers(), "accepted issuers must come from the pinned delegate");
    org.junit.jupiter.api.Assertions.assertDoesNotThrow(
        () -> {
          tm.checkServerTrusted(chain, "RSA");
          tm.checkServerTrusted(chain, "RSA", (Socket) null);
          tm.checkServerTrusted(chain, "RSA", (SSLEngine) null);
        });

    assertEquals(
        3,
        recorder.serverTwoArgCalls,
        "every checkServerTrusted overload must delegate to the host-agnostic two-arg chain check");
  }

  /** Stub {@link X509TrustManager} that counts delegations to the two-arg server-trust check. */
  private static final class RecordingTrustManager implements X509TrustManager {

    private int serverTwoArgCalls;

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) {
      // no-op: the probe is always the TLS client, never the server
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) {
      serverTwoArgCalls++;
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
      return new X509Certificate[0];
    }
  }

  // ─── Spring constructor-selection guard ─────────────────────────────────

  @Test
  void productionConstructor_isAnnotatedAutowired_soSpringCanInstantiate() {
    // Regression guard: the indicator declares TWO constructors -- the production one (the
    // @Value-injected app.backend-health-url) and a package-private test-only one with explicit
    // Duration parameters. Spring 4+ refuses to auto-select between multiple constructors and falls
    // back
    // to a no-arg default; without that, it aborts startup with
    // `NoSuchMethodException: <init>()`. The fix is exactly the @Autowired marker on the
    // production constructor; the test below asserts that marker survives any future refactor.
    long autowiredCtors =
        Arrays.stream(BackendHealthIndicator.class.getDeclaredConstructors())
            .filter(ctor -> ctor.isAnnotationPresent(Autowired.class))
            .count();
    assertEquals(
        1L,
        autowiredCtors,
        "exactly one constructor must carry @Autowired so Spring can disambiguate between the "
            + "production constructor and the visible-for-testing constructor; otherwise the prod "
            + "context fails to start with 'No default constructor found'");

    Constructor<?>[] all = BackendHealthIndicator.class.getDeclaredConstructors();
    assertEquals(
        3,
        all.length,
        "this regression test assumes three constructors: the @Autowired production one, the "
            + "visible-for-testing one, and the private shared one that wires the resolved trust "
            + "policy (audit L-5) -- if the count changes, revisit the @Autowired contract");
  }
}
