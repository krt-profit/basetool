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

import de.greluc.krt.profit.basetool.frontend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.frontend.service.LiveSyncPresenceService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
 * Tests for {@link LiveSyncWebSocketHandler} (ported from {@code
 * MissionPresenceWebSocketHandlerTest} with the mission topic driving the generic relay; every
 * original assertion is preserved).
 *
 * <p>Drives the handler through a hand-rolled {@link FakeSession} that records outbound messages so
 * the JSON wire format, room membership, principal-resolution and broadcast behaviour can be
 * verified without a real servlet container. Each session is bound to a {@code mission:{id}} topic
 * via the {@link LiveSyncWebSocketHandler#ATTR_TOPIC} attribute the handshake interceptor would
 * set.
 */
class LiveSyncWebSocketHandlerTest {

  private LiveSyncPresenceService service;
  private ObjectMapper objectMapper;
  private LiveSyncWebSocketHandler handler;
  private CapturingFanout fanout;
  private SimpleMeterRegistry registry;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    service = new LiveSyncPresenceService(registry);
    objectMapper = JsonMapper.builder().build();
    fanout = new CapturingFanout();
    handler = new LiveSyncWebSocketHandler(service, fanout, objectMapper, registry);
  }

  @Test
  void focusMessage_recordsPresence_andBroadcastsSnapshot() throws Exception {
    String topic = missionTopic();
    FakeSession session = openSession(topic, oidcUser("user-1", "Alice"));

    session.sent.clear();
    handler.handleTextMessage(
        session, new TextMessage("{\"type\":\"focus\",\"sectionKey\":\"crew\"}"));

    assertThat(service.get(topic, "crew", "user-1")).isNotNull();
    assertThat(service.get(topic, "crew", "user-1").displayName()).isEqualTo("Alice");

    JsonNode broadcast = lastBroadcast(session);
    assertThat(broadcast.get("type").asString()).isEqualTo("presence");
    JsonNode editors = broadcast.get("sections").get("crew");
    assertThat(editors).isNotNull();
    assertThat(editors.get(0).get("userId").asString()).isEqualTo("user-1");
    assertThat(editors.get(0).get("displayName").asString()).isEqualTo("Alice");
  }

  @Test
  void blurMessage_clearsPresence_andBroadcastsEmptySection() throws Exception {
    String topic = missionTopic();
    FakeSession session = openSession(topic, oidcUser("user-1", "Alice"));
    handler.handleTextMessage(
        session, new TextMessage("{\"type\":\"focus\",\"sectionKey\":\"crew\"}"));

    session.sent.clear();
    handler.handleTextMessage(
        session, new TextMessage("{\"type\":\"blur\",\"sectionKey\":\"crew\"}"));

    assertThat(service.get(topic, "crew", "user-1")).isNull();
    JsonNode broadcast = lastBroadcast(session);
    // After the blur the snapshot has no sections at all (the entry was the only one).
    assertThat(broadcast.get("type").asString()).isEqualTo("presence");
    assertThat(broadcast.get("sections").size()).isZero();
  }

  @Test
  void heartbeat_doesNotBroadcast_whenUserAlreadyKnown() throws Exception {
    String topic = missionTopic();
    FakeSession session = openSession(topic, oidcUser("user-1", "Alice"));
    handler.handleTextMessage(
        session, new TextMessage("{\"type\":\"focus\",\"sectionKey\":\"crew\"}"));

    int countAfterFocus = session.sent.size();
    handler.handleTextMessage(
        session, new TextMessage("{\"type\":\"heartbeat\",\"sectionKey\":\"crew\"}"));

    // Heartbeat from an already-known editor must NOT trigger a broadcast — generating one frame
    // per
    // heartbeat per connected client per topic would be wasteful and visually pointless because the
    // state didn't change.
    assertThat(session.sent).hasSize(countAfterFocus);
  }

  @Test
  void malformedPayload_isSilentlyDropped() throws Exception {
    String topic = missionTopic();
    FakeSession session = openSession(topic, oidcUser("user-1", "Alice"));
    session.sent.clear();

    handler.handleTextMessage(session, new TextMessage("{this is not json"));
    handler.handleTextMessage(session, new TextMessage("{\"type\":null}"));
    handler.handleTextMessage(
        session, new TextMessage("{\"type\":\"unknown\",\"sectionKey\":\"x\"}"));

    // No state mutation, no broadcasts.
    assertThat(service.trackedTopics()).isEmpty();
    assertThat(session.sent).isEmpty();
  }

  @Test
  void connectionClosed_clearsAllPresence_andBroadcastsToRemainingClients() throws Exception {
    String topic = missionTopic();
    FakeSession aliceSession = openSession(topic, oidcUser("user-1", "Alice"));
    FakeSession bobSession = openSession(topic, oidcUser("user-2", "Bob"));
    handler.handleTextMessage(
        aliceSession, new TextMessage("{\"type\":\"focus\",\"sectionKey\":\"crew\"}"));
    handler.handleTextMessage(
        bobSession, new TextMessage("{\"type\":\"focus\",\"sectionKey\":\"steps\"}"));

    bobSession.sent.clear();
    aliceSession.open = false;
    handler.afterConnectionClosed(aliceSession, CloseStatus.NORMAL);

    // Alice's presence is gone on every section.
    assertThat(service.get(topic, "crew", "user-1")).isNull();
    // Bob's presence is untouched.
    assertThat(service.get(topic, "steps", "user-2")).isNotNull();
    // Bob's session received an updated snapshot (no Alice in `crew`).
    JsonNode broadcast = lastBroadcast(bobSession);
    assertThat(broadcast.get("sections").has("crew")).isFalse();
    assertThat(broadcast.get("sections").get("steps").get(0).get("userId").asString())
        .isEqualTo("user-2");
  }

  @Test
  void connectionClosed_keepsPresence_whenSameUserHasAnotherOpenTab() throws Exception {
    String topic = missionTopic();
    FakeSession tabA = openSession(topic, oidcUser("user-1", "Alice"));
    FakeSession tabB = openSession(topic, oidcUser("user-1", "Alice"));
    handler.handleTextMessage(
        tabA, new TextMessage("{\"type\":\"focus\",\"sectionKey\":\"crew\"}"));

    tabA.open = false;
    handler.afterConnectionClosed(tabA, CloseStatus.NORMAL);

    // The other tab is still alive — Alice's "crew" presence must survive.
    assertThat(service.get(topic, "crew", "user-1")).isNotNull();
    assertThat(tabB.isOpen()).isTrue();
  }

  @Test
  void changedSignal_isRelayedToOtherSessions_butNotToOrigin() throws Exception {
    String topic = missionTopic();
    FakeSession alice = openSession(topic, oidcUser("user-1", "Alice"));
    FakeSession bob = openSession(topic, oidcUser("user-2", "Bob"));
    alice.sent.clear();
    bob.sent.clear();

    handler.handleTextMessage(
        alice, new TextMessage("{\"type\":\"changed\",\"sections\":[\"crew\",\"finance\"]}"));

    // The originator already applied its own change locally — it must not receive the echo.
    assertThat(alice.sent).isEmpty();
    // Every other socket in the same room gets the section keys (and nothing else).
    JsonNode relayed = lastBroadcast(bob);
    assertThat(relayed.get("type").asString()).isEqualTo("changed");
    assertThat(relayed.get("topic").asString()).isEqualTo(topic);
    assertThat(sectionsOf(relayed)).containsExactly("crew", "finance");
  }

  @Test
  void changedSignal_dropsUnknownKeysAndDeduplicates() throws Exception {
    String topic = missionTopic();
    FakeSession alice = openSession(topic, oidcUser("user-1", "Alice"));
    FakeSession bob = openSession(topic, oidcUser("user-2", "Bob"));
    bob.sent.clear();

    handler.handleTextMessage(
        alice,
        new TextMessage(
            "{\"type\":\"changed\",\"sections\":[\"crew\",\"bogus\",\"crew\",\"mgmt\",42]}"));

    JsonNode relayed = lastBroadcast(bob);
    // "bogus" and the non-string 42 are dropped; the duplicate "crew" appears once.
    assertThat(sectionsOf(relayed)).containsExactly("crew", "mgmt");
  }

  @Test
  void changedSignal_relaysEverySectionOfTheMissionSeamMap() throws Exception {
    String topic = missionTopic();
    FakeSession alice = openSession(topic, oidcUser("user-1", "Alice"));
    FakeSession bob = openSession(topic, oidcUser("user-2", "Bob"));
    bob.sent.clear();

    // Every key of the MISSION_SECTIONS seam map in mission-detail.js must pass the relay whitelist
    // — steps/objectives/frequencies were once dropped here, leaving peers' Verwaltung editors
    // stale
    // until a manual reload (REQ-FE-010).
    handler.handleTextMessage(
        alice,
        new TextMessage(
            "{\"type\":\"changed\",\"sections\":[\"crew\",\"finance\",\"mgmt\",\"overview\","
                + "\"steps\",\"objectives\",\"frequencies\"]}"));

    JsonNode relayed = lastBroadcast(bob);
    assertThat(sectionsOf(relayed))
        .containsExactly(
            "crew", "finance", "mgmt", "overview", "steps", "objectives", "frequencies");
  }

  @Test
  void changedSignal_withNoValidSections_relaysNothing() throws Exception {
    String topic = missionTopic();
    FakeSession alice = openSession(topic, oidcUser("user-1", "Alice"));
    FakeSession bob = openSession(topic, oidcUser("user-2", "Bob"));
    bob.sent.clear();

    handler.handleTextMessage(
        alice, new TextMessage("{\"type\":\"changed\",\"sections\":[\"bogus\"]}"));
    handler.handleTextMessage(alice, new TextMessage("{\"type\":\"changed\",\"sections\":[]}"));
    handler.handleTextMessage(alice, new TextMessage("{\"type\":\"changed\"}"));

    // Nothing valid to relay — peers receive no frame, presence is untouched, and nothing fans out.
    assertThat(bob.sent).isEmpty();
    assertThat(service.trackedTopics()).isEmpty();
    assertThat(fanout.publishedTopics).isEmpty();
  }

  @Test
  void changedSignal_isRateLimitedPerSession() throws Exception {
    String topic = missionTopic();
    FakeSession alice = openSession(topic, oidcUser("user-1", "Alice"));
    FakeSession bob = openSession(topic, oidcUser("user-2", "Bob"));
    bob.sent.clear();

    // Emit far more than the per-session burst in a tight synchronous loop (well under one token's
    // refill interval), simulating a crafted flooding client.
    int emitted = LiveSyncWebSocketHandler.CHANGED_BURST + 40;
    for (int i = 0; i < emitted; i++) {
      handler.handleTextMessage(
          alice, new TextMessage("{\"type\":\"changed\",\"sections\":[\"crew\"]}"));
    }

    // The token bucket caps relayed frames at the burst (a negligible refill may add at most ~1),
    // so
    // the peer receives far fewer frames than were emitted.
    assertThat(bob.sent.size())
        .isGreaterThan(0)
        .isLessThanOrEqualTo(LiveSyncWebSocketHandler.CHANGED_BURST + 1)
        .isLessThan(emitted);
  }

  @Test
  void changedSignal_isPublishedToTheFanoutAfterLocalRelay() throws Exception {
    String topic = missionTopic();
    FakeSession alice = openSession(topic, oidcUser("user-1", "Alice"));
    openSession(topic, oidcUser("user-2", "Bob"));

    handler.handleTextMessage(
        alice, new TextMessage("{\"type\":\"changed\",\"sections\":[\"crew\",\"finance\"]}"));

    // A cross-replica fan-out publish carries the canonical topic and the sanitised sections.
    assertThat(fanout.publishedTopics).containsExactly(topic);
    assertThat(fanout.publishedSections).containsExactly(List.of("crew", "finance"));
  }

  @Test
  void changedSignal_staysWithinItsOwnRoom() throws Exception {
    String topicA = missionTopic();
    String topicB = missionTopic();
    FakeSession alice = openSession(topicA, oidcUser("user-1", "Alice"));
    FakeSession carol = openSession(topicB, oidcUser("user-3", "Carol"));
    carol.sent.clear();

    handler.handleTextMessage(
        alice, new TextMessage("{\"type\":\"changed\",\"sections\":[\"crew\"]}"));

    // A change in room A never reaches a viewer of room B (cross-room isolation).
    assertThat(carol.sent).isEmpty();
  }

  @Test
  void deliverFromFanout_relaysToLocalRoom_withoutOriginExclusion() throws Exception {
    String topic = missionTopic();
    FakeSession alice = openSession(topic, oidcUser("user-1", "Alice"));
    FakeSession bob = openSession(topic, oidcUser("user-2", "Bob"));
    alice.sent.clear();
    bob.sent.clear();

    // A frame arriving from a peer replica has no local origin — every local socket receives it.
    handler.deliverFromFanout(topic, List.of("crew"));

    assertThat(sectionsOf(lastBroadcast(alice))).containsExactly("crew");
    assertThat(sectionsOf(lastBroadcast(bob))).containsExactly("crew");
    // Consuming a fan-out frame must not re-publish it (that would loop across replicas).
    assertThat(fanout.publishedTopics).isEmpty();
  }

  @Test
  void openSessions_areCountedInPresenceWsSessionsGauge() throws Exception {
    String topic = missionTopic();
    assertThat(presenceGauge()).isZero();

    FakeSession alice = openSession(topic, oidcUser("user-1", "Alice"));
    FakeSession bob = openSession(topic, oidcUser("user-2", "Bob"));
    assertThat(presenceGauge()).isEqualTo(2.0);

    alice.open = false;
    handler.afterConnectionClosed(alice, CloseStatus.NORMAL);
    assertThat(presenceGauge()).isEqualTo(1.0);
    assertThat(bob.isOpen()).isTrue();
  }

  @Test
  void broadcasts_countSnapshotAndChangedRelayFrames() throws Exception {
    String topic = missionTopic();
    FakeSession alice = openSession(topic, oidcUser("user-1", "Alice"));
    openSession(topic, oidcUser("user-2", "Bob"));

    handler.handleTextMessage(
        alice, new TextMessage("{\"type\":\"changed\",\"sections\":[\"crew\"]}"));

    // The relayed change is one changed frame; the per-connect snapshots are snapshot frames.
    assertThat(frameCounter(MetricNames.FRAME_CHANGED)).isEqualTo(1.0);
    assertThat(frameCounter(MetricNames.FRAME_SNAPSHOT)).isGreaterThan(0.0);
  }

  @Test
  void throttledChangedFrames_areCountedAsDroppedThrottled() throws Exception {
    String topic = missionTopic();
    FakeSession alice = openSession(topic, oidcUser("user-1", "Alice"));
    openSession(topic, oidcUser("user-2", "Bob"));

    int emitted = LiveSyncWebSocketHandler.CHANGED_BURST + 40;
    for (int i = 0; i < emitted; i++) {
      handler.handleTextMessage(
          alice, new TextMessage("{\"type\":\"changed\",\"sections\":[\"crew\"]}"));
    }

    // Every frame past the per-session token bucket is now counted (previously a silent drop).
    assertThat(dropCounter(MetricNames.DROPPED_THROTTLED)).isGreaterThan(0.0);
  }

  @Test
  void sendFailureToBrokenPeer_isCountedAsDroppedSendFailed() throws Exception {
    String topic = missionTopic();
    FakeSession alice = openSession(topic, oidcUser("user-1", "Alice"));
    FakeSession bob = openSession(topic, oidcUser("user-2", "Bob"));
    // Bob's socket reports open but every write throws (a half-broken connection).
    bob.failSend = true;

    handler.handleTextMessage(
        alice, new TextMessage("{\"type\":\"changed\",\"sections\":[\"crew\"]}"));

    // The failed write is a send_failed drop and must NOT also count as a delivered changed frame.
    assertThat(dropCounter(MetricNames.DROPPED_SEND_FAILED)).isGreaterThanOrEqualTo(1.0);
    assertThat(frameCounter(MetricNames.FRAME_CHANGED)).isZero();
  }

  // ── helpers ────────────────────────────────────────────────────────────────────────────────

  private static String missionTopic() {
    return "mission:" + UUID.randomUUID();
  }

  private static List<String> sectionsOf(JsonNode relayed) {
    List<String> sections = new ArrayList<>();
    relayed.get("sections").forEach(node -> sections.add(node.asString()));
    return sections;
  }

  private double presenceGauge() {
    return registry.get(MetricNames.PRESENCE_WS_SESSIONS).gauge().value();
  }

  private double frameCounter(String type) {
    var counter =
        registry
            .find(MetricNames.PRESENCE_RELAY_FRAMES)
            .tag(MetricNames.TAG_TYPE, type)
            .tag(MetricNames.TAG_TOPIC_CLASS, "mission")
            .counter();
    return counter == null ? 0.0 : counter.count();
  }

  private double dropCounter(String reason) {
    var counter =
        registry
            .find(MetricNames.PRESENCE_RELAY_DROPPED)
            .tag(MetricNames.TAG_REASON, reason)
            .tag(MetricNames.TAG_TOPIC_CLASS, "mission")
            .counter();
    return counter == null ? 0.0 : counter.count();
  }

  private FakeSession openSession(String topic, OidcUser user) throws Exception {
    FakeSession session = new FakeSession();
    session.open = true;
    session.uri =
        URI.create(
            "ws://localhost/ws/missions/" + topic.substring("mission:".length()) + "/presence");
    session.attributes.put(LiveSyncWebSocketHandler.ATTR_TOPIC, topic);
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

  /** Captures fan-out publishes so tests can assert the cross-replica hand-off. */
  private static final class CapturingFanout implements LiveSyncFanout {
    private final List<String> publishedTopics = new ArrayList<>();
    private final List<List<String>> publishedSections = new ArrayList<>();

    @Override
    public void publish(String canonicalTopic, List<String> sections) {
      publishedTopics.add(canonicalTopic);
      publishedSections.add(List.copyOf(sections));
    }
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
    boolean failSend;
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
      if (failSend) {
        throw new IOException("simulated broken socket");
      }
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

    // Suppress unused-field warnings for `closeStatus` / `ByteBuffer` import — both are part of the
    // WebSocketSession contract we mirror but the current tests do not assert on them.
    @SuppressWarnings("unused")
    private void touchUnusedSymbols() {
      ByteBuffer.allocate(0);
    }
  }
}
