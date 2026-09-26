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
import java.util.Set;
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
 * Tests {@link LiveSyncSubscriptionAuthorizer} against {@link MockWebServer}: 2xx allows, 403/404
 * deny, and 401 / 5xx / transport error / missing token fail open (ADR-0094), or for a
 * presence-enabled class yield {@link Decision#DENY_INDETERMINATE} rather than {@link
 * Decision#DENY}.
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
    assertThat(authorizer.authorize(operationTopic, null, PIN)).isEqualTo(Decision.ALLOW);
    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  void authorize_transportError_failsOpen() throws Exception {
    server.shutdown();
    assertThat(authorizer.authorize(operationTopic, TOKEN, PIN)).isEqualTo(Decision.ALLOW);
  }

  @Test
  void authorize_missionPresence_2xx_allows() throws Exception {
    LiveSyncTopic mission = LiveSyncTopic.parse("mission:" + UUID.randomUUID());
    server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
    assertThat(authorizer.authorize(mission, TOKEN, PIN)).isEqualTo(Decision.ALLOW);
  }

  @Test
  void authorize_missionPresence_401_failsClosed() {
    LiveSyncTopic mission = LiveSyncTopic.parse("mission:" + UUID.randomUUID());
    server.enqueue(new MockResponse().setResponseCode(401));
    Decision decision = authorizer.authorize(mission, TOKEN, PIN);
    assertThat(decision).isEqualTo(Decision.DENY_INDETERMINATE);
    assertThat(decision.denied()).isTrue();
  }

  @Test
  void authorize_missionPresence_5xx_failsClosed() {
    LiveSyncTopic mission = LiveSyncTopic.parse("mission:" + UUID.randomUUID());
    server.enqueue(new MockResponse().setResponseCode(503));
    assertThat(authorizer.authorize(mission, TOKEN, PIN)).isEqualTo(Decision.DENY_INDETERMINATE);
  }

  @Test
  void authorize_missionPresence_nullToken_failsClosedWithoutProbing() {
    LiveSyncTopic mission = LiveSyncTopic.parse("mission:" + UUID.randomUUID());
    assertThat(authorizer.authorize(mission, null, PIN)).isEqualTo(Decision.DENY_INDETERMINATE);
    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  void authorize_missionPresence_403_denies() {
    LiveSyncTopic mission = LiveSyncTopic.parse("mission:" + UUID.randomUUID());
    server.enqueue(new MockResponse().setResponseCode(403));
    assertThat(authorizer.authorize(mission, TOKEN, PIN)).isEqualTo(Decision.DENY);
  }

  @Test
  void decisionDenied_isTrueForBothRefusals_andFalseForAllow() {
    assertThat(Decision.ALLOW.denied()).isFalse();
    assertThat(Decision.DENY.denied()).isTrue();
    assertThat(Decision.DENY_INDETERMINATE.denied()).isTrue();
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

  @Test
  void authorize_orderResourceProbe_allows2xx_andTargetsThePerOrderRead() throws Exception {
    LiveSyncTopic order = LiveSyncTopic.parse("order:" + UUID.randomUUID());
    server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

    assertThat(authorizer.authorize(order, TOKEN, PIN)).isEqualTo(Decision.ALLOW);
    RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
    assertThat(request).isNotNull();
    assertThat(request.getPath()).isEqualTo("/api/v1/orders/" + order.resourceId());
    assertThat(request.getHeader("Authorization")).isEqualTo("Bearer " + TOKEN);
  }

  @Test
  void authorize_orderResourceProbe_403_denies() {
    LiveSyncTopic order = LiveSyncTopic.parse("order:" + UUID.randomUUID());
    server.enqueue(new MockResponse().setResponseCode(403));

    assertThat(authorizer.authorize(order, TOKEN, PIN)).isEqualTo(Decision.DENY);
  }

  @Test
  void authorize_globalCapabilityGranted_allows_viaCapabilitiesEndpoint() throws Exception {
    LiveSyncTopic orders = LiveSyncTopic.parse("orders");
    server.enqueue(jsonResponse("{\"canViewJobOrders\":true}"));

    assertThat(authorizer.authorize(orders, TOKEN, PIN)).isEqualTo(Decision.ALLOW);
    RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
    assertThat(request).isNotNull();
    assertThat(request.getPath()).isEqualTo("/api/v1/me/capabilities");
    assertThat(request.getHeader("Authorization")).isEqualTo("Bearer " + TOKEN);
    assertThat(request.getHeader(ActiveSquadronRelayFilter.ACTIVE_ORG_UNIT_HEADER))
        .isEqualTo(PIN.toString());
  }

  @Test
  void authorize_globalCapabilityWithheld_denies() {
    LiveSyncTopic orders = LiveSyncTopic.parse("orders");
    server.enqueue(jsonResponse("{\"canViewJobOrders\":false}"));

    assertThat(authorizer.authorize(orders, TOKEN, PIN)).isEqualTo(Decision.DENY);
  }

  @Test
  void authorize_globalCapabilityAbsent_denies() {
    LiveSyncTopic orders = LiveSyncTopic.parse("orders");
    server.enqueue(jsonResponse("{}"));

    assertThat(authorizer.authorize(orders, TOKEN, PIN)).isEqualTo(Decision.DENY);
  }

  @Test
  void authorize_globalCapabilityProbeFails_failsOpen() {
    LiveSyncTopic orders = LiveSyncTopic.parse("orders");
    server.enqueue(new MockResponse().setResponseCode(503));

    assertThat(authorizer.authorize(orders, TOKEN, PIN)).isEqualTo(Decision.ALLOW);
  }

  @Test
  void authorize_globalCapabilityNullToken_allowsWithoutProbing() {
    LiveSyncTopic orders = LiveSyncTopic.parse("orders");

    assertThat(authorizer.authorize(orders, null, PIN)).isEqualTo(Decision.ALLOW);
    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  void authorize_bankAccountPrimary2xx_allows_withoutTouchingTheFallback() throws Exception {
    LiveSyncTopic bank = LiveSyncTopic.parse("bank:" + UUID.randomUUID());
    server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

    assertThat(authorizer.authorize(bank, TOKEN, PIN)).isEqualTo(Decision.ALLOW);
    RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
    assertThat(request).isNotNull();
    assertThat(request.getPath()).isEqualTo("/api/v1/bank/accounts/" + bank.resourceId());
    assertThat(server.getRequestCount()).isEqualTo(1);
  }

  @Test
  void authorize_bankAccountPrimaryDeniesButFallbackAllows() throws Exception {
    LiveSyncTopic bank = LiveSyncTopic.parse("bank:" + UUID.randomUUID());
    server.enqueue(new MockResponse().setResponseCode(403));
    server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

    assertThat(authorizer.authorize(bank, TOKEN, PIN)).isEqualTo(Decision.ALLOW);
    RecordedRequest primary = server.takeRequest(2, TimeUnit.SECONDS);
    assertThat(primary.getPath()).isEqualTo("/api/v1/bank/accounts/" + bank.resourceId());
    RecordedRequest fallback = server.takeRequest(2, TimeUnit.SECONDS);
    assertThat(fallback.getPath())
        .isEqualTo("/api/v1/org-units/bank/accounts/" + bank.resourceId());
  }

  @Test
  void authorize_bankAccountBothReadsRefuse_denies() {
    LiveSyncTopic bank = LiveSyncTopic.parse("bank:" + UUID.randomUUID());
    server.enqueue(new MockResponse().setResponseCode(404));
    server.enqueue(new MockResponse().setResponseCode(403));

    assertThat(authorizer.authorize(bank, TOKEN, PIN)).isEqualTo(Decision.DENY);
  }

  @Test
  void authorize_bankStaffWithBankEmployeeRole_allows_withoutProbing() {
    LiveSyncTopic staff = LiveSyncTopic.parse("bank");
    assertThat(authorizer.authorize(staff, TOKEN, PIN, Set.of("ROLE_BANK_EMPLOYEE")))
        .isEqualTo(Decision.ALLOW);
    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  void authorize_bankStaffWithManagementRoleOnly_allows() {
    LiveSyncTopic staff = LiveSyncTopic.parse("bank");
    assertThat(authorizer.authorize(staff, TOKEN, PIN, Set.of("ROLE_BANK_MANAGEMENT")))
        .isEqualTo(Decision.ALLOW);
  }

  @Test
  void authorize_bankStaffWithoutABankRole_denies_withoutProbing() {
    LiveSyncTopic staff = LiveSyncTopic.parse("bank");
    assertThat(authorizer.authorize(staff, TOKEN, PIN, Set.of("ROLE_KRT_MEMBER")))
        .isEqualTo(Decision.DENY);
    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  void authorize_bankStaffNullAuthorities_failsOpen() {
    LiveSyncTopic staff = LiveSyncTopic.parse("bank");
    assertThat(authorizer.authorize(staff, TOKEN, PIN, null)).isEqualTo(Decision.ALLOW);
  }

  @Test
  void authorize_orgUnitBankWithMemberRole_allows() {
    LiveSyncTopic orgUnit = LiveSyncTopic.parse("orgunit-bank");
    assertThat(authorizer.authorize(orgUnit, TOKEN, PIN, Set.of("ROLE_KRT_MEMBER")))
        .isEqualTo(Decision.ALLOW);
    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  void authorize_orgUnitBankWithOnlyTheNoRoleMarker_denies() {
    LiveSyncTopic orgUnit = LiveSyncTopic.parse("orgunit-bank");
    assertThat(authorizer.authorize(orgUnit, TOKEN, PIN, Set.of("ROLE_NO_ROLE")))
        .isEqualTo(Decision.DENY);
  }

  @Test
  void authorize_materialboard_allowsOnAuthenticationAlone_withoutProbing() {
    LiveSyncTopic board = LiveSyncTopic.parse("materialboard");
    assertThat(authorizer.authorize(board, TOKEN, PIN, null)).isEqualTo(Decision.ALLOW);
    assertThat(authorizer.authorize(board, null, null, Set.of("ROLE_NO_ROLE")))
        .isEqualTo(Decision.ALLOW);
    assertThat(server.getRequestCount()).isZero();
  }

  private static MockResponse jsonResponse(String body) {
    return new MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body);
  }
}
