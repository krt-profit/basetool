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

package de.greluc.krt.profit.basetool.frontend.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.logging.ActiveSquadronRelayFilter;
import de.greluc.krt.profit.basetool.frontend.websocket.LiveSyncSubscriptionAuthorizer.Decision;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Tests for {@link LiveSyncSubscriptionAuthorizer} driven by {@link MockWebServer}: a 2xx probe
 * allows the subscribe (replaying the captured bearer + pin), an explicit 403/404 denies it, and a
 * 401 / 5xx / transport error / missing token all fail open (ADR-0092).
 */
class LiveSyncSubscriptionAuthorizerTest {

  private static final String TOKEN = "captured-access-token";
  private static final UUID PIN = UUID.fromString("11111111-1111-1111-1111-111111111111");

  private MockWebServer server;
  private LiveSyncSubscriptionAuthorizer authorizer;
  private LiveSyncTopic operationTopic;

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    WebClient webClient = WebClient.builder().baseUrl(server.url("/").toString()).build();
    authorizer = new LiveSyncSubscriptionAuthorizer(webClient);
    operationTopic = LiveSyncTopic.parse("operation:" + UUID.randomUUID());
  }

  @AfterEach
  void tearDown() throws Exception {
    server.shutdown();
  }

  @Test
  void authorize_2xx_allows_andReplaysBearerAndPin() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

    Decision decision = authorizer.authorize(operationTopic, TOKEN, PIN);

    assertThat(decision).isEqualTo(Decision.ALLOW);
    RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
    assertThat(request).isNotNull();
    assertThat(request.getPath()).isEqualTo("/api/v1/operations/" + operationTopic.resourceId());
    assertThat(request.getHeader("Authorization")).isEqualTo("Bearer " + TOKEN);
    assertThat(request.getHeader(ActiveSquadronRelayFilter.ACTIVE_ORG_UNIT_HEADER))
        .isEqualTo(PIN.toString());
  }

  @Test
  void authorize_403_denies() {
    server.enqueue(new MockResponse().setResponseCode(403));
    assertThat(authorizer.authorize(operationTopic, TOKEN, PIN)).isEqualTo(Decision.DENY);
  }

  @Test
  void authorize_404_denies() {
    server.enqueue(new MockResponse().setResponseCode(404));
    assertThat(authorizer.authorize(operationTopic, TOKEN, PIN)).isEqualTo(Decision.DENY);
  }

  @Test
  void authorize_401_failsOpen() {
    // A 401 means the captured token expired, not that the user lacks access: fail open (opaque
    // keys
    // only; each fragment re-pull re-authorizes with a fresh token).
    server.enqueue(new MockResponse().setResponseCode(401));
    assertThat(authorizer.authorize(operationTopic, TOKEN, PIN)).isEqualTo(Decision.ALLOW);
  }

  @Test
  void authorize_5xx_failsOpen() {
    server.enqueue(new MockResponse().setResponseCode(503));
    assertThat(authorizer.authorize(operationTopic, TOKEN, PIN)).isEqualTo(Decision.ALLOW);
  }

  @Test
  void authorize_nullToken_allowsWithoutProbing() {
    // No captured token snapshot: fail open without issuing a probe at all.
    assertThat(authorizer.authorize(operationTopic, null, PIN)).isEqualTo(Decision.ALLOW);
    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  void authorize_transportError_failsOpen() throws Exception {
    // A connection error (server gone) is a transient failure, not an authorization denial.
    server.shutdown();
    assertThat(authorizer.authorize(operationTopic, TOKEN, PIN)).isEqualTo(Decision.ALLOW);
  }

  @Test
  void authorize_omitsPinHeader_whenNoActivePin() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

    authorizer.authorize(operationTopic, TOKEN, null);

    RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
    assertThat(request).isNotNull();
    assertThat(request.getHeader(ActiveSquadronRelayFilter.ACTIVE_ORG_UNIT_HEADER)).isNull();
    assertThat(request.getHeader("Authorization")).isEqualTo("Bearer " + TOKEN);
  }
}
