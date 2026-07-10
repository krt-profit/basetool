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

package de.greluc.krt.profit.basetool.backend.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.config.KeycloakSyncProperties;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.model.dto.KeycloakUserDto;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.security.KeyStore;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ssl.NoSuchSslBundleException;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.ssl.SslStoreBundle;

/**
 * Unit tests for {@link KeycloakService}'s TLS-trust wiring: the constructor must pin the {@code
 * keycloak-trust} truststore when the bundle exists (production) and quietly fall back to the
 * default client when it does not (dev/test plain-HTTP admin URL).
 */
@ExtendWith(MockitoExtension.class)
class KeycloakServiceTest {

  @Mock private SslBundles sslBundles;

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  /**
   * When the {@code keycloak-trust} SSL bundle is present, the constructor must parse its
   * truststore and build the pinned request factory without error.
   */
  @Test
  void constructor_withKeycloakTrustBundle_buildsTrustedClient() throws Exception {
    KeyStore truststore = KeyStore.getInstance("PKCS12");
    truststore.load(null, null);
    SslStoreBundle stores = mock(SslStoreBundle.class);
    when(stores.getTrustStore()).thenReturn(truststore);
    SslBundle bundle = mock(SslBundle.class);
    when(bundle.getStores()).thenReturn(stores);
    when(sslBundles.getBundle("keycloak-trust")).thenReturn(bundle);

    assertDoesNotThrow(
        () -> new KeycloakService(new KeycloakSyncProperties(), sslBundles, meterRegistry));
    verify(sslBundles).getBundle("keycloak-trust");
  }

  /**
   * When no {@code keycloak-trust} bundle is configured (dev/test), the constructor must swallow
   * the {@link NoSuchSslBundleException} and the service must still operate — here a disabled sync
   * simply returns no users instead of failing.
   */
  @Test
  void fetchUsers_withoutBundle_disabledSync_returnsEmpty() {
    when(sslBundles.getBundle("keycloak-trust"))
        .thenThrow(new NoSuchSslBundleException("keycloak-trust", "no such bundle"));
    KeycloakSyncProperties properties = new KeycloakSyncProperties();
    properties.setEnabled(false);

    KeycloakService service = new KeycloakService(properties, sslBundles, meterRegistry);

    assertTrue(service.fetchUsers(List.of(), Set.of()).isEmpty());
    verify(sslBundles).getBundle("keycloak-trust");
  }

  /**
   * REQ-OBS-011: when the roster fetch throws (here the client-credentials token request returns
   * 500), {@code fetchUsers} swallows it to an empty list and increments {@code
   * basetool_keycloak_sync_fetch_failures_total} so a Keycloak outage is distinguishable from a
   * legitimately empty roster (which returns empty <em>without</em> throwing).
   */
  @Test
  void fetchUsers_onFetchFailure_incrementsFailureCounterAndReturnsEmpty() throws Exception {
    when(sslBundles.getBundle("keycloak-trust"))
        .thenThrow(new NoSuchSslBundleException("keycloak-trust", "no such bundle"));
    MockWebServer server = new MockWebServer();
    server.start();
    try {
      KeycloakSyncProperties properties = new KeycloakSyncProperties();
      properties.setEnabled(true);
      properties.setAdminUrl(server.url("/").toString().replaceAll("/+$", ""));
      properties.setRealm("iri");
      properties.setClientId("client");
      properties.setClientSecret("secret");
      // The client-credentials token request fails → getAccessToken throws → fetchUsers swallows.
      server.enqueue(new MockResponse().setResponseCode(500));

      KeycloakService service = new KeycloakService(properties, sslBundles, meterRegistry);

      assertTrue(service.fetchUsers(List.of(), Set.of()).isEmpty());
      assertEquals(1.0, meterRegistry.counter(MetricNames.KEYCLOAK_SYNC_FETCH_FAILURES).count());
    } finally {
      server.shutdown();
    }
  }

