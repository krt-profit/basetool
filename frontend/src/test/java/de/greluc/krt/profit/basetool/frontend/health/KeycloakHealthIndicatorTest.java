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

import java.lang.reflect.Constructor;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/**
 * Unit tests for the frontend's {@link KeycloakHealthIndicator}. The indicator is driven against an
 * in-process {@link MockWebServer} so the OIDC discovery response — including failure modes such as
 * 4xx/5xx upstream codes and connection-refused — can be staged deterministically without a real
 * Keycloak.
 *
 * <p>Three behaviour classes are covered:
 *
 * <ol>
 *   <li>Happy path — a 2xx response from {@code /.well-known/openid-configuration} yields {@link
 *       Status#UP}.
 *   <li>Upstream error — a 4xx/5xx response yields {@link Status#DOWN} with the upstream status
 *       code attached as a detail for log correlation.
 *   <li>Transport failure — the upstream port is closed (server shut down before the probe), so the
 *       {@code JdkClientHttpRequestFactory} surfaces an I/O failure that maps to {@link
 *       Status#DOWN} with the exception class as a detail.
 * </ol>
 *
 * <p>Each test pins the request path to {@code /.well-known/openid-configuration} so a future
 * refactor that accidentally pointed the indicator at the wrong sub-resource (e.g. the JWKS or the
 * token endpoint) would fail loud.
 */
class KeycloakHealthIndicatorTest {

  /**
   * Short timeouts keep transport-failure tests millisecond-fast — the production indicator uses
   * 2&nbsp;s / 3&nbsp;s but we do not need those margins against an in-process server.
   */
  private static final Duration TEST_CONNECT_TIMEOUT = Duration.ofMillis(500);

  private static final Duration TEST_READ_TIMEOUT = Duration.ofMillis(500);

  private MockWebServer server;
  private String issuerUri;

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    issuerUri = server.url("/realms/iri").toString();
  }

  @AfterEach
  void tearDown() throws Exception {
    server.shutdown();
  }

  @Test
  void health_returns_up_when_discovery_endpoint_responds_200() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                {
                  "issuer": "%s",
                  "authorization_endpoint": "%s/protocol/openid-connect/auth",
                  "token_endpoint": "%s/protocol/openid-connect/token",
                  "jwks_uri": "%s/protocol/openid-connect/certs"
                }
                """
                    .formatted(issuerUri, issuerUri, issuerUri, issuerUri)));
    KeycloakHealthIndicator indicator =
        new KeycloakHealthIndicator(issuerUri, TEST_CONNECT_TIMEOUT, TEST_READ_TIMEOUT);

    Health health = indicator.health();

    assertEquals(Status.UP, health.getStatus());
    assertEquals("openid-configuration", health.getDetails().get("endpoint"));

    RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
    assertNotNull(req, "indicator did not issue an HTTP request");
    assertEquals("GET", req.getMethod());
    assertEquals("/realms/iri/.well-known/openid-configuration", req.getPath());
  }

  @Test
  void health_returns_down_when_discovery_endpoint_responds_503() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(503).setBody("upstream exploded"));
    KeycloakHealthIndicator indicator =
        new KeycloakHealthIndicator(issuerUri, TEST_CONNECT_TIMEOUT, TEST_READ_TIMEOUT);

    Health health = indicator.health();

    assertEquals(Status.DOWN, health.getStatus());
    assertEquals(503, health.getDetails().get("status"));
    assertEquals("openid-configuration", health.getDetails().get("endpoint"));

    RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
    assertNotNull(req);
    assertEquals("/realms/iri/.well-known/openid-configuration", req.getPath());
  }

  @Test
  void health_returns_down_when_discovery_endpoint_responds_404() {
    server.enqueue(new MockResponse().setResponseCode(404));
    KeycloakHealthIndicator indicator =
        new KeycloakHealthIndicator(issuerUri, TEST_CONNECT_TIMEOUT, TEST_READ_TIMEOUT);

    Health health = indicator.health();

    assertEquals(Status.DOWN, health.getStatus());
    assertEquals(404, health.getDetails().get("status"));
  }

  @Test
  void health_returns_down_when_server_is_unreachable() {
    String deadIssuerUri = server.url("/realms/iri").toString();
    try {
      server.shutdown();
    } catch (Exception ex) {
      throw new IllegalStateException("server shutdown failed during arrange", ex);
    }
    KeycloakHealthIndicator indicator =
        new KeycloakHealthIndicator(deadIssuerUri, TEST_CONNECT_TIMEOUT, TEST_READ_TIMEOUT);

    Health health = indicator.health();

    assertEquals(Status.DOWN, health.getStatus());
    assertEquals("openid-configuration", health.getDetails().get("endpoint"));
    Object error = health.getDetails().get("error");
    assertNotNull(error, "transport failure must record an `error` detail for log correlation");
    assertNotEquals("", error.toString(), "the recorded error class name must not be empty");
  }

  @Test
  void productionConstructor_isAnnotatedAutowired_soSpringCanInstantiate() {
    long autowiredCtors =
        Arrays.stream(KeycloakHealthIndicator.class.getDeclaredConstructors())
            .filter(ctor -> ctor.isAnnotationPresent(Autowired.class))
            .count();
    assertEquals(
        1L,
        autowiredCtors,
        "exactly one constructor must carry @Autowired so Spring can disambiguate between the "
            + "production constructor and the visible-for-testing constructor; otherwise the prod "
            + "context fails to start with 'No default constructor found'");

    Constructor<?>[] all = KeycloakHealthIndicator.class.getDeclaredConstructors();
    assertEquals(
        2,
        all.length,
        "this regression test assumes exactly two constructors -- if a third is added, revisit "
            + "the @Autowired contract");
  }
}
