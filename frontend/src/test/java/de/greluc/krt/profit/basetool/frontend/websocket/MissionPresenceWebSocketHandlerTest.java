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

import de.greluc.krt.profit.basetool.frontend.service.MissionPresenceService;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests for {@link MissionPresenceWebSocketHandler}.
 *
 * <p>Drives the handler through a hand-rolled {@link FakeSession} that records outbound messages in
 * a buffer so the JSON wire format, mission-id extraction, principal-resolution, and broadcast
 * behaviour can be verified without starting a real servlet container.
 */
class MissionPresenceWebSocketHandlerTest {

  private MissionPresenceService service;
  private ObjectMapper objectMapper;
  private MissionPresenceWebSocketHandler handler;

  @BeforeEach
  void setUp() {
    service = new MissionPresenceService();
    objectMapper = JsonMapper.builder().build();
    handler = new MissionPresenceWebSocketHandler(service, objectMapper);
  }

  @Test
  void extractMissionId_acceptsCanonicalPath() {
    UUID id = UUID.randomUUID();
    UUID extracted =
        MissionPresenceWebSocketHandler.extractMissionId(
            URI.create("ws://localhost/ws/missions/" + id + "/presence"));
    assertThat(extracted).isEqualTo(id);
  }

  @Test
  void extractMissionId_rejectsWrongShape() {
    assertThat(MissionPresenceWebSocketHandler.extractMissionId(URI.create("ws://x/ws/missions")))
        .isNull();
    assertThat(
            MissionPresenceWebSocketHandler.extractMissionId(
                URI.create("ws://x/api/missions/" + UUID.randomUUID() + "/presence")))
        .isNull();
    assertThat(
            MissionPresenceWebSocketHandler.extractMissionId(
                URI.create("ws://x/ws/missions/not-a-uuid/presence")))
        .isNull();
  }

  @Test
  void focusMessage_recordsPresence_andBroadcastsSnapshot() throws Exception {
    UUID missionId = UUID.randomUUID();
    FakeSession session = openSession(missionId, oidcUser("user-1", "Alice"));

    session.sent.clear();
    handler.handleTextMessage(
        session, new TextMessage("{\"type\":\"focus\",\"sectionKey\":\"details\"}"));

    assertThat(service.get(missionId, "details", "user-1")).isNotNull();
    assertThat(service.get(missionId, "details", "user-1").displayName()).isEqualTo("Alice");

    JsonNode broadcast = lastBroadcast(session);
    assertThat(broadcast.get("type").asString()).isEqualTo("presence");
    JsonNode editors = broadcast.get("sections").get("details");
    assertThat(editors).isNotNull();
    assertThat(editors.get(0).get("userId").asString()).isEqualTo("user-1");
    assertThat(editors.get(0).get("displayName").asString()).isEqualTo("Alice");
  }

  @Test
  void blurMessage_clearsPresence_andBroadcastsEmptySection() throws Exception {
    UUID missionId = UUID.randomUUID();
    FakeSession session = openSession(missionId, oidcUser("user-1", "Alice"));
    handler.handleTextMessage(
        session, new TextMessage("{\"type\":\"focus\",\"sectionKey\":\"details\"}"));

    session.sent.clear();
    handler.handleTextMessage(
        session, new TextMessage("{\"type\":\"blur\",\"sectionKey\":\"details\"}"));

    assertThat(service.get(missionId, "details", "user-1")).isNull();
    JsonNode broadcast = lastBroadcast(session);
    // After the blur the snapshot has no sections at all (the entry was the only one).
    assertThat(broadcast.get("type").asString()).isEqualTo("presence");
    assertThat(broadcast.get("sections").size()).isZero();
  }