  /**
   * The Keycloak Admin {@code GET /users} endpoint caps each response at a server-side maximum, so
   * the sync must page through {@code first}/{@code max}. Regression guard for the truncation bug:
   * with a page size of 2 and three users spread across two pages, {@code fetchUsers} must return
   * all three — not just the first page — otherwise {@code UserSyncTask} would wrongly flag the
   * third user as missing and soft-delete it.
   */
  @Test
  void fetchUsers_pagesThroughAllUsers_notJustTheFirstPage() throws Exception {
    when(sslBundles.getBundle("keycloak-trust"))
        .thenThrow(new NoSuchSslBundleException("keycloak-trust", "no such bundle"));
    MockWebServer server = new MockWebServer();
    server.start();
    try {
      KeycloakSyncProperties properties = new KeycloakSyncProperties();
      properties.setEnabled(true);
      properties.setAdminUrl(server.url("/").toString().replaceAll("/+$", ""));
      properties.setRealm("iri");
      properties.setClientId("client");
      properties.setClientSecret("secret");
      properties.setPageSize(2);

      UUID userA = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
      UUID userB = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
      UUID userC = UUID.fromString("00000000-0000-0000-0000-0000000000c3");

      // 1) client-credentials token, 2) full first page (== pageSize → keep paging),
      // 3) short second page (< pageSize → stop). No role names are passed, so no /roles member
      // page is requested; then one federated-identity lookup per roster user (A, B, C) since none
      // is pre-known-linked — all empty here, so this stays a pure pagination check.
      server.enqueue(jsonResponse("{\"access_token\":\"test-token\"}"));
      server.enqueue(
          jsonResponse(
              "[{\"id\":\""
                  + userA
                  + "\",\"username\":\"a\",\"enabled\":true},{\"id\":\""
                  + userB
                  + "\",\"username\":\"b\",\"enabled\":true}]"));
      server.enqueue(
          jsonResponse("[{\"id\":\"" + userC + "\",\"username\":\"c\",\"enabled\":true}]"));
      server.enqueue(jsonResponse("[]"));
      server.enqueue(jsonResponse("[]"));
      server.enqueue(jsonResponse("[]"));

      KeycloakService service = new KeycloakService(properties, sslBundles, meterRegistry);

      List<KeycloakUserDto> users = service.fetchUsers(List.of(), Set.of());

      assertEquals(3, users.size(), "all three users across both pages must be returned");
      assertEquals(
          Set.of("a", "b", "c"),
          users.stream().map(KeycloakUserDto::username).collect(Collectors.toSet()));

      server.takeRequest(); // token
      RecordedRequest firstPage = server.takeRequest();
      assertTrue(firstPage.getPath().contains("first=0"), "first page must request first=0");
      assertTrue(firstPage.getPath().contains("max=2"), "first page must bind the page size");
      RecordedRequest secondPage = server.takeRequest();
      assertTrue(secondPage.getPath().contains("first=2"), "second page must advance the offset");
    } finally {
      server.shutdown();
    }
  }

  /**
   * The sync joins each user's {@code discord} federated-identity link (Admin API {@code
   * /federated-identity}) onto the DTO, so an account that linked Discord is recognised regardless
   * of how it linked (REQ-DATA-006). With one user whose federated-identity list carries a {@code
   * discord} entry, the returned DTO must carry that snowflake.
   *
   * @throws Exception if the mock server cannot be started or stopped.
   */
  @Test
  void fetchUsers_attachesDiscordFederatedId() throws Exception {
    when(sslBundles.getBundle("keycloak-trust"))
        .thenThrow(new NoSuchSslBundleException("keycloak-trust", "no such bundle"));
    MockWebServer server = new MockWebServer();
    server.start();
    try {
      KeycloakSyncProperties properties = new KeycloakSyncProperties();
      properties.setEnabled(true);
      properties.setAdminUrl(server.url("/").toString().replaceAll("/+$", ""));
      properties.setRealm("iri");
      properties.setClientId("client");
      properties.setClientSecret("secret");
      properties.setPageSize(100);

      UUID userA = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

      // token, single short page (stop); no role names → no /roles member page; then federated
      // identities A (one discord link) since A is not pre-known-linked.
      server.enqueue(jsonResponse("{\"access_token\":\"test-token\"}"));
      server.enqueue(
          jsonResponse("[{\"id\":\"" + userA + "\",\"username\":\"a\",\"enabled\":true}]"));
      server.enqueue(
          jsonResponse(
              "[{\"identityProvider\":\"discord\",\"userId\":\"123456789012345678\","
                  + "\"userName\":\"a#1\"}]"));

      KeycloakService service = new KeycloakService(properties, sslBundles, meterRegistry);

      List<KeycloakUserDto> users = service.fetchUsers(List.of(), Set.of());

      assertEquals(1, users.size());
      assertEquals("123456789012345678", users.get(0).discordUserId());
    } finally {
      server.shutdown();
    }
  }

  /**
   * A user whose only federated identity is a non-Discord provider has no Discord link, so the
   * DTO's {@code discordUserId} must be {@code null} — the indicator must never light up off
   * another IdP.
   *
   * @throws Exception if the mock server cannot be started or stopped.
   */
  @Test
  void fetchUsers_ignoresNonDiscordFederatedIdentity() throws Exception {
    when(sslBundles.getBundle("keycloak-trust"))
        .thenThrow(new NoSuchSslBundleException("keycloak-trust", "no such bundle"));
    MockWebServer server = new MockWebServer();
    server.start();
    try {
      KeycloakSyncProperties properties = new KeycloakSyncProperties();
      properties.setEnabled(true);
      properties.setAdminUrl(server.url("/").toString().replaceAll("/+$", ""));
      properties.setRealm("iri");
      properties.setClientId("client");
      properties.setClientSecret("secret");
      properties.setPageSize(100);

      UUID userA = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

      server.enqueue(jsonResponse("{\"access_token\":\"test-token\"}"));
      server.enqueue(
          jsonResponse("[{\"id\":\"" + userA + "\",\"username\":\"a\",\"enabled\":true}]"));
      server.enqueue(
          jsonResponse("[{\"identityProvider\":\"github\",\"userId\":\"99\",\"userName\":\"a\"}]"));

      KeycloakService service = new KeycloakService(properties, sslBundles, meterRegistry);

      List<KeycloakUserDto> users = service.fetchUsers(List.of(), Set.of());

      assertEquals(1, users.size());
      assertNull(users.get(0).discordUserId());
    } finally {
      server.shutdown();
    }
  }

