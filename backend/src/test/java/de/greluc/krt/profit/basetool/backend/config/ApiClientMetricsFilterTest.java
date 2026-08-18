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

package de.greluc.krt.profit.basetool.backend.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.support.ApiClientMetricsProperties;
import de.greluc.krt.profit.basetool.backend.support.IngestGatewayProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Tests the client attribution of {@link ApiClientMetricsFilter} (A8, REQ-OBS-018): a known {@code
 * azp} keeps its name, everything else collapses to a bounded literal, and nothing about the
 * request can make a call disappear from the count.
 */
class ApiClientMetricsFilterTest {

  private ApiClientMetricsProperties properties;
  private IngestGatewayProperties gatewayProperties;
  private MeterRegistry meterRegistry;
  private ApiClientMetricsFilter filter;

  @BeforeEach
  void setUp() {
    properties = new ApiClientMetricsProperties();
    properties.setKnownClientIds(List.of("basetool-frontend", "basetool-android"));
    gatewayProperties = new IngestGatewayProperties();
    meterRegistry = new SimpleMeterRegistry();
    filter = new ApiClientMetricsFilter(properties, gatewayProperties, meterRegistry);
  }

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  /**
   * Authenticates the context with a bearer token carrying the given authorized party.
   *
   * @param azp the {@code azp} claim, or {@code null} to omit the claim entirely
   */
  private static void authenticateWithAzp(String azp) {
    Map<String, Object> claims =
        azp == null ? Map.of("sub", "member-a") : Map.of("sub", "member-a", "azp", azp);
    Jwt jwt =
        new Jwt(
            "token", Instant.now(), Instant.now().plusSeconds(300), Map.of("alg", "none"), claims);
    SecurityContextHolder.getContext()
        .setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
  }

  /**
   * Sends one request through the filter.
   *
   * @param uri the request URI
   * @throws Exception if the chain fails
   */
  private void send(String uri) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
    request.setRequestURI(uri);
    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
  }

  /**
   * Reads the request counter for one client label.
   *
   * @param clientId the bounded {@code client_id} label
   * @return the count, or {@code 0} when the series does not exist
   */
  private double requests(String clientId) {
    Counter counter =
        meterRegistry
            .find(MetricNames.API_CLIENT_REQUESTS)
            .tag(MetricNames.TAG_CLIENT_ID, clientId)
            .counter();
    return counter == null ? 0d : counter.count();
  }

  @Test
  void aKnownClientKeepsItsName() throws Exception {
    authenticateWithAzp("basetool-android");

    send("/api/v1/missions");

    assertEquals(1.0d, requests("basetool-android"));
  }

  @Test
  @DisplayName("an unregistered client is counted, but never under its own name")
  void anUnknownClientCollapsesToTheBoundedLiteral() throws Exception {
    authenticateWithAzp("some-tool-nobody-registered");

    send("/api/v1/missions");

    assertEquals(1.0d, requests(MetricNames.CLIENT_ID_OTHER));
    assertNull(
        meterRegistry
            .find(MetricNames.API_CLIENT_REQUESTS)
            .tag(MetricNames.TAG_CLIENT_ID, "some-tool-nobody-registered")
            .counter(),
        "azp comes off a token; using it unbounded is a cardinality bomb (REQ-OBS-006)");
  }

  @Test
  void aTokenWithoutAnAzpReadsAsNoneRatherThanOther() throws Exception {
    // The two mean opposite things: 'other' is a foreign client, 'none' is a Keycloak mapper
    // regression that would blind the attribution for every client at once.
    authenticateWithAzp(null);

    send("/api/v1/missions");

    assertEquals(1.0d, requests(MetricNames.CLIENT_ID_NONE));
    assertEquals(0.0d, requests(MetricNames.CLIENT_ID_OTHER));
  }

  @Test
  void aConfiguredIngestGatewayCountsAsKnownWithoutBeingListedTwice() throws Exception {
    // Otherwise the two lists drift and the gateway silently starts reading as 'other', which is
    // exactly the series the unknown-client alert watches.
    gatewayProperties.setClientIds(List.of("basetool-ingest"));
    authenticateWithAzp("basetool-ingest");

    send("/api/v1/refinery/imports");

    assertEquals(1.0d, requests("basetool-ingest"));
  }

  @Test
  void anonymousCallersAreNotCounted() throws Exception {
    send("/api/v1/materials");

    assertNull(
        meterRegistry.find(MetricNames.API_CLIENT_REQUESTS).counter(),
        "a guest has no client identity to attribute");
  }

  @Test
  void nonApiPathsAreOutsideTheAttribution() throws Exception {
    authenticateWithAzp("basetool-frontend");

    send("/actuator/health");

    assertNull(meterRegistry.find(MetricNames.API_CLIENT_REQUESTS).counter());
  }

  @Test
  void anEncodedSpellingCannotEscapeTheCount() throws Exception {
    // REQ-SEC-029: the scope is decided on the decoded path. A client that can spell its way out of
    // the counter defeats the counter.
    authenticateWithAzp("basetool-android");

    send("/%61pi/v1/missions");

    assertEquals(1.0d, requests("basetool-android"));
  }
}