  @Test
  void heartbeat_doesNotBroadcast_whenUserAlreadyKnown() throws Exception {
    UUID missionId = UUID.randomUUID();
    FakeSession session = openSession(missionId, oidcUser("user-1", "Alice"));
    handler.handleTextMessage(
        session, new TextMessage("{\"type\":\"focus\",\"sectionKey\":\"details\"}"));

    int countAfterFocus = session.sent.size();
    handler.handleTextMessage(
        session, new TextMessage("{\"type\":\"heartbeat\",\"sectionKey\":\"details\"}"));

    // Heartbeat from an already-known editor must NOT trigger a broadcast — generating one
    // frame per heartbeat per connected client per mission would be wasteful and visually
    // pointless because the state didn't change.
    assertThat(session.sent).hasSize(countAfterFocus);
  }

  @Test
  void malformedPayload_isSilentlyDropped() throws Exception {
    UUID missionId = UUID.randomUUID();
    FakeSession session = openSession(missionId, oidcUser("user-1", "Alice"));
    session.sent.clear();

    handler.handleTextMessage(session, new TextMessage("{this is not json"));
    handler.handleTextMessage(session, new TextMessage("{\"type\":null}"));
    handler.handleTextMessage(
        session, new TextMessage("{\"type\":\"unknown\",\"sectionKey\":\"x\"}"));

    // No state mutation, no broadcasts.
    assertThat(service.trackedMissions()).isEmpty();
    assertThat(session.sent).isEmpty();
  }

  @Test
  void connectionClosed_clearsAllPresence_andBroadcastsToRemainingClients() throws Exception {
    UUID missionId = UUID.randomUUID();
    FakeSession aliceSession = openSession(missionId, oidcUser("user-1", "Alice"));
    FakeSession bobSession = openSession(missionId, oidcUser("user-2", "Bob"));
    handler.handleTextMessage(
        aliceSession, new TextMessage("{\"type\":\"focus\",\"sectionKey\":\"details\"}"));
    handler.handleTextMessage(
        bobSession, new TextMessage("{\"type\":\"focus\",\"sectionKey\":\"schedule\"}"));

    bobSession.sent.clear();
    aliceSession.open = false;
    handler.afterConnectionClosed(aliceSession, CloseStatus.NORMAL);

    // Alice's presence is gone on every section.
    assertThat(service.get(missionId, "details", "user-1")).isNull();
    // Bob's presence is untouched.
    assertThat(service.get(missionId, "schedule", "user-2")).isNotNull();
    // Bob's session received an updated snapshot (no Alice in `details`).
    JsonNode broadcast = lastBroadcast(bobSession);
    assertThat(broadcast.get("sections").has("details")).isFalse();
    assertThat(broadcast.get("sections").get("schedule").get(0).get("userId").asString())
        .isEqualTo("user-2");
  }

  @Test
  void connectionClosed_keepsPresence_whenSameUserHasAnotherOpenTab() throws Exception {
    UUID missionId = UUID.randomUUID();
    FakeSession tabA = openSession(missionId, oidcUser("user-1", "Alice"));
    FakeSession tabB = openSession(missionId, oidcUser("user-1", "Alice"));
    handler.handleTextMessage(
        tabA, new TextMessage("{\"type\":\"focus\",\"sectionKey\":\"details\"}"));

    tabA.open = false;
    handler.afterConnectionClosed(tabA, CloseStatus.NORMAL);

    // The other tab is still alive — Alice's "details" presence must survive.
    assertThat(service.get(missionId, "details", "user-1")).isNotNull();
    assertThat(tabB.isOpen()).isTrue();
  }

  @Test
  void changedSignal_isRelayedToOtherSessions_butNotToOrigin() throws Exception {
    UUID missionId = UUID.randomUUID();
    FakeSession alice = openSession(missionId, oidcUser("user-1", "Alice"));
    FakeSession bob = openSession(missionId, oidcUser("user-2", "Bob"));
    alice.sent.clear();
    bob.sent.clear();

    handler.handleTextMessage(
        alice, new TextMessage("{\"type\":\"changed\",\"sections\":[\"crew\",\"finance\"]}"));

    // The originator already applied its own change locally — it must not receive the echo.
    assertThat(alice.sent).isEmpty();
    // Every other socket on the same mission gets the section keys (and nothing else).
    JsonNode relayed = lastBroadcast(bob);
    assertThat(relayed.get("type").asString()).isEqualTo("changed");
    List<String> sections = new ArrayList<>();
    relayed.get("sections").forEach(node -> sections.add(node.asString()));
    assertThat(sections).containsExactly("crew", "finance");
  }