  /**
   * Role membership is resolved role-indexed: for each mappable role name, {@code fetchUsers}
   * queries {@code GET /roles/{name}/users} once and maps the role onto every returned member,
   * rather than reading each user's role mapping individually. With role {@code ADMIN} listing user
   * A as a member, A's DTO must carry {@code ADMIN}, and the request path must target the role's
   * member endpoint.
   *
   * @throws Exception if the mock server cannot be started or stopped.
   */
  @Test
  void fetchUsers_resolvesRolesRoleIndexed() throws Exception {
    when(sslBundles.getBundle("keycloak-trust"))
        .thenThrow(new NoSuchSslBundleException("keycloak-trust", "no such bundle"));
    MockWebServer server = new MockWebServer();
    server.start();
    try {
      KeycloakSyncProperties properties = new KeycloakSyncProperties();
      properties.setEnabled(true);
      properties.setAdminUrl(server.url("/").toString().replaceAll("/+$", ""));
      properties.setRealm("iri");
      properties.setClientId("client");
      properties.setClientSecret("secret");
      properties.setPageSize(100);

      UUID userA = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

      // token, single short roster page (stop), one ADMIN member page (A, short → stop), then
      // federated identities A (empty, A is not pre-known-linked).
      server.enqueue(jsonResponse("{\"access_token\":\"test-token\"}"));
      server.enqueue(
          jsonResponse("[{\"id\":\"" + userA + "\",\"username\":\"a\",\"enabled\":true}]"));
      server.enqueue(jsonResponse("[{\"id\":\"" + userA + "\",\"username\":\"a\"}]"));
      server.enqueue(jsonResponse("[]"));

      KeycloakService service = new KeycloakService(properties, sslBundles, meterRegistry);

      List<KeycloakUserDto> users = service.fetchUsers(List.of("ADMIN"), Set.of());

      assertEquals(1, users.size());
      assertEquals(Set.of("ADMIN"), users.get(0).roles());

      server.takeRequest(); // token
      server.takeRequest(); // roster page
      RecordedRequest rolePage = server.takeRequest();
      assertTrue(
          rolePage.getPath().contains("/roles/ADMIN/users"),
          "role membership must be read from the role's member endpoint");
    } finally {
      server.shutdown();
    }
  }

  /**
   * Incremental Discord back-fill: a roster user whose id is already known-linked locally must NOT
   * trigger a federated-identity read. With no role names and A pre-known-linked, only the token +
   * roster page are requested — no third call — and A's DTO carries a {@code null} Discord id
   * (which {@code syncUser} treats as "leave the existing link alone").
   *
   * @throws Exception if the mock server cannot be started or stopped.
   */
  @Test
  void fetchUsers_skipsDiscordReadForAlreadyLinkedUsers() throws Exception {
    when(sslBundles.getBundle("keycloak-trust"))
        .thenThrow(new NoSuchSslBundleException("keycloak-trust", "no such bundle"));
    MockWebServer server = new MockWebServer();
    server.start();
    try {
      KeycloakSyncProperties properties = new KeycloakSyncProperties();
      properties.setEnabled(true);
      properties.setAdminUrl(server.url("/").toString().replaceAll("/+$", ""));
      properties.setRealm("iri");
      properties.setClientId("client");
      properties.setClientSecret("secret");
      properties.setPageSize(100);

      UUID userA = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

      server.enqueue(jsonResponse("{\"access_token\":\"test-token\"}"));
      server.enqueue(
          jsonResponse("[{\"id\":\"" + userA + "\",\"username\":\"a\",\"enabled\":true}]"));

      KeycloakService service = new KeycloakService(properties, sslBundles, meterRegistry);

      List<KeycloakUserDto> users = service.fetchUsers(List.of(), Set.of(userA));

      assertEquals(1, users.size());
      assertNull(users.get(0).discordUserId());
      assertEquals(
          2,
          server.getRequestCount(),
          "an already-linked user must not trigger a federated-identity read");
    } finally {
      server.shutdown();
    }
  }

  /**
   * Builds a 200 JSON {@link MockResponse} with the given body.
   *
   * @param body the JSON payload.
   * @return the staged response.
   */
  private static MockResponse jsonResponse(String body) {
    return new MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body);
  }
}
