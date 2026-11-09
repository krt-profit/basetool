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

package de.greluc.krt.profit.basetool.backend.identity.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.kernel.ExternalServiceException;
import de.greluc.krt.profit.basetool.backend.platform.api.RestClientConfig;
import de.greluc.krt.profit.basetool.backend.support.BoundProperties;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.security.KeyStore;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.springframework.web.client.RestClient;

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
   * Observation registry that turns every observation into a timer on {@link #meterRegistry}, the
   * way Boot's metrics auto-configuration does in the running application.
   */
  private final ObservationRegistry observationRegistry = ObservationRegistry.create();

  {
    observationRegistry
        .observationConfig()
        .observationHandler(new DefaultMeterObservationHandler(meterRegistry));
  }

  /**
   * The production builder from {@link RestClientConfig}, wired to {@link #observationRegistry}, so
   * the tests drive the same JDK request factory and observation wiring the application does.
   *
   * @return a fresh observed builder
   */
  private RestClient.Builder observedBuilder() {
    return new RestClientConfig().restClientBuilder(observationRegistry);
  }

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

    assertDoesNotThrow(() ->
        new KeycloakService(
            BoundProperties.defaults(KeycloakSyncProperties.class),
            observedBuilder(),
            sslBundles,
            meterRegistry));
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
    KeycloakSyncProperties properties =
        BoundProperties.bind(KeycloakSyncProperties.class, Map.of("enabled", false));

    KeycloakService service =
        new KeycloakService(properties, observedBuilder(), sslBundles, meterRegistry);

    assertTrue(service.fetchUsers(Set.of()).isEmpty());
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
      KeycloakSyncProperties properties =
          BoundProperties.bind(
              KeycloakSyncProperties.class,
              Map.of(
                  "enabled",
                  true,
                  "admin-url",
                  server.url("/").toString().replaceAll("/+$", ""),
                  "realm",
                  "basetool",
                  "client-id",
                  "client",
                  "client-secret",
                  "secret"));
      server.enqueue(new MockResponse().setResponseCode(500));

      KeycloakService service =
          new KeycloakService(properties, observedBuilder(), sslBundles, meterRegistry);

      assertTrue(service.fetchUsers(Set.of()).isEmpty());
      assertEquals(
          1.0, meterRegistry.counter(IdentityMetricNames.KEYCLOAK_SYNC_FETCH_FAILURES).count());
    } finally {
      server.shutdown();
    }
  }

  /**
   * {@code fetchUsers} pages through the Keycloak Admin {@code GET /users} endpoint and returns
   * users from every page, not just the first.
   */
  @Test
  void fetchUsers_pagesThroughAllUsers_notJustTheFirstPage() throws Exception {
    when(sslBundles.getBundle("keycloak-trust"))
        .thenThrow(new NoSuchSslBundleException("keycloak-trust", "no such bundle"));
    MockWebServer server = new MockWebServer();
    server.start();
    try {
      KeycloakSyncProperties properties =
          BoundProperties.bind(
              KeycloakSyncProperties.class,
              Map.of(
                  "enabled",
                  true,
                  "admin-url",
                  server.url("/").toString().replaceAll("/+$", ""),
                  "realm",
                  "basetool",
                  "client-id",
                  "client",
                  "client-secret",
                  "secret",
                  "page-size",
                  2));

      UUID userA = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
      UUID userB = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
      UUID userC = UUID.fromString("00000000-0000-0000-0000-0000000000c3");

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

      KeycloakService service =
          new KeycloakService(properties, observedBuilder(), sslBundles, meterRegistry);

      List<KeycloakUserDto> users = service.fetchUsers(Set.of());

      assertEquals(3, users.size(), "all three users across both pages must be returned");
      assertEquals(
          Set.of("a", "b", "c"),
          users.stream().map(KeycloakUserDto::username).collect(Collectors.toSet()));

      server.takeRequest();
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
      KeycloakSyncProperties properties =
          BoundProperties.bind(
              KeycloakSyncProperties.class,
              Map.of(
                  "enabled",
                  true,
                  "admin-url",
                  server.url("/").toString().replaceAll("/+$", ""),
                  "realm",
                  "basetool",
                  "client-id",
                  "client",
                  "client-secret",
                  "secret",
                  "page-size",
                  100));

      UUID userA = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

      server.enqueue(jsonResponse("{\"access_token\":\"test-token\"}"));
      server.enqueue(
          jsonResponse("[{\"id\":\"" + userA + "\",\"username\":\"a\",\"enabled\":true}]"));
      server.enqueue(
          jsonResponse(
              "[{\"identityProvider\":\"discord\",\"userId\":\"123456789012345678\","
                  + "\"userName\":\"a#1\"}]"));

      KeycloakService service =
          new KeycloakService(properties, observedBuilder(), sslBundles, meterRegistry);

      List<KeycloakUserDto> users = service.fetchUsers(Set.of());

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
      KeycloakSyncProperties properties =
          BoundProperties.bind(
              KeycloakSyncProperties.class,
              Map.of(
                  "enabled",
                  true,
                  "admin-url",
                  server.url("/").toString().replaceAll("/+$", ""),
                  "realm",
                  "basetool",
                  "client-id",
                  "client",
                  "client-secret",
                  "secret",
                  "page-size",
                  100));

      UUID userA = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

      server.enqueue(jsonResponse("{\"access_token\":\"test-token\"}"));
      server.enqueue(
          jsonResponse("[{\"id\":\"" + userA + "\",\"username\":\"a\",\"enabled\":true}]"));
      server.enqueue(
          jsonResponse("[{\"identityProvider\":\"github\",\"userId\":\"99\",\"userName\":\"a\"}]"));

      KeycloakService service =
          new KeycloakService(properties, observedBuilder(), sslBundles, meterRegistry);

      List<KeycloakUserDto> users = service.fetchUsers(Set.of());

      assertEquals(1, users.size());
      assertNull(users.get(0).discordUserId());
    } finally {
      server.shutdown();
    }
  }

  /**
   * Incremental Discord back-fill: a roster user whose id is already known-linked locally must NOT
   * trigger a federated-identity read. With A pre-known-linked, only the token + roster page are
   * requested — no third call — and A's DTO carries a {@code null} Discord id (which {@code
   * syncUser} treats as "leave the existing link alone").
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
      KeycloakSyncProperties properties =
          BoundProperties.bind(
              KeycloakSyncProperties.class,
              Map.of(
                  "enabled",
                  true,
                  "admin-url",
                  server.url("/").toString().replaceAll("/+$", ""),
                  "realm",
                  "basetool",
                  "client-id",
                  "client",
                  "client-secret",
                  "secret",
                  "page-size",
                  100));

      UUID userA = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

      server.enqueue(jsonResponse("{\"access_token\":\"test-token\"}"));
      server.enqueue(
          jsonResponse("[{\"id\":\"" + userA + "\",\"username\":\"a\",\"enabled\":true}]"));

      KeycloakService service =
          new KeycloakService(properties, observedBuilder(), sslBundles, meterRegistry);

      List<KeycloakUserDto> users = service.fetchUsers(Set.of(userA));

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
   * REQ-SEC-026: {@code unlinkDiscordIdentity} detaches the {@code discord} federated identity via
   * {@code DELETE /users/{id}/federated-identity/discord}. It exists so the snowflake is never on
   * two Keycloak users at once — Keycloak permits that and then throws {@code
   * IllegalStateException} on every login resolving it.
   *
   * @throws Exception if the mock server cannot be started or stopped.
   */
  @Test
  void unlinkDiscordIdentity_deletesTheFederatedIdentity() throws Exception {
    when(sslBundles.getBundle("keycloak-trust"))
        .thenThrow(new NoSuchSslBundleException("keycloak-trust", "no such bundle"));
    MockWebServer server = new MockWebServer();
    server.start();
    try {
      KeycloakSyncProperties properties = writeProperties(server);
      UUID pending = UUID.fromString("00000000-0000-0000-0000-0000000000d5");
      server.enqueue(jsonResponse("{\"access_token\":\"test-token\"}"));
      server.enqueue(new MockResponse().setResponseCode(204));

      KeycloakService service =
          new KeycloakService(properties, observedBuilder(), sslBundles, meterRegistry);
      service.unlinkDiscordIdentity(pending);

      server.takeRequest();
      RecordedRequest delete = server.takeRequest();
      assertEquals("DELETE", delete.getMethod());
      assertTrue(
          delete.getPath().endsWith("/users/" + pending + "/federated-identity/discord"),
          "must DELETE the user's discord federated-identity endpoint");
    } finally {
      server.shutdown();
    }
  }

  /**
   * A {@code 404} on the detach is success, not a failure: the user is gone, or carries no Discord
   * link. Either way there is nothing to detach, which is the state the caller wants — and a link
   * flow that threw here would be unretryable after its own partial failure.
   *
   * @throws Exception if the mock server cannot be started or stopped.
   */
  @Test
  void unlinkDiscordIdentity_notFound_isSuccess() throws Exception {
    when(sslBundles.getBundle("keycloak-trust"))
        .thenThrow(new NoSuchSslBundleException("keycloak-trust", "no such bundle"));
    MockWebServer server = new MockWebServer();
    server.start();
    try {
      KeycloakSyncProperties properties = writeProperties(server);
      UUID pending = UUID.fromString("00000000-0000-0000-0000-0000000000d6");
      server.enqueue(jsonResponse("{\"access_token\":\"test-token\"}"));
      server.enqueue(new MockResponse().setResponseCode(404));

      KeycloakService service =
          new KeycloakService(properties, observedBuilder(), sslBundles, meterRegistry);

      assertDoesNotThrow(() -> service.unlinkDiscordIdentity(pending));
    } finally {
      server.shutdown();
    }
  }

  /**
   * REQ-SEC-026: {@code linkDiscordIdentity} attaches the {@code discord} federated identity to the
   * target user via {@code POST /users/{id}/federated-identity/discord}, carrying the snowflake in
   * the body. On a 204 it must not throw, and the request must target the right endpoint.
   *
   * @throws Exception if the mock server cannot be started or stopped.
   */
  @Test
  void linkDiscordIdentity_postsFederatedIdentityToTheUser() throws Exception {
    when(sslBundles.getBundle("keycloak-trust"))
        .thenThrow(new NoSuchSslBundleException("keycloak-trust", "no such bundle"));
    MockWebServer server = new MockWebServer();
    server.start();
    try {
      KeycloakSyncProperties properties = writeProperties(server);
      UUID target = UUID.fromString("00000000-0000-0000-0000-0000000000d4");
      server.enqueue(jsonResponse("{\"access_token\":\"test-token\"}"));
      server.enqueue(new MockResponse().setResponseCode(204));

      KeycloakService service =
          new KeycloakService(properties, observedBuilder(), sslBundles, meterRegistry);
      service.linkDiscordIdentity(target, "123456789012345678", "examplehandle4711");

      server.takeRequest();
      RecordedRequest post = server.takeRequest();
      assertEquals("POST", post.getMethod());
      assertTrue(
          post.getPath().endsWith("/users/" + target + "/federated-identity/discord"),
          "must POST to the user's discord federated-identity endpoint");
      String body = post.getBody().readUtf8();
      assertTrue(body.contains("123456789012345678"), "the snowflake must ride the request body");
      assertTrue(body.contains("\"identityProvider\":\"discord\""));
    } finally {
      server.shutdown();
    }
  }

  /**
   * Idempotency: a {@code 409} whose existing link carries the <em>same</em> snowflake (a retry of
   * this very link) must be treated as success — the linking orchestrator retries after a partial
   * failure, so re-attaching an already-present identity must not fail.
   *
   * @throws Exception if the mock server cannot be started or stopped.
   */
  @Test
  void linkDiscordIdentity_conflictWithSameSnowflake_isIdempotentSuccess() throws Exception {
    when(sslBundles.getBundle("keycloak-trust"))
        .thenThrow(new NoSuchSslBundleException("keycloak-trust", "no such bundle"));
    MockWebServer server = new MockWebServer();
    server.start();
    try {
      KeycloakSyncProperties properties = writeProperties(server);
      UUID target = UUID.fromString("00000000-0000-0000-0000-0000000000d4");
      server.enqueue(jsonResponse("{\"access_token\":\"test-token\"}"));
      server.enqueue(errorResponse(409));
      server.enqueue(
          jsonResponse(
              "[{\"identityProvider\":\"discord\",\"userId\":\"123456789012345678\","
                  + "\"userName\":\"examplehandle4711\"}]"));

      KeycloakService service =
          new KeycloakService(properties, observedBuilder(), sslBundles, meterRegistry);
      assertDoesNotThrow(
          () -> service.linkDiscordIdentity(target, "123456789012345678", "examplehandle4711"));
    } finally {
      server.shutdown();
    }
  }

  /**
   * A {@code 409} whose existing link carries a <em>different</em> snowflake is a genuine conflict
   * (the target is already linked to another Discord account) and must be raised, never silently
   * swallowed.
   *
   * @throws Exception if the mock server cannot be started or stopped.
   */
  @Test
  void linkDiscordIdentity_conflictWithDifferentSnowflake_throws() throws Exception {
    when(sslBundles.getBundle("keycloak-trust"))
        .thenThrow(new NoSuchSslBundleException("keycloak-trust", "no such bundle"));
    MockWebServer server = new MockWebServer();
    server.start();
    try {
      KeycloakSyncProperties properties = writeProperties(server);
      UUID target = UUID.fromString("00000000-0000-0000-0000-0000000000d4");
      server.enqueue(jsonResponse("{\"access_token\":\"test-token\"}"));
      server.enqueue(errorResponse(409));
      server.enqueue(
          jsonResponse(
              "[{\"identityProvider\":\"discord\",\"userId\":\"999999999999999999\","
                  + "\"userName\":\"someone-else\"}]"));

      KeycloakService service =
          new KeycloakService(properties, observedBuilder(), sslBundles, meterRegistry);
      assertThrows(
          ExternalServiceException.class,
          () -> service.linkDiscordIdentity(target, "123456789012345678", "examplehandle4711"));
    } finally {
      server.shutdown();
    }
  }

  /**
   * {@code deleteUser} hard-deletes the throwaway Discord user via {@code DELETE /users/{id}} on a
   * 204, targeting the right endpoint.
   *
   * @throws Exception if the mock server cannot be started or stopped.
   */
  @Test
  void deleteUser_deletesTheUser() throws Exception {
    when(sslBundles.getBundle("keycloak-trust"))
        .thenThrow(new NoSuchSslBundleException("keycloak-trust", "no such bundle"));
    MockWebServer server = new MockWebServer();
    server.start();
    try {
      KeycloakSyncProperties properties = writeProperties(server);
      UUID pending = UUID.fromString("00000000-0000-0000-0000-0000000000e5");
      server.enqueue(jsonResponse("{\"access_token\":\"test-token\"}"));
      server.enqueue(new MockResponse().setResponseCode(204));

      KeycloakService service =
          new KeycloakService(properties, observedBuilder(), sslBundles, meterRegistry);
      service.deleteUser(pending);

      server.takeRequest();
      RecordedRequest delete = server.takeRequest();
      assertEquals("DELETE", delete.getMethod());
      assertTrue(delete.getPath().endsWith("/users/" + pending));
    } finally {
      server.shutdown();
    }
  }

  /**
   * Idempotency: a {@code 404} on delete (the user is already gone) must be treated as success, so
   * a retry after a partial failure never fails on the second attempt.
   *
   * @throws Exception if the mock server cannot be started or stopped.
   */
  @Test
  void deleteUser_notFound_isIdempotentSuccess() throws Exception {
    when(sslBundles.getBundle("keycloak-trust"))
        .thenThrow(new NoSuchSslBundleException("keycloak-trust", "no such bundle"));
    MockWebServer server = new MockWebServer();
    server.start();
    try {
      KeycloakSyncProperties properties = writeProperties(server);
      UUID pending = UUID.fromString("00000000-0000-0000-0000-0000000000e5");
      server.enqueue(jsonResponse("{\"access_token\":\"test-token\"}"));
      server.enqueue(errorResponse(404));

      KeycloakService service =
          new KeycloakService(properties, observedBuilder(), sslBundles, meterRegistry);
      assertDoesNotThrow(() -> service.deleteUser(pending));
    } finally {
      server.shutdown();
    }
  }

  /**
   * REQ-XCH-008: {@code endSessionsHeldOnlyBy} deletes the member's online sessions that hold the
   * client alone, and leaves a session the client shares with the web login, reporting it.
   *
   * @throws Exception if the mock server cannot be started or stopped.
   */
  @Test
  void endSessionsHeldOnlyBy_endsTheClientsOwnSessionsAndCountsTheShared() throws Exception {
    when(sslBundles.getBundle("keycloak-trust"))
        .thenThrow(new NoSuchSslBundleException("keycloak-trust", "no such bundle"));
    MockWebServer server = new MockWebServer();
    server.start();
    try {
      KeycloakSyncProperties properties = writeProperties(server);
      UUID member = UUID.fromString("00000000-0000-0000-0000-0000000000f7");
      server.enqueue(jsonResponse("{\"access_token\":\"test-token\"}"));
      server.enqueue(
          jsonResponse(
              "[{\"id\":\"own\",\"clients\":{\"u1\":\"basetool-sc-extractor\"}},"
                  + "{\"id\":\"shared\",\"clients\":{\"u1\":\"basetool-sc-extractor\","
                  + "\"u2\":\"basetool-frontend\"}},"
                  + "{\"id\":\"web\",\"clients\":{\"u2\":\"basetool-frontend\"}}]"));
      server.enqueue(new MockResponse().setResponseCode(204));

      KeycloakService service =
          new KeycloakService(properties, observedBuilder(), sslBundles, meterRegistry);
      int shared = service.endSessionsHeldOnlyBy(member, "basetool-sc-extractor");

      server.takeRequest();
      RecordedRequest list = server.takeRequest();
      assertEquals("GET", list.getMethod());
      assertTrue(list.getPath().endsWith("/users/" + member + "/sessions"));
      RecordedRequest delete = server.takeRequest();
      assertEquals("DELETE", delete.getMethod());
      assertTrue(delete.getPath().endsWith("/admin/realms/basetool/sessions/own"));
      assertEquals(1, shared);
      assertEquals(3, server.getRequestCount(), "the shared and the web session stay");
    } finally {
      server.shutdown();
    }
  }

  /**
   * A member Keycloak no longer knows has no sessions to end.
   *
   * @throws Exception if the mock server cannot be started or stopped.
   */
  @Test
  void endSessionsHeldOnlyBy_unknownMember_endsNothing() throws Exception {
    when(sslBundles.getBundle("keycloak-trust"))
        .thenThrow(new NoSuchSslBundleException("keycloak-trust", "no such bundle"));
    MockWebServer server = new MockWebServer();
    server.start();
    try {
      KeycloakSyncProperties properties = writeProperties(server);
      server.enqueue(jsonResponse("{\"access_token\":\"test-token\"}"));
      server.enqueue(errorResponse(404));

      KeycloakService service =
          new KeycloakService(properties, observedBuilder(), sslBundles, meterRegistry);

      assertEquals(0, service.endSessionsHeldOnlyBy(UUID.randomUUID(), "basetool-sc-extractor"));
      assertEquals(2, server.getRequestCount());
    } finally {
      server.shutdown();
    }
  }

  /**
   * A server error on the session list fails the call, so the disconnect does not go ahead as if
   * the sessions had ended.
   *
   * @throws Exception if the mock server cannot be started or stopped.
   */
  @Test
  void endSessionsHeldOnlyBy_serverError_throws() throws Exception {
    when(sslBundles.getBundle("keycloak-trust"))
        .thenThrow(new NoSuchSslBundleException("keycloak-trust", "no such bundle"));
    MockWebServer server = new MockWebServer();
    server.start();
    try {
      KeycloakSyncProperties properties = writeProperties(server);
      server.enqueue(jsonResponse("{\"access_token\":\"test-token\"}"));
      server.enqueue(errorResponse(500));

      KeycloakService service =
          new KeycloakService(properties, observedBuilder(), sslBundles, meterRegistry);

      assertThrows(
          RuntimeException.class,
          () -> service.endSessionsHeldOnlyBy(UUID.randomUUID(), "basetool-sc-extractor"));
    } finally {
      server.shutdown();
    }
  }

  /**
   * REQ-XCH-008: {@code endClientInSharedSessions} calls the {@code basetool-exchange} admin
   * extension for the member and the client.
   *
   * @throws Exception if the mock server cannot be started or stopped.
   */
  @Test
  void endClientInSharedSessions_callsTheAdminExtension() throws Exception {
    when(sslBundles.getBundle("keycloak-trust"))
        .thenThrow(new NoSuchSslBundleException("keycloak-trust", "no such bundle"));
    MockWebServer server = new MockWebServer();
    server.start();
    try {
      KeycloakSyncProperties properties = writeProperties(server);
      UUID member = UUID.fromString("00000000-0000-0000-0000-0000000000f8");
      server.enqueue(jsonResponse("{\"access_token\":\"test-token\"}"));
      server.enqueue(new MockResponse().setResponseCode(204));

      KeycloakService service =
          new KeycloakService(properties, observedBuilder(), sslBundles, meterRegistry);

      assertTrue(service.endClientInSharedSessions(member, "basetool-sc-extractor"));
      server.takeRequest();
      RecordedRequest delete = server.takeRequest();
      assertEquals("DELETE", delete.getMethod());
      assertTrue(
          delete
              .getPath()
              .endsWith(
                  "/admin/realms/basetool/basetool-exchange/users/"
                      + member
                      + "/clients/basetool-sc-extractor/sessions"));
    } finally {
      server.shutdown();
    }
  }

  /**
   * A Keycloak without the extension answers {@code 404}; the call reports it instead of failing,
   * so a disconnect still goes ahead and leaves the shared sessions to the gateway.
   *
   * @throws Exception if the mock server cannot be started or stopped.
   */
  @Test
  void endClientInSharedSessions_withoutTheExtension_reportsFalse() throws Exception {
    when(sslBundles.getBundle("keycloak-trust"))
        .thenThrow(new NoSuchSslBundleException("keycloak-trust", "no such bundle"));
    MockWebServer server = new MockWebServer();
    server.start();
    try {
      KeycloakSyncProperties properties = writeProperties(server);
      server.enqueue(jsonResponse("{\"access_token\":\"test-token\"}"));
      server.enqueue(errorResponse(404));

      KeycloakService service =
          new KeycloakService(properties, observedBuilder(), sslBundles, meterRegistry);

      assertFalse(service.endClientInSharedSessions(UUID.randomUUID(), "basetool-sc-extractor"));
    } finally {
      server.shutdown();
    }
  }

  /**
   * A forbidden call propagates, so a disconnect fails instead of pretending the sessions ended.
   *
   * @throws Exception if the mock server cannot be started or stopped.
   */
  @Test
  void endClientInSharedSessions_forbidden_throws() throws Exception {
    when(sslBundles.getBundle("keycloak-trust"))
        .thenThrow(new NoSuchSslBundleException("keycloak-trust", "no such bundle"));
    MockWebServer server = new MockWebServer();
    server.start();
    try {
      KeycloakSyncProperties properties = writeProperties(server);
      server.enqueue(jsonResponse("{\"access_token\":\"test-token\"}"));
      server.enqueue(errorResponse(403));

      KeycloakService service =
          new KeycloakService(properties, observedBuilder(), sslBundles, meterRegistry);

      assertThrows(
          RuntimeException.class,
          () -> service.endClientInSharedSessions(UUID.randomUUID(), "basetool-sc-extractor"));
    } finally {
      server.shutdown();
    }
  }

  /**
   * {@code readDiscordLink} returns the target's {@code discord} snowflake and stored username —
   * the authoritative source of the incoming snowflake for the link flow, working even when the
   * {@code discord_user_id} claim mapper never persisted it locally.
   *
   * @throws Exception if the mock server cannot be started or stopped.
   */
  @Test
  void readDiscordLink_returnsSnowflakeAndUsername() throws Exception {
    when(sslBundles.getBundle("keycloak-trust"))
        .thenThrow(new NoSuchSslBundleException("keycloak-trust", "no such bundle"));
    MockWebServer server = new MockWebServer();
    server.start();
    try {
      KeycloakSyncProperties properties = writeProperties(server);
      UUID pending = UUID.fromString("00000000-0000-0000-0000-0000000000e5");
      server.enqueue(jsonResponse("{\"access_token\":\"test-token\"}"));
      server.enqueue(
          jsonResponse(
              "[{\"identityProvider\":\"discord\",\"userId\":\"123456789012345678\","
                  + "\"userName\":\"examplehandle4711\"}]"));

      KeycloakService service =
          new KeycloakService(properties, observedBuilder(), sslBundles, meterRegistry);
      Optional<KeycloakService.DiscordLink> link = service.readDiscordLink(pending);

      assertTrue(link.isPresent());
      assertEquals("123456789012345678", link.get().userId());
      assertEquals("examplehandle4711", link.get().userName());
    } finally {
      server.shutdown();
    }
  }

  /**
   * {@code readDiscordLink} maps a {@code 404} for a missing Keycloak user to an empty result, so
   * the account-linking flow can fall back to the local {@code discord_user_id}.
   *
   * @throws Exception if the mock server cannot be started or stopped.
   */
  @Test
  void readDiscordLink_userNotFound_returnsEmpty() throws Exception {
    when(sslBundles.getBundle("keycloak-trust"))
        .thenThrow(new NoSuchSslBundleException("keycloak-trust", "no such bundle"));
    MockWebServer server = new MockWebServer();
    server.start();
    try {
      KeycloakSyncProperties properties = writeProperties(server);
      UUID pending = UUID.fromString("00000000-0000-0000-0000-0000000000e5");
      server.enqueue(jsonResponse("{\"access_token\":\"test-token\"}"));
      server.enqueue(errorResponse(404));

      KeycloakService service =
          new KeycloakService(properties, observedBuilder(), sslBundles, meterRegistry);
      Optional<KeycloakService.DiscordLink> link =
          assertDoesNotThrow(() -> service.readDiscordLink(pending));

      assertTrue(link.isEmpty());
    } finally {
      server.shutdown();
    }
  }

  /**
   * Builds enabled write-path Keycloak properties pointed at the mock server (admin URL, realm,
   * client credentials) for the {@code linkDiscordIdentity} / {@code deleteUser} / {@code
   * readDiscordLink} tests.
   *
   * @param server the running mock server whose URL becomes the admin base URL.
   * @return the configured properties.
   */
  private static KeycloakSyncProperties writeProperties(MockWebServer server) {
    KeycloakSyncProperties properties =
        BoundProperties.bind(
            KeycloakSyncProperties.class,
            Map.of(
                "enabled",
                true,
                "admin-url",
                server.url("/").toString().replaceAll("/+$", ""),
                "realm",
                "basetool",
                "client-id",
                "client",
                "client-secret",
                "secret"));
    return properties;
  }

  /**
   * Every Keycloak Admin API call records an {@code http.client.requests} observation with the
   * default client key values, including {@code client.name} (REQ-OBS-009).
   *
   * @throws Exception if the mock server cannot be started or stopped.
   */
  @Test
  void fetchUsers_recordsAnHttpClientObservationPerAdminCall() throws Exception {
    when(sslBundles.getBundle("keycloak-trust"))
        .thenThrow(new NoSuchSslBundleException("keycloak-trust", "no such bundle"));
    MockWebServer server = new MockWebServer();
    server.start();
    try {
      KeycloakSyncProperties properties =
          BoundProperties.bind(
              KeycloakSyncProperties.class,
              Map.of(
                  "enabled",
                  true,
                  "admin-url",
                  server.url("/").toString().replaceAll("/+$", ""),
                  "realm",
                  "basetool",
                  "client-id",
                  "client",
                  "client-secret",
                  "secret",
                  "page-size",
                  10));
      server.enqueue(jsonResponse("{\"access_token\":\"test-token\"}"));
      server.enqueue(jsonResponse("[]"));

      KeycloakService service =
          new KeycloakService(properties, observedBuilder(), sslBundles, meterRegistry);
      service.fetchUsers(Set.of());

      long observed =
          meterRegistry
              .find("http.client.requests")
              .tag("client.name", server.getHostName())
              .tag("outcome", "SUCCESS")
              .timers()
              .stream()
              .mapToLong(Timer::count)
              .sum();
      assertEquals(2, observed, "one observation per admin call: token grant + roster page");
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

  /**
   * Builds a bodyless {@link MockResponse} with the given HTTP status code, for driving the sync's
   * error paths (a 404 role skip vs. a 5xx run-skip).
   *
   * @param code the HTTP status code to return.
   * @return the staged error response.
   */
  private static MockResponse errorResponse(int code) {
    return new MockResponse().setResponseCode(code);
  }
}