  @Test
  void changedSignal_dropsUnknownKeysAndDeduplicates() throws Exception {
    UUID missionId = UUID.randomUUID();
    FakeSession alice = openSession(missionId, oidcUser("user-1", "Alice"));
    FakeSession bob = openSession(missionId, oidcUser("user-2", "Bob"));
    bob.sent.clear();

    handler.handleTextMessage(
        alice,
        new TextMessage(
            "{\"type\":\"changed\",\"sections\":[\"crew\",\"bogus\",\"crew\",\"mgmt\",42]}"));

    JsonNode relayed = lastBroadcast(bob);
    List<String> sections = new ArrayList<>();
    relayed.get("sections").forEach(node -> sections.add(node.asString()));
    // "bogus" and the non-string 42 are dropped; the duplicate "crew" appears once.
    assertThat(sections).containsExactly("crew", "mgmt");
  }

  @Test
  void changedSignal_relaysEverySectionOfTheMissionSeamMap() throws Exception {
    UUID missionId = UUID.randomUUID();
    FakeSession alice = openSession(missionId, oidcUser("user-1", "Alice"));
    FakeSession bob = openSession(missionId, oidcUser("user-2", "Bob"));
    bob.sent.clear();

    // Every key of the MISSION_SECTIONS seam map in mission-detail.js must pass the relay
    // whitelist — steps/objectives/frequencies were once dropped here, leaving peers' Verwaltung
    // editors stale until a manual reload (REQ-FE-010).
    handler.handleTextMessage(
        alice,
        new TextMessage(
            "{\"type\":\"changed\",\"sections\":[\"crew\",\"finance\",\"mgmt\",\"overview\","
                + "\"steps\",\"objectives\",\"frequencies\"]}"));

    JsonNode relayed = lastBroadcast(bob);
    List<String> sections = new ArrayList<>();
    relayed.get("sections").forEach(node -> sections.add(node.asString()));
    assertThat(sections)
        .containsExactly(
            "crew", "finance", "mgmt", "overview", "steps", "objectives", "frequencies");
  }

  @Test
  void changedSignal_withNoValidSections_relaysNothing() throws Exception {
    UUID missionId = UUID.randomUUID();
    FakeSession alice = openSession(missionId, oidcUser("user-1", "Alice"));
    FakeSession bob = openSession(missionId, oidcUser("user-2", "Bob"));
    bob.sent.clear();

    handler.handleTextMessage(
        alice, new TextMessage("{\"type\":\"changed\",\"sections\":[\"bogus\"]}"));
    handler.handleTextMessage(alice, new TextMessage("{\"type\":\"changed\",\"sections\":[]}"));
    handler.handleTextMessage(alice, new TextMessage("{\"type\":\"changed\"}"));

    // Nothing valid to relay — peers receive no frame, and presence state is untouched.
    assertThat(bob.sent).isEmpty();
    assertThat(service.trackedMissions()).isEmpty();
  }

  @Test
  void changedSignal_isRateLimitedPerSession() throws Exception {
    UUID missionId = UUID.randomUUID();
    FakeSession alice = openSession(missionId, oidcUser("user-1", "Alice"));
    FakeSession bob = openSession(missionId, oidcUser("user-2", "Bob"));
    bob.sent.clear();

    // Emit far more than the per-session burst in a tight synchronous loop (well under one token's
    // refill interval), simulating a crafted flooding client.
    int emitted = MissionPresenceWebSocketHandler.CHANGED_BURST + 40;
    for (int i = 0; i < emitted; i++) {
      handler.handleTextMessage(
          alice, new TextMessage("{\"type\":\"changed\",\"sections\":[\"crew\"]}"));
    }

    // The token bucket caps relayed frames at the burst (a negligible refill may add at most ~1),
    // so the peer receives far fewer frames than were emitted.
    assertThat(bob.sent.size())
        .isGreaterThan(0)
        .isLessThanOrEqualTo(MissionPresenceWebSocketHandler.CHANGED_BURST + 1)
        .isLessThan(emitted);
  }

