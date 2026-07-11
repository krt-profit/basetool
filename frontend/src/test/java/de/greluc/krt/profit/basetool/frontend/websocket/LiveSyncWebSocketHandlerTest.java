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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import java.util.concurrent.RejectedExecutionException;
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
  private LiveSyncSubscriptionAuthorizer authorizer;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    service = new LiveSyncPresenceService(registry);
    objectMapper = JsonMapper.builder().build();
    fanout = new CapturingFanout();
    authorizer = mock(LiveSyncSubscriptionAuthorizer.class);
    // Default: authorize any subscribe. Individual tests override to DENY where they need it. The
    // executor is direct (Runnable::run) so an async subscribe-authorize completes synchronously in
    // the test thread — the saturation path uses a throwing executor instead.
    when(authorizer.authorize(any(), any(), any(), any()))
        .thenReturn(LiveSyncSubscriptionAuthorizer.Decision.ALLOW);
    handler =
        new LiveSyncWebSocketHandler(
            service, fanout, objectMapper, registry, authorizer, Runnable::run);
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
                + "\"steps\",\"objectives\",\"frequencies\",\"organisation\"]}"));

    JsonNode relayed = lastBroadcast(bob);
    assertThat(sectionsOf(relayed))
        .containsExactly(
            "crew",
            "finance",
            "mgmt",
            "overview",
            "steps",
            "objectives",
            "frequencies",
            "organisation");
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

  // ── Multiplexed /ws/sync tests ───────────────────────────────────────────────────────────────

  @Test
  void multiplexedSubscribe_authorized_acksAndReceivesPeerChange() throws Exception {
    String topic = operationTopic();
    FakeSession alice = openMultiplexedSession(oidcUser("user-1", "Alice"));
    FakeSession bob = openMultiplexedSession(oidcUser("user-2", "Bob"));
    subscribe(alice, topic);
    subscribe(bob, topic);

    assertThat(lastBroadcast(alice).get("type").asString()).isEqualTo("subscribed");
    assertThat(lastBroadcast(bob).get("type").asString()).isEqualTo("subscribed");

    bob.sent.clear();
    handler.handleTextMessage(alice, changedFrame(topic, "overview"));

    JsonNode relayed = lastBroadcast(bob);
    assertThat(relayed.get("type").asString()).isEqualTo("changed");
    assertThat(relayed.get("topic").asString()).isEqualTo(topic);
    assertThat(sectionsOf(relayed)).containsExactly("overview");
    assertThat(subscribeCounter(MetricNames.OUTCOME_ALLOWED, "operation"))
        .isGreaterThanOrEqualTo(2.0);
  }

  @Test
  void multiplexedSubscribe_denied_refusesAndCounts() throws Exception {
    when(authorizer.authorize(any(), any(), any(), any()))
        .thenReturn(LiveSyncSubscriptionAuthorizer.Decision.DENY);
    FakeSession bob = openMultiplexedSession(oidcUser("user-2", "Bob"));
    subscribe(bob, operationTopic());

    assertThat(lastBroadcast(bob).get("type").asString()).isEqualTo("denied");
    assertThat(subscribeCounter(MetricNames.OUTCOME_DENIED, "operation")).isEqualTo(1.0);
  }

  @Test
  void multiplexedChanged_publishesWithoutSubscription() throws Exception {
    String topic = operationTopic();
    FakeSession subscriber = openMultiplexedSession(oidcUser("user-2", "Bob"));
    subscribe(subscriber, topic);
    subscriber.sent.clear();

    // The publisher never subscribed to the topic (the cross-topic case: a requester notifying a
    // queue it may not read) yet its change still reaches the room.
    FakeSession publisher = openMultiplexedSession(oidcUser("user-1", "Alice"));
    handler.handleTextMessage(publisher, changedFrame(topic, "payout"));

    assertThat(sectionsOf(lastBroadcast(subscriber))).containsExactly("payout");
    assertThat(publisher.sent).isEmpty();
  }

  @Test
  void multiplexedChanged_sanitisesAgainstOperationWhitelist() throws Exception {
    String topic = operationTopic();
    FakeSession bob = openMultiplexedSession(oidcUser("user-2", "Bob"));
    subscribe(bob, topic);
    bob.sent.clear();
    FakeSession alice = openMultiplexedSession(oidcUser("user-1", "Alice"));

    // "crew" is a mission section, not an operation section — dropped; the two operation-surface
    // sections "overview"/"payout" are kept ("missions"/"finance" are not in the OPERATION
    // whitelist — see #1241).
    handler.handleTextMessage(
        alice,
        new TextMessage(
            "{\"type\":\"changed\",\"topic\":\""
                + topic
                + "\",\"sections\":[\"overview\",\"crew\",\"payout\"]}"));

    assertThat(sectionsOf(lastBroadcast(bob))).containsExactly("overview", "payout");
  }

  @Test
  void multiplexedSubscribe_unknownTopic_isDenied() throws Exception {
    FakeSession bob = openMultiplexedSession(oidcUser("user-2", "Bob"));
    handler.handleTextMessage(
        bob, new TextMessage("{\"type\":\"subscribe\",\"topic\":\"bogus:not-a-thing\"}"));
    assertThat(lastBroadcast(bob).get("type").asString()).isEqualTo("denied");
  }

  @Test
  void multiplexedSubscribe_reSubscribe_isIdempotent() throws Exception {
    String topic = operationTopic();
    FakeSession bob = openMultiplexedSession(oidcUser("user-2", "Bob"));
    subscribe(bob, topic);
    subscribe(bob, topic); // idempotent: re-ack, no double room join

    assertThat(lastBroadcast(bob).get("type").asString()).isEqualTo("subscribed");
    FakeSession alice = openMultiplexedSession(oidcUser("user-1", "Alice"));
    bob.sent.clear();
    handler.handleTextMessage(alice, changedFrame(topic, "overview"));
    // Exactly one room membership → exactly one relayed frame (a double join would send two).
    assertThat(bob.sent).hasSize(1);
  }

  @Test
  void multiplexedSubscribe_beyondTopicCap_isDeniedAndCounted() throws Exception {
    FakeSession bob = openMultiplexedSession(oidcUser("user-2", "Bob"));
    for (int i = 0; i < 16; i++) {
      subscribe(bob, operationTopic());
    }
    bob.sent.clear();
    subscribe(bob, operationTopic()); // the 17th exceeds MAX_TOPICS_PER_SESSION

    assertThat(lastBroadcast(bob).get("type").asString()).isEqualTo("denied");
    assertThat(dropCounter(MetricNames.DROPPED_TOPIC_CAP, "operation")).isEqualTo(1.0);
  }

  @Test
  void multiplexedSubscribe_executorSaturated_failsOpenAndCounts() throws Exception {
    SimpleMeterRegistry reg2 = new SimpleMeterRegistry();
    LiveSyncPresenceService svc2 = new LiveSyncPresenceService(reg2);
    LiveSyncSubscriptionAuthorizer denyAll = mock(LiveSyncSubscriptionAuthorizer.class);
    when(denyAll.authorize(any(), any(), any(), any()))
        .thenReturn(LiveSyncSubscriptionAuthorizer.Decision.DENY);
    LiveSyncWebSocketHandler saturated =
        new LiveSyncWebSocketHandler(
            svc2,
            fanout,
            objectMapper,
            reg2,
            denyAll,
            runnable -> {
              throw new RejectedExecutionException("auth executor full");
            });

    FakeSession bob = new FakeSession();
    bob.open = true;
    bob.uri = URI.create("ws://localhost/ws/sync");
    bob.attributes.put(LiveSyncWebSocketHandler.ATTR_MULTIPLEXED, Boolean.TRUE);
    bob.principal =
        new UsernamePasswordAuthenticationToken(
            oidcUser("user-2", "Bob"), "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER")));
    saturated.afterConnectionEstablished(bob);

    saturated.handleTextMessage(
        bob, new TextMessage("{\"type\":\"subscribe\",\"topic\":\"" + operationTopic() + "\"}"));

    // Saturation fails the subscribe open: the socket is acked `subscribed` even though the
    // DENY-everything probe never ran, and the fail-open is counted.
    assertThat(lastBroadcast(bob).get("type").asString()).isEqualTo("subscribed");
    var counter =
        reg2.find(MetricNames.PRESENCE_RELAY_DROPPED)
            .tag(MetricNames.TAG_REASON, MetricNames.DROPPED_AUTHORIZE_SATURATED)
            .tag(MetricNames.TAG_TOPIC_CLASS, "operation")
            .counter();
    assertThat(counter).isNotNull();
    assertThat(counter.count()).isEqualTo(1.0);
  }

  @Test
  void multiplexedSubscribe_denied_receivesNoSubsequentPeerChange() throws Exception {
    // Beyond the `denied` ack + counter (multiplexedSubscribe_denied_refusesAndCounts): prove via
    // observed traffic that a refused socket is NOT in the room — a later peer `changed` frame for
    // the same topic must reach it with nothing (the "subscribe refused ⇒ no inbound relay"
    // contract).
    when(authorizer.authorize(any(), any(), any(), any()))
        .thenReturn(LiveSyncSubscriptionAuthorizer.Decision.DENY);
    String topic = operationTopic();
    FakeSession bob = openMultiplexedSession(oidcUser("user-2", "Bob"));
    subscribe(bob, topic);
    assertThat(lastBroadcast(bob).get("type").asString()).isEqualTo("denied");
    bob.sent.clear();

    // A peer publishes (publishing needs no subscription); the denied socket receives nothing.
    FakeSession alice = openMultiplexedSession(oidcUser("user-1", "Alice"));
    handler.handleTextMessage(alice, changedFrame(topic, "overview"));

    assertThat(bob.sent).isEmpty();
  }

  @Test
  void multiplexedSubscribe_authorizerThrows_failsOpen() throws Exception {
    // authorizeAndRegister catches a RuntimeException from the probe and downgrades to ALLOW — the
    // same availability-over-strictness posture as the executor-saturation path (safe: only opaque
    // keys cross the socket, every fragment re-fetch re-authorizes per viewer).
    LiveSyncSubscriptionAuthorizer throwing = mock(LiveSyncSubscriptionAuthorizer.class);
    when(throwing.authorize(any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("probe blew up"));
    SimpleMeterRegistry reg = new SimpleMeterRegistry();
    LiveSyncPresenceService svc = new LiveSyncPresenceService(reg);
    LiveSyncWebSocketHandler h =
        new LiveSyncWebSocketHandler(svc, fanout, objectMapper, reg, throwing, Runnable::run);

    String topic = operationTopic();
    FakeSession bob = multiplexedSession(oidcUser("user-2", "Bob"));
    h.afterConnectionEstablished(bob);
    h.handleTextMessage(bob, subscribeFrame(topic));

    // Fail-open: acked `subscribed` despite the throwing probe, and actually joined the room.
    assertThat(lastBroadcast(bob).get("type").asString()).isEqualTo("subscribed");
    bob.sent.clear();
    FakeSession alice = multiplexedSession(oidcUser("user-1", "Alice"));
    h.afterConnectionEstablished(alice);
    h.handleTextMessage(alice, changedFrame(topic, "overview"));
    assertThat(sectionsOf(lastBroadcast(bob))).containsExactly("overview");
  }

  @Test
  void multiplexedSubscribe_socketClosedDuringProbe_dropsAndDoesNotJoin() throws Exception {
    // Close-during-probe race branch (a): the async probe returns ALLOW, but the socket closed
    // while it ran. The slot was reserved synchronously at subscribe time; completeSubscribe must
    // drop it and NOT join the room (a closed decorator lingering in a room would leak frames to a
    // dead socket). A deferred executor holds the probe so the close can be interleaved
    // deterministically.
    List<Runnable> deferred = new ArrayList<>();
    SimpleMeterRegistry reg = new SimpleMeterRegistry();
    LiveSyncPresenceService svc = new LiveSyncPresenceService(reg);
    LiveSyncWebSocketHandler h =
        new LiveSyncWebSocketHandler(svc, fanout, objectMapper, reg, authorizer, deferred::add);

    String topic = operationTopic();
    FakeSession bob = multiplexedSession(oidcUser("user-2", "Bob"));
    h.afterConnectionEstablished(bob);
    h.handleTextMessage(bob, subscribeFrame(topic)); // reserves the slot, defers the probe
    assertThat(deferred).hasSize(1);

    // The socket closes before the probe completes.
    bob.open = false;
    h.afterConnectionClosed(bob, CloseStatus.NORMAL);
    bob.sent.clear();

    deferred.get(0).run(); // probe completes ALLOW against a now-closed socket

    // No `subscribed` ack to the dead socket, and the room stays empty (never joined).
    assertThat(bob.sent).isEmpty();
    assertThat(presenceGauge(reg)).isZero();
  }

  @Test
  void multiplexedSubscribe_socketClosesBetweenJoinAndAck_leavesRoom() throws Exception {
    // Close-during-probe race branch (b): the socket is open when completeSubscribe checks, joins
    // the room, then loses the race with a concurrent close before the ack. The handler must
    // leaveRoom so no closed decorator lingers. The FakeSession reports open once (the pre-join
    // check) then closed (the post-join check), reproducing that interleaving deterministically.
    List<Runnable> deferred = new ArrayList<>();
    SimpleMeterRegistry reg = new SimpleMeterRegistry();
    LiveSyncPresenceService svc = new LiveSyncPresenceService(reg);
    LiveSyncWebSocketHandler h =
        new LiveSyncWebSocketHandler(svc, fanout, objectMapper, reg, authorizer, deferred::add);

    String topic = operationTopic();
    FakeSession bob = multiplexedSession(oidcUser("user-2", "Bob"));
    h.afterConnectionEstablished(bob);
    h.handleTextMessage(bob, subscribeFrame(topic)); // reserves the slot, defers the probe
    bob.flipOpenAfter = 1; // open at the pre-join check, closed at the post-join check

    deferred.get(0).run();

    // Joined then immediately left: the room is empty (no lingering decorator) and no ack was sent.
    assertThat(presenceGauge(reg)).isZero();
    assertThat(bob.sent).isEmpty();
  }

  @Test
  void multiplexedClose_leavesAllSubscribedRooms() throws Exception {
    FakeSession bob = openMultiplexedSession(oidcUser("user-2", "Bob"));
    subscribe(bob, operationTopic());
    subscribe(bob, operationTopic());

    // The socket is in two rooms (the gauge sums a socket once per room).
    assertThat(presenceGauge()).isEqualTo(2.0);

    bob.open = false;
    handler.afterConnectionClosed(bob, CloseStatus.NORMAL);
    assertThat(presenceGauge()).isZero();
  }

  @Test
  void multiplexedChanged_staysWithinItsOwnRoom() throws Exception {
    String topicA = operationTopic();
    String topicB = operationTopic();
    FakeSession carol = openMultiplexedSession(oidcUser("user-3", "Carol"));
    subscribe(carol, topicB);
    carol.sent.clear();
    FakeSession alice = openMultiplexedSession(oidcUser("user-1", "Alice"));

    handler.handleTextMessage(alice, changedFrame(topicA, "overview"));

    assertThat(carol.sent).isEmpty();
  }

  @Test
  void multiplexedPresence_onSubscribedPresenceTopic_tracksAndBroadcasts() throws Exception {
    // A presence-enabled class (mission) subscribed over /ws/sync tracks editor presence just like
    // the legacy socket does — the path the mission migration will use.
    String topic = missionTopic();
    FakeSession alice = openMultiplexedSession(oidcUser("user-1", "Alice"));
    FakeSession bob = openMultiplexedSession(oidcUser("user-2", "Bob"));
    subscribe(alice, topic);
    subscribe(bob, topic);
    bob.sent.clear();

    handler.handleTextMessage(
        alice,
        new TextMessage(
            "{\"type\":\"focus\",\"topic\":\"" + topic + "\",\"sectionKey\":\"crew\"}"));

    assertThat(service.get(topic, "crew", "user-1")).isNotNull();
    JsonNode snapshot = lastBroadcast(bob);
    assertThat(snapshot.get("type").asString()).isEqualTo("presence");
    assertThat(snapshot.get("sections").get("crew").get(0).get("userId").asString())
        .isEqualTo("user-1");
  }

  @Test
  void multiplexedPresence_onUnsubscribedTopic_isIgnored() throws Exception {
    String topic = missionTopic();
    FakeSession alice = openMultiplexedSession(oidcUser("user-1", "Alice"));
    // Alice never subscribed to the topic — a presence frame for it is ignored.
    handler.handleTextMessage(
        alice,
        new TextMessage(
            "{\"type\":\"focus\",\"topic\":\"" + topic + "\",\"sectionKey\":\"crew\"}"));

    assertThat(service.get(topic, "crew", "user-1")).isNull();
  }

  @Test
  void publishFromServer_relaysToLocalRoom_andFansOut() throws Exception {
    // The server-side publish path (anonymous guest order create — no socket to publish from).
    FakeSession bob = openMultiplexedSession(oidcUser("user-2", "Bob"));
    subscribe(bob, "orders");
    bob.sent.clear();

    handler.publishFromServer("orders", List.of("queue"));

    assertThat(sectionsOf(lastBroadcast(bob))).containsExactly("queue");
    assertThat(fanout.publishedTopics).containsExactly("orders");
  }

  @Test
  void publishFromServer_dropsSectionsOutsideWhitelist() throws Exception {
    FakeSession bob = openMultiplexedSession(oidcUser("user-2", "Bob"));
    subscribe(bob, "orders");
    bob.sent.clear();

    handler.publishFromServer("orders", List.of("bogus"));

    assertThat(bob.sent).isEmpty();
    assertThat(fanout.publishedTopics).isEmpty();
  }

  @Test
  void publishFromServer_ignoresUnknownTopic() {
    handler.publishFromServer("nope:not-a-topic", List.of("queue"));
    assertThat(fanout.publishedTopics).isEmpty();
  }

  // ── Materialbörse board (legacy /ws/materialboerse/board alias + multiplexed materialboard) ───

  @Test
  void boardChanged_overLegacyAlias_isRelayedToOtherSessionsOnly() throws Exception {
    // Migrated from the deleted MaterialboardPresenceWebSocketHandlerTest: a legacy board socket
    // sends a topic-less `changed` frame (its implicit ATTR_TOPIC binds it to the materialboard
    // room) — the path the pre-rollout materialboerse-presence.js uses — and it relays to peers
    // (carrying the canonical topic) but not back to the origin.
    FakeSession origin = openBoardLegacySession(oidcUser("user-1", "Alice"));
    FakeSession peer = openBoardLegacySession(oidcUser("user-2", "Bob"));
    origin.sent.clear();
    peer.sent.clear();

    handler.handleTextMessage(
        origin, new TextMessage("{\"type\":\"changed\",\"sections\":[\"board\"]}"));

    JsonNode relayed = lastBroadcast(peer);
    assertThat(relayed.get("type").asString()).isEqualTo("changed");
    assertThat(relayed.get("topic").asString()).isEqualTo("materialboard");
    assertThat(sectionsOf(relayed)).containsExactly("board");
    assertThat(origin.sent).isEmpty();
  }

  @Test
  void boardChanged_dropsSectionsOutsideTheMaterialboardWhitelist() throws Exception {
    FakeSession origin = openBoardLegacySession(oidcUser("user-1", "Alice"));
    FakeSession peer = openBoardLegacySession(oidcUser("user-2", "Bob"));
    peer.sent.clear();

    handler.handleTextMessage(
        origin, new TextMessage("{\"type\":\"changed\",\"sections\":[\"secret\"]}"));

    // Only `board` is accept-listed for the materialboard class; anything else is dropped.
    assertThat(peer.sent).isEmpty();
  }

  @Test
  void multiplexedChanged_materialboardRoom_relaysBoardSection() throws Exception {
    // A new /ws/sync board client subscribes to the global materialboard room and receives a peer's
    // board change — the same room the legacy alias joins, so both transports interoperate.
    FakeSession subscriber = openMultiplexedSession(oidcUser("user-2", "Bob"));
    subscribe(subscriber, "materialboard");
    subscriber.sent.clear();
    FakeSession publisher = openMultiplexedSession(oidcUser("user-1", "Alice"));

    handler.handleTextMessage(publisher, changedFrame("materialboard", "board"));

    assertThat(sectionsOf(lastBroadcast(subscriber))).containsExactly("board");
  }

  // ── Reaper + connect-time refusal (ported from the retired MissionPresenceWebSocketHandlerTest)
  // ─

  @Test
  void tickReaper_broadcastsFreshSnapshotToAffectedRoom() throws Exception {
    // The reaper tick must fan a fresh presence snapshot into every room that lost a TTL-expired
    // entry; otherwise an editor who closes their tab without a blur lingers forever in every
    // peer's
    // "X is editing this section" indicator, defeating the collision-avoidance the feature exists
    // for. Drive tickReaper() against a mocked presence service so the reap -> broadcastSnapshot
    // wiring is exercised in isolation.
    String topic = missionTopic();
    SimpleMeterRegistry reaperRegistry = new SimpleMeterRegistry();
    LiveSyncPresenceService mockService = mock(LiveSyncPresenceService.class);
    when(mockService.snapshot(any(String.class), any(java.time.Instant.class)))
        .thenReturn(Map.of());
    when(mockService.reapExpired(any(java.time.Instant.class)))
        .thenReturn(List.of(new LiveSyncPresenceService.TopicSectionRef(topic, "crew")));
    LiveSyncWebSocketHandler reaperHandler =
        new LiveSyncWebSocketHandler(
            mockService, fanout, objectMapper, reaperRegistry, authorizer, Runnable::run);

    FakeSession session = new FakeSession();
    session.open = true;
    session.uri =
        URI.create(
            "ws://localhost/ws/missions/" + topic.substring("mission:".length()) + "/presence");
    session.attributes.put(LiveSyncWebSocketHandler.ATTR_TOPIC, topic);
    session.principal =
        new UsernamePasswordAuthenticationToken(
            oidcUser("user-1", "Alice"), "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER")));
    reaperHandler.afterConnectionEstablished(session);
    session.sent.clear();

    reaperHandler.tickReaper();

    // The reaper fans exactly one fresh presence snapshot to the affected room's socket.
    assertThat(session.sent).hasSize(1);
    JsonNode frame = objectMapper.readTree(((TextMessage) session.sent.get(0)).getPayload());
    assertThat(frame.get("type").asString()).isEqualTo("presence");
  }

  @Test
  void establishLegacySocket_withoutTopic_isRefused() throws Exception {
    // Defense-in-depth: a legacy socket whose handshake bound no ATTR_TOPIC must be closed
    // NOT_ACCEPTABLE and never registered — no room entry, no snapshot — so a mis-ordered guard or
    // a
    // misconfigured interceptor cannot let it register and receive presence snapshots (leaking
    // editor identities).
    FakeSession session = new FakeSession();
    session.open = true;
    session.uri = URI.create("ws://localhost/ws/missions/presence"); // no ATTR_TOPIC bound
    session.principal =
        new UsernamePasswordAuthenticationToken(
            oidcUser("user-1", "Alice"), "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER")));

    handler.afterConnectionEstablished(session);

    assertThat(session.closeStatus).isEqualTo(CloseStatus.NOT_ACCEPTABLE);
    assertThat(presenceGauge()).isZero();
    assertThat(session.sent).isEmpty();
  }

  @Test
  void establishLegacySocket_withoutPrincipal_isRefused() throws Exception {
    // A legacy socket that reaches the handler with no authenticated principal (Spring Security
    // failed to attach one) must be refused NOT_ACCEPTABLE, not registered, and sent no snapshot —
    // otherwise editor identities leak to an unauthenticated/malformed socket.
    String topic = missionTopic();
    FakeSession session = new FakeSession();
    session.open = true;
    session.uri =
        URI.create(
            "ws://localhost/ws/missions/" + topic.substring("mission:".length()) + "/presence");
    session.attributes.put(LiveSyncWebSocketHandler.ATTR_TOPIC, topic);
    session.principal = null;

    handler.afterConnectionEstablished(session);

    assertThat(session.closeStatus).isEqualTo(CloseStatus.NOT_ACCEPTABLE);
    assertThat(presenceGauge()).isZero();
    assertThat(session.sent).isEmpty();
  }

  // ── helpers ────────────────────────────────────────────────────────────────────────────────

  private static String missionTopic() {
    return "mission:" + UUID.randomUUID();
  }

  private static String operationTopic() {
    return "operation:" + UUID.randomUUID();
  }

  private static TextMessage changedFrame(String topic, String section) {
    return new TextMessage(
        "{\"type\":\"changed\",\"topic\":\"" + topic + "\",\"sections\":[\"" + section + "\"]}");
  }

  private static TextMessage subscribeFrame(String topic) {
    return new TextMessage("{\"type\":\"subscribe\",\"topic\":\"" + topic + "\"}");
  }

  private void subscribe(FakeSession session, String topic) throws Exception {
    handler.handleTextMessage(
        session, new TextMessage("{\"type\":\"subscribe\",\"topic\":\"" + topic + "\"}"));
  }

  private FakeSession openMultiplexedSession(OidcUser user) throws Exception {
    FakeSession session = multiplexedSession(user);
    handler.afterConnectionEstablished(session);
    return session;
  }

  /**
   * Builds — but does not establish — a multiplexed {@code /ws/sync} {@link FakeSession}, so a test
   * driving a non-default handler (its own executor / authorizer) can call {@code
   * afterConnectionEstablished} on that handler itself.
   *
   * @param user the socket owner
   * @return the un-established multiplexed session
   */
  private static FakeSession multiplexedSession(OidcUser user) {
    FakeSession session = new FakeSession();
    session.open = true;
    session.uri = URI.create("ws://localhost/ws/sync");
    session.attributes.put(LiveSyncWebSocketHandler.ATTR_MULTIPLEXED, Boolean.TRUE);
    session.principal =
        new UsernamePasswordAuthenticationToken(
            user, "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER")));
    return session;
  }

  private double subscribeCounter(String outcome, String topicClass) {
    var counter =
        registry
            .find(MetricNames.LIVESYNC_SUBSCRIBE)
            .tag(MetricNames.TAG_OUTCOME, outcome)
            .tag(MetricNames.TAG_TOPIC_CLASS, topicClass)
            .counter();
    return counter == null ? 0.0 : counter.count();
  }

  private static List<String> sectionsOf(JsonNode relayed) {
    List<String> sections = new ArrayList<>();
    relayed.get("sections").forEach(node -> sections.add(node.asString()));
    return sections;
  }

  private double presenceGauge() {
    return presenceGauge(registry);
  }

  private static double presenceGauge(SimpleMeterRegistry reg) {
    return reg.get(MetricNames.PRESENCE_WS_SESSIONS).gauge().value();
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
    return dropCounter(reason, "mission");
  }

  private double dropCounter(String reason, String topicClass) {
    var counter =
        registry
            .find(MetricNames.PRESENCE_RELAY_DROPPED)
            .tag(MetricNames.TAG_REASON, reason)
            .tag(MetricNames.TAG_TOPIC_CLASS, topicClass)
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

  /**
   * Opens a legacy board socket bound to the fixed global {@code materialboard} topic, as the
   * {@code /ws/materialboerse/board} handshake interceptor binds it via {@link
   * LiveSyncWebSocketHandler#ATTR_TOPIC}.
   *
   * @param user the socket owner
   * @return the established fake session
   * @throws Exception if the handler's connect callback throws
   */
  private FakeSession openBoardLegacySession(OidcUser user) throws Exception {
    FakeSession session = new FakeSession();
    session.open = true;
    session.uri = URI.create("ws://localhost/ws/materialboerse/board");
    session.attributes.put(LiveSyncWebSocketHandler.ATTR_TOPIC, "materialboard");
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

    /**
     * When {@code >= 0}, {@link #isOpen()} reports {@code true} for the first {@code flipOpenAfter}
     * calls and {@code false} thereafter — used to reproduce a close that races in mid-way through
     * {@code completeSubscribe} (open at the pre-join check, closed at the post-join check). {@code
     * -1} (the default) reports the plain {@link #open} field, leaving every other test unaffected.
     */
    int flipOpenAfter = -1;

    private int openChecks;

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
      if (flipOpenAfter >= 0) {
        return openChecks++ < flipOpenAfter;
      }
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