  // ── helpers ────────────────────────────────────────────────────────────────────────────────

  private FakeSession openSession(UUID missionId, OidcUser user) throws Exception {
    FakeSession session = new FakeSession();
    session.open = true;
    session.uri = URI.create("ws://localhost/ws/missions/" + missionId + "/presence");
    session.principal =
        new UsernamePasswordAuthenticationToken(
            user, "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER")));
    handler.afterConnectionEstablished(session);
    return session;
  }

  private static OidcUser oidcUser(String sub, String preferredUsername) {
    Map<String, Object> claims = new HashMap<>();
    claims.put("sub", sub);
    claims.put("preferred_username", preferredUsername);
    OidcIdToken token =
        new OidcIdToken(
            "tok-" + sub, java.time.Instant.now(), java.time.Instant.now().plusSeconds(60), claims);
    return new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_USER")), token, "sub");
  }

  private JsonNode lastBroadcast(WebSocketSession session) throws Exception {
    List<WebSocketMessage<?>> sent = new ArrayList<>(((FakeSession) session).sent);
    assertThat(sent).isNotEmpty();
    WebSocketMessage<?> last = sent.get(sent.size() - 1);
    assertThat(last).isInstanceOf(TextMessage.class);
    return objectMapper.readTree(((TextMessage) last).getPayload());
  }

  /**
   * Hand-rolled stand-in for {@code WebSocketSession}. Records outbound messages in {@link #sent}
   * and exposes the mutable {@link #attributes}, {@link #principal} and {@link #uri} fields tests
   * need. All other interface methods return safe defaults; the handler under test never invokes
   * them.
   */
  private static final class FakeSession implements WebSocketSession {
    private final String id = UUID.randomUUID().toString();
    final Map<String, Object> attributes = new HashMap<>();
    final List<WebSocketMessage<?>> sent = new ArrayList<>();
    boolean open;
    URI uri;
    Principal principal;
    CloseStatus closeStatus;

    @Override
    public String getId() {
      return id;
    }

    @Override
    public URI getUri() {
      return uri;
    }

    @Override
    public HttpHeaders getHandshakeHeaders() {
      return new HttpHeaders();
    }

    @Override
    public Map<String, Object> getAttributes() {
      return attributes;
    }

    @Override
    public Principal getPrincipal() {
      return principal;
    }

    @Override
    public InetSocketAddress getLocalAddress() {
      return null;
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
      return null;
    }

    @Override
    public String getAcceptedProtocol() {
      return null;
    }

    @Override
    public void setTextMessageSizeLimit(int messageSizeLimit) {
      // no-op
    }

    @Override
    public int getTextMessageSizeLimit() {
      return 0;
    }

    @Override
    public void setBinaryMessageSizeLimit(int messageSizeLimit) {
      // no-op
    }

    @Override
    public int getBinaryMessageSizeLimit() {
      return 0;
    }

    @Override
    public List<WebSocketExtension> getExtensions() {
      return Collections.emptyList();
    }

    @Override
    public void sendMessage(WebSocketMessage<?> message) throws IOException {
      sent.add(message);
    }

    @Override
    public boolean isOpen() {
      return open;
    }

    @Override
    public void close() {
      open = false;
    }

    @Override
    public void close(CloseStatus status) {
      this.closeStatus = status;
      this.open = false;
    }

    // Suppress unused-field warnings for `closeStatus` / `ByteBuffer` import — both are part of
    // the WebSocketSession contract we mirror but the current tests do not assert on them.
    @SuppressWarnings("unused")
    private void touchUnusedSymbols() {
      ByteBuffer.allocate(0);
    }
  }
}
