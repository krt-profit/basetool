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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import java.util.concurrent.atomic.AtomicLong;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
 * Tests for {@link LiveSyncWebSocketHandler} — the multiplexed {@code /ws/sync} relay.
 *
 * <p>Drives the handler through a hand-rolled {@link FakeSession} that records outbound messages so
 * the JSON wire format, room membership, principal-resolution and broadcast behaviour can be
 * verified without a real servlet container. Every socket is a multiplexed {@code /ws/sync} socket
 * (marked by the {@link LiveSyncWebSocketHandler#ATTR_MULTIPLEXED} attribute the {@code /ws/sync}
 * handshake interceptor sets); a socket joins a room by sending a {@code subscribe} frame, and its
 * {@code changed} / presence frames carry their own {@code topic}.
 */
class LiveSyncWebSocketHandlerTest {

  private LiveSyncPresenceService service;
  private ObjectMapper objectMapper;
  private LiveSyncWebSocketHandler handler;
  private CapturingFanout fanout;
  private SimpleMeterRegistry registry;
  private LiveSyncSubscriptionAuthorizer authorizer;

  /**
   * Frozen monotonic clock the handler's token buckets read, so a burst never refills mid-test.
   * Keeping it fixed makes the throttle assertions exact (relayed count == the burst) instead of
   * tolerating wall-clock refill, which flakes under CI load. Tests that need time to pass advance
   * it explicitly.
   */
  private AtomicLong nanoClock;

  /**
   * Captures the handler's own log events so the tests can assert the <em>level</em> where it is
   * the contract: a client-supplied {@code changed} frame's filtered section keys must stay at
   * DEBUG (an attacker-triggerable flood at any higher level), while a backend-triggered
   * fail-closed subscribe must reach WARN.
   */
  private ListAppender<ILoggingEvent> logAppender;

  /** The handler logger's configured level, restored after each test. */
  private Level previousLogLevel;

  @BeforeEach
  void attachLogAppender() {
    Logger logger = (Logger) LoggerFactory.getLogger(LiveSyncWebSocketHandler.class);
    previousLogLevel = logger.getLevel();
    logger.setLevel(Level.DEBUG);
    logAppender = new ListAppender<>();
    logAppender.start();
    logger.addAppender(logAppender);
  }

  @AfterEach
  void detachLogAppender() {
    Logger logger = (Logger) LoggerFactory.getLogger(LiveSyncWebSocketHandler.class);
    logger.detachAppender(logAppender);
    logger.setLevel(previousLogLevel);
  }

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    service = new LiveSyncPresenceService(registry);
    objectMapper = JsonMapper.builder().build();
    fanout = new CapturingFanout();
    authorizer = mock(LiveSyncSubscriptionAuthorizer.class);
    nanoClock = new AtomicLong();
    // Default: authorize any subscribe. Individual tests override to DENY where they need it. The
    // executor is direct (Runnable::run) so an async subscribe-authorize completes synchronously in
    // the test thread — the saturation path uses a throwing executor instead.
    when(authorizer.authorize(any(), any(), any(), any()))
        .thenReturn(LiveSyncSubscriptionAuthorizer.Decision.ALLOW);
    handler =
        new LiveSyncWebSocketHandler(
            service, fanout, objectMapper, registry, authorizer, Runnable::run, nanoClock::get);
  }

  @Test
  void blurMessage_clearsPresence_andBroadcastsEmptySection() throws Exception {
    String topic = missionTopic();
    FakeSession session = openSubscribed(topic, oidcUser("user-1", "Alice"));
    handler.handleTextMessage(session, presenceFrame("focus", topic, "crew"));

    session.sent.clear();
    handler.handleTextMessage(session, presenceFrame("blur", topic, "crew"));

    assertThat(service.get(topic, "crew", "user-1")).isNull();
    JsonNode broadcast = lastBroadcast(session);
    // After the blur the snapshot has no sections at all (the entry was the only one).
    assertThat(broadcast.get("type").asString()).isEqualTo("presence");
    assertThat(broadcast.get("sections").size()).isZero();
  }

  @Test
  void focusAndBlur_gossipThisInstancesPresenceSnapshotToPeers() throws Exception {
    String topic = missionTopic();
    FakeSession session = openSubscribed(topic, oidcUser("user-1", "Alice"));

    handler.handleTextMessage(session, presenceFrame("focus", topic, "crew"));

    assertThat(fanout.presenceTopics).containsExactly(topic);
    assertThat(fanout.presenceSnapshots.get(0))
        .containsOnlyKeys("crew")
        .extractingByKey("crew")
        .asInstanceOf(InstanceOfAssertFactories.list(LiveSyncPresenceService.PresenceEditor.class))
        .extracting(LiveSyncPresenceService.PresenceEditor::userId)
        .containsExactly("user-1");

    handler.handleTextMessage(session, presenceFrame("blur", topic, "crew"));

    // The blur gossips an EMPTY snapshot rather than nothing at all: that is what drops this
    // instance's partition on the peers immediately instead of leaving the dot up until the
    // partition TTL expires (ADR-0126).
    assertThat(fanout.presenceTopics).containsExactly(topic, topic);
    assertThat(fanout.presenceSnapshots.get(1)).isEmpty();
  }

  @Test
  void deliverPresenceFromFanout_mergesAPeerReplicasEditors_andBroadcastsTheMergedDots()
      throws Exception {
    String topic = missionTopic();
    FakeSession local = openSubscribed(topic, oidcUser("user-1", "Alice"));
    handler.handleTextMessage(local, presenceFrame("focus", topic, "crew"));
    local.sent.clear();
    fanout.presenceTopics.clear();

    handler.deliverPresenceFromFanout(
        topic,
        "instance-B",
        Map.of("steps", List.of(new LiveSyncPresenceService.PresenceEditor("user-2", "Bob"))));

    JsonNode broadcast = lastBroadcast(local);
    assertThat(broadcast.get("type").asString()).isEqualTo("presence");
    assertThat(broadcast.get("sections").get("crew").get(0).get("userId").asString())
        .isEqualTo("user-1");
    assertThat(broadcast.get("sections").get("steps").get(0).get("displayName").asString())
        .isEqualTo("Bob");
    // Consume must never re-publish — two replicas would otherwise echo each other forever.
    assertThat(fanout.presenceTopics).isEmpty();
  }

  @Test
  void deliverPresenceFromFanout_ignoresANonPresenceTopicClass() {
    String topic = "operation:5f1d2c3b-0000-0000-0000-000000000009";

    handler.deliverPresenceFromFanout(
        topic,
        "instance-B",
        Map.of("overview", List.of(new LiveSyncPresenceService.PresenceEditor("user-2", "Bob"))));

    // A peer must not be able to open a presence surface on a class that carries no dots.
    assertThat(service.remotePartitionCount()).isZero();
  }

  @Test
  void deliverPresenceFromFanout_dropsAnOverLongSectionKey() {
    String topic = missionTopic();

    handler.deliverPresenceFromFanout(
        topic,
        "instance-B",
        Map.of(
            "x".repeat(65), List.of(new LiveSyncPresenceService.PresenceEditor("user-2", "Bob"))));

    // Same shape bound an inbound client presence frame carries: a peer replica is re-validated,
    // not trusted.
    assertThat(service.remotePartitionCount()).isZero();
  }

  @Test
  void reaperTick_reGossipsEveryTrackedPresenceTopic() throws Exception {
    String topic = missionTopic();
    FakeSession session = openSubscribed(topic, oidcUser("user-1", "Alice"));
    handler.handleTextMessage(session, presenceFrame("focus", topic, "crew"));
    fanout.presenceTopics.clear();

    handler.tickReaper();

    // The periodic re-gossip is what heals a dropped message and seeds a replica that started
    // after the focus happened.
    assertThat(fanout.presenceTopics).containsExactly(topic);
  }

  @Test
  void reaperTick_gossipsNothing_whenNobodyIsEditing() {
    handler.tickReaper();

    assertThat(fanout.presenceTopics).isEmpty();
  }

  @Test
  void heartbeat_doesNotBroadcast_whenUserAlreadyKnown() throws Exception {
    String topic = missionTopic();
    FakeSession session = openSubscribed(topic, oidcUser("user-1", "Alice"));
    handler.handleTextMessage(session, presenceFrame("focus", topic, "crew"));

    int countAfterFocus = session.sent.size();
    handler.handleTextMessage(session, presenceFrame("heartbeat", topic, "crew"));

    // Heartbeat from an already-known editor must NOT trigger a broadcast — generating one frame
    // per
    // heartbeat per connected client per topic would be wasteful and visually pointless because the
    // state didn't change.
    assertThat(session.sent).hasSize(countAfterFocus);
  }

  @Test
  void malformedPayload_isSilentlyDropped() throws Exception {
    FakeSession session = openMultiplexedSession(oidcUser("user-1", "Alice"));
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
    FakeSession aliceSession = openSubscribed(topic, oidcUser("user-1", "Alice"));
    FakeSession bobSession = openSubscribed(topic, oidcUser("user-2", "Bob"));
    handler.handleTextMessage(aliceSession, presenceFrame("focus", topic, "crew"));
    handler.handleTextMessage(bobSession, presenceFrame("focus", topic, "steps"));

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
    FakeSession tabA = openSubscribed(topic, oidcUser("user-1", "Alice"));
    FakeSession tabB = openSubscribed(topic, oidcUser("user-1", "Alice"));
    handler.handleTextMessage(tabA, presenceFrame("focus", topic, "crew"));

    tabA.open = false;
    handler.afterConnectionClosed(tabA, CloseStatus.NORMAL);

    // The other tab is still alive — Alice's "crew" presence must survive.
    assertThat(service.get(topic, "crew", "user-1")).isNotNull();
    assertThat(tabB.isOpen()).isTrue();
  }

  @Test
  void changedSignal_dropsUnknownKeysAndDeduplicates() throws Exception {
    String topic = missionTopic();
    FakeSession bob = openSubscribed(topic, oidcUser("user-2", "Bob"));
    bob.sent.clear();
    FakeSession alice = openMultiplexedSession(oidcUser("user-1", "Alice"));

    handler.handleTextMessage(
        alice,
        new TextMessage(
            "{\"type\":\"changed\",\"topic\":\""
                + topic
                + "\",\"sections\":[\"crew\",\"bogus\",\"crew\",\"mgmt\",42]}"));

    JsonNode relayed = lastBroadcast(bob);
    // "bogus" and the non-string 42 are dropped; the duplicate "crew" appears once.
    assertThat(sectionsOf(relayed)).containsExactly("crew", "mgmt");
  }

  @Test
  void changedSignal_relaysEverySectionOfTheMissionSeamMap() throws Exception {
    String topic = missionTopic();
    FakeSession bob = openSubscribed(topic, oidcUser("user-2", "Bob"));
    bob.sent.clear();
    FakeSession alice = openMultiplexedSession(oidcUser("user-1", "Alice"));

    // Every key of the MISSION_SECTIONS seam map in mission-detail.js must pass the relay whitelist
    // — steps/objectives/frequencies were once dropped here, leaving peers' Verwaltung editors
    // stale
    // until a manual reload (REQ-FE-010).
    handler.handleTextMessage(
        alice,
        changedFrame(
            topic,
            "crew",
            "finance",
            "mgmt",
            "overview",
            "steps",
            "objectives",
            "frequencies",
            "organisation"));

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
    FakeSession bob = openSubscribed(topic, oidcUser("user-2", "Bob"));
    bob.sent.clear();
    FakeSession alice = openMultiplexedSession(oidcUser("user-1", "Alice"));

    handler.handleTextMessage(
        alice,
        new TextMessage(
            "{\"type\":\"changed\",\"topic\":\"" + topic + "\",\"sections\":[\"bogus\"]}"));
    handler.handleTextMessage(
        alice,
        new TextMessage("{\"type\":\"changed\",\"topic\":\"" + topic + "\",\"sections\":[]}"));
    handler.handleTextMessage(
        alice, new TextMessage("{\"type\":\"changed\",\"topic\":\"" + topic + "\"}"));

    // Nothing valid to relay — peers receive no frame, presence is untouched, and nothing fans out.
    assertThat(bob.sent).isEmpty();
    assertThat(service.trackedTopics()).isEmpty();
    assertThat(fanout.publishedTopics).isEmpty();
  }

  @Test
  void changedSignal_isRateLimitedPerSession() throws Exception {
    String topic = missionTopic();
    FakeSession bob = openSubscribed(topic, oidcUser("user-2", "Bob"));
    bob.sent.clear();
    FakeSession alice = openMultiplexedSession(oidcUser("user-1", "Alice"));

    // Emit far more than the per-session burst, simulating a crafted flooding client. The clock is
    // frozen (see nanoClock), so the bucket never refills mid-loop and exactly the burst passes.
    int emitted = LiveSyncWebSocketHandler.CHANGED_BURST + 40;
    for (int i = 0; i < emitted; i++) {
      handler.handleTextMessage(alice, changedFrame(topic, "crew"));
    }

    // The token bucket caps relayed frames at exactly the burst, so the peer receives far fewer
    // frames than were emitted.
    assertThat(bob.sent.size()).isEqualTo(LiveSyncWebSocketHandler.CHANGED_BURST);
  }

  @Test
  void changedSignal_isPublishedToTheFanoutAfterLocalRelay() throws Exception {
    String topic = missionTopic();
    openSubscribed(topic, oidcUser("user-2", "Bob"));
    FakeSession alice = openMultiplexedSession(oidcUser("user-1", "Alice"));

    handler.handleTextMessage(alice, changedFrame(topic, "crew", "finance"));

    // A cross-replica fan-out publish carries the canonical topic and the sanitised sections.
    assertThat(fanout.publishedTopics).containsExactly(topic);
    assertThat(fanout.publishedSections).containsExactly(List.of("crew", "finance"));
  }

  @Test
  void deliverFromFanout_relaysToLocalRoom_withoutOriginExclusion() throws Exception {
    String topic = missionTopic();
    FakeSession alice = openSubscribed(topic, oidcUser("user-1", "Alice"));
    FakeSession bob = openSubscribed(topic, oidcUser("user-2", "Bob"));
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
  void broadcasts_countSnapshotAndChangedRelayFrames() throws Exception {
    String topic = missionTopic();
    openSubscribed(topic, oidcUser("user-2", "Bob"));
    FakeSession alice = openMultiplexedSession(oidcUser("user-1", "Alice"));

    handler.handleTextMessage(alice, changedFrame(topic, "crew"));

    // The relayed change is one changed frame; the per-subscribe snapshots are snapshot frames.
    assertThat(frameCounter(MetricNames.FRAME_CHANGED)).isEqualTo(1.0);
    assertThat(frameCounter(MetricNames.FRAME_SNAPSHOT)).isGreaterThan(0.0);
  }

  @Test
  void throttledChangedFrames_areCountedAsDroppedThrottled() throws Exception {
    String topic = missionTopic();
    openSubscribed(topic, oidcUser("user-2", "Bob"));
    FakeSession alice = openMultiplexedSession(oidcUser("user-1", "Alice"));

    int emitted = LiveSyncWebSocketHandler.CHANGED_BURST + 40;
    for (int i = 0; i < emitted; i++) {
      handler.handleTextMessage(alice, changedFrame(topic, "crew"));
    }

    // Every frame past the per-session token bucket is now counted (previously a silent drop).
    assertThat(dropCounter(MetricNames.DROPPED_THROTTLED)).isGreaterThan(0.0);
  }

  @Test
  void sendFailureToBrokenPeer_isCountedAsDroppedSendFailed() throws Exception {
    String topic = missionTopic();
    FakeSession bob = openSubscribed(topic, oidcUser("user-2", "Bob"));
    // Bob's socket reports open but every write throws (a half-broken connection). Set only after
    // the subscribe snapshot has been delivered, so the send_failed / changed counts below observe
    // the changed relay alone.
    bob.failSend = true;
    FakeSession alice = openMultiplexedSession(oidcUser("user-1", "Alice"));

    handler.handleTextMessage(alice, changedFrame(topic, "crew"));

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

    // "crew" is a mission section, not an operation section — dropped; "overview"/"finance" kept
    // ("finance" is cross-published from the mission surface, #1241).
    handler.handleTextMessage(
        alice,
        new TextMessage(
            "{\"type\":\"changed\",\"topic\":\""
                + topic
                + "\",\"sections\":[\"overview\",\"crew\",\"finance\"]}"));

    assertThat(sectionsOf(lastBroadcast(bob))).containsExactly("overview", "finance");
  }

  @Test
  void multiplexedSubscribe_unknownTopic_isDeniedAndCounts() throws Exception {
    FakeSession bob = openMultiplexedSession(oidcUser("user-2", "Bob"));
    handler.handleTextMessage(
        bob, new TextMessage("{\"type\":\"subscribe\",\"topic\":\"bogus:not-a-thing\"}"));
    assertThat(lastBroadcast(bob).get("type").asString()).isEqualTo("denied");
    // #1239: the unparseable-topic subscribe is counted on the unlabelled invalid-topic meter so a
    // client/server topic-vocabulary skew is visible; no `topic_class` because it parsed to none.
    assertThat(invalidTopicCounter()).isEqualTo(1.0);
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
  void multiplexedSubscribe_presenceClassAuthorizerThrows_failsClosed() throws Exception {
    // F1: for a PRESENCE class (mission), an indeterminate verdict (the probe throwing) fails
    // CLOSED — the socket is refused and never joins, so no editor-identity snapshot is emitted and
    // a later peer change reaches it with nothing. Contrast the operation topic above (fails open).
    LiveSyncSubscriptionAuthorizer throwing = mock(LiveSyncSubscriptionAuthorizer.class);
    when(throwing.authorize(any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("probe blew up"));
    SimpleMeterRegistry reg = new SimpleMeterRegistry();
    LiveSyncPresenceService svc = new LiveSyncPresenceService(reg);
    LiveSyncWebSocketHandler h =
        new LiveSyncWebSocketHandler(svc, fanout, objectMapper, reg, throwing, Runnable::run);

    String topic = missionTopic();
    FakeSession bob = multiplexedSession(oidcUser("user-2", "Bob"));
    h.afterConnectionEstablished(bob);
    h.handleTextMessage(bob, subscribeFrame(topic));

    assertThat(lastBroadcast(bob).get("type").asString()).isEqualTo("denied");
    bob.sent.clear();
    FakeSession alice = multiplexedSession(oidcUser("user-1", "Alice"));
    h.afterConnectionEstablished(alice);
    h.handleTextMessage(alice, changedFrame(topic, "crew"));
    assertThat(bob.sent).isEmpty();
  }

  @Test
  void multiplexedSubscribe_presenceClassExecutorSaturated_failsClosed() throws Exception {
    // F1: auth-executor saturation is indeterminate; a presence class fails CLOSED (the operation
    // topic in multiplexedSubscribe_executorSaturated_failsOpenAndCounts fails open instead).
    SimpleMeterRegistry reg = new SimpleMeterRegistry();
    LiveSyncPresenceService svc = new LiveSyncPresenceService(reg);
    LiveSyncWebSocketHandler saturated =
        new LiveSyncWebSocketHandler(
            svc,
            fanout,
            objectMapper,
            reg,
            authorizer,
            runnable -> {
              throw new RejectedExecutionException("auth executor full");
            });

    String topic = missionTopic();
    FakeSession bob = multiplexedSession(oidcUser("user-2", "Bob"));
    saturated.afterConnectionEstablished(bob);
    saturated.handleTextMessage(bob, subscribeFrame(topic));

    assertThat(lastBroadcast(bob).get("type").asString()).isEqualTo("denied");
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
  void peerRoomsGauge_countsOnlyRoomsThatActuallyHoldPeers() throws Exception {
    String room = operationTopic();
    FakeSession alice = openMultiplexedSession(oidcUser("user-1", "Alice"));
    subscribe(alice, room);

    // A lone viewer is not a peer room: relayLocal skips the origin, so nothing can ever be
    // relayed here and a `changed` flatline is the correct, healthy state.
    assertThat(subscriptionsGauge("operation")).isEqualTo(1.0);
    assertThat(peerRoomsGauge("operation")).isZero();

    FakeSession bob = openMultiplexedSession(oidcUser("user-2", "Bob"));
    subscribe(bob, room);

    // Two sockets in the SAME room — peer-sync is live.
    assertThat(peerRoomsGauge("operation")).isEqualTo(1.0);

    bob.open = false;
    handler.afterConnectionClosed(bob, CloseStatus.NORMAL);
    assertThat(peerRoomsGauge("operation")).isZero();
  }

  @Test
  void peerRoomsGauge_separatesCoPresenceFromTwoLoneViewers() throws Exception {
    // THE case the subscriptions gauge cannot express, and the reason this gauge exists: two
    // sockets in the same topic class but in DIFFERENT rooms. `subscriptions` reads 2 either way,
    // so it cannot tell peer-sync being exercised from peer-sync being inert — which is what makes
    // a `changed`-frame flatline uninterpretable without this gauge (#1238).
    FakeSession alice = openMultiplexedSession(oidcUser("user-1", "Alice"));
    subscribe(alice, operationTopic());
    FakeSession bob = openMultiplexedSession(oidcUser("user-2", "Bob"));
    subscribe(bob, operationTopic());

    assertThat(subscriptionsGauge("operation")).isEqualTo(2.0);
    assertThat(peerRoomsGauge("operation")).isZero();
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

  // ── Consent gate (REQ-SEC-028): a marked handshake is refused terminally ──────────────────────

  /**
   * A handshake the consent gate marked is refused with {@code 4003} carrying the consent page.
   *
   * <p>All three parts are the contract and each alone is useless. The code is what {@code
   * krt-live-sync.js} recognises as terminal; without it the close is just a close and the client
   * reconnects forever, which is the defect. The reason is where the client learns which page can
   * end the refusal. And the socket must actually be closed — a marked socket left open would relay
   * peer changes to a user the backend refuses every fragment fetch for.
   */
  @Test
  void consentGate_refusesTheSocketWithATerminalCloseCodeAndTheConsentUrl() throws Exception {
    FakeSession session = multiplexedSession(oidcUser("user-1", "Alice"));
    session.attributes.put(LiveSyncWebSocketHandler.ATTR_TERMS_GATE, "/terms/accept");

    handler.afterConnectionEstablished(session);

    assertThat(session.closeStatus).isNotNull();
    assertThat(session.closeStatus.getCode())
        .isEqualTo(LiveSyncWebSocketHandler.TERMS_CONSENT_REQUIRED_CODE);
    assertThat(session.closeStatus.getReason()).isEqualTo("/terms/accept");
    assertThat(session.open).isFalse();
    assertThat(socketRejectedCounter(MetricNames.SOCKET_REJECTED_TERMS_GATE)).isEqualTo(1.0);
  }

  /**
   * A gated refusal consumes no per-user socket slot.
   *
   * <p>The check runs before the cap is acquired, so a tab reconnecting against a closed gate
   * cannot exhaust the user's own budget and turn a consent prompt into a socket-cap refusal once
   * they accept. Driven past the cap deliberately: with the checks in the other order this fails.
   */
  @Test
  void consentGate_refusalTakesNoUserSocketSlot() throws Exception {
    OidcUser bob = oidcUser("user-2", "Bob");
    for (int i = 0; i < LiveSyncWebSocketHandler.MAX_SOCKETS_PER_USER + 1; i++) {
      FakeSession gated = multiplexedSession(bob);
      gated.attributes.put(LiveSyncWebSocketHandler.ATTR_TERMS_GATE, "/terms/accept");
      handler.afterConnectionEstablished(gated);
    }

    // Consent recorded: the very next handshake is unmarked and must be accepted, not capped.
    assertThat(openMultiplexedSession(bob).closeStatus).isNull();
  }

  /**
   * A consent URL too long for a close frame is dropped, but the socket is still closed with the
   * terminal code. Losing the redirect costs the user one navigation; failing the close outright
   * (which is what an over-long reason does to the container) would hand back the reconnect loop.
   */
  @Test
  void consentGate_dropsAnUnsendableConsentUrlButStillClosesTerminally() throws Exception {
    FakeSession session = multiplexedSession(oidcUser("user-1", "Alice"));
    session.attributes.put(
        LiveSyncWebSocketHandler.ATTR_TERMS_GATE, "/" + "x".repeat(200) + "/terms/accept");

    handler.afterConnectionEstablished(session);

    assertThat(session.closeStatus).isNotNull();
    assertThat(session.closeStatus.getCode())
        .isEqualTo(LiveSyncWebSocketHandler.TERMS_CONSENT_REQUIRED_CODE);
    assertThat(session.closeStatus.getReason()).isNull();
  }

  // ── Abuse bounds (F2 / #1243): per-user socket cap + per-topic publish throttle ───────────────

  @Test
  void perUserSocketCap_refusesBeyondTheCap_andCounts() throws Exception {
    OidcUser bob = oidcUser("user-2", "Bob");
    for (int i = 0; i < LiveSyncWebSocketHandler.MAX_SOCKETS_PER_USER; i++) {
      // Every socket up to the cap is accepted (not closed).
      assertThat(openMultiplexedSession(bob).closeStatus).isNull();
    }

    // The one past the cap is refused with the dedicated cap close status and counted.
    FakeSession overCap = openMultiplexedSession(bob);
    assertThat(overCap.closeStatus).isEqualTo(LiveSyncWebSocketHandler.SOCKET_CAP_EXCEEDED);
    assertThat(socketRejectedCounter(MetricNames.SOCKET_REJECTED_USER_CAP)).isEqualTo(1.0);
  }

  @Test
  void perUserSocketCap_decrementsOnClose_allowingANewSocket() throws Exception {
    OidcUser bob = oidcUser("user-2", "Bob");
    List<FakeSession> sockets = new ArrayList<>();
    for (int i = 0; i < LiveSyncWebSocketHandler.MAX_SOCKETS_PER_USER; i++) {
      sockets.add(openMultiplexedSession(bob));
    }
    // At the cap: a further socket is refused.
    assertThat(openMultiplexedSession(bob).closeStatus)
        .isEqualTo(LiveSyncWebSocketHandler.SOCKET_CAP_EXCEEDED);

    // Closing one frees a slot (the close path decrements the per-user count exactly once)...
    FakeSession first = sockets.get(0);
    first.open = false;
    handler.afterConnectionClosed(first, CloseStatus.NORMAL);

    // ...so the next socket now fits.
    assertThat(openMultiplexedSession(bob).closeStatus).isNull();
  }

  @Test
  void perUserSocketCap_isPerUser_soAnotherUserIsUnaffected() throws Exception {
    OidcUser bob = oidcUser("user-2", "Bob");
    for (int i = 0; i < LiveSyncWebSocketHandler.MAX_SOCKETS_PER_USER; i++) {
      openMultiplexedSession(bob);
    }
    assertThat(openMultiplexedSession(bob).closeStatus)
        .isEqualTo(LiveSyncWebSocketHandler.SOCKET_CAP_EXCEEDED);

    // A different user's socket is unaffected by Bob's saturation — the cap is per-user, not
    // global.
    assertThat(openMultiplexedSession(oidcUser("user-1", "Alice")).closeStatus).isNull();
  }

  @Test
  void perTopicThrottle_boundsAggregateRelayAcrossPublishers() throws Exception {
    String topic = operationTopic();
    FakeSession subscriber = openMultiplexedSession(oidcUser("sub", "Sub"));
    subscribe(subscriber, topic);
    subscriber.sent.clear();

    // Several distinct publishers, each staying within its own per-session burst, together exceed
    // the per-topic burst. The per-session bucket alone cannot bound the room's aggregate rate; the
    // per-topic bucket does. Deriving the publisher count from the two constants keeps the test
    // valid whatever the tuned values are: (TOPIC_CHANGED_BURST / CHANGED_BURST) + 2 publishers,
    // each emitting a full per-session burst, always overshoots the per-topic burst.
    int perPublisher = LiveSyncWebSocketHandler.CHANGED_BURST;
    int publishers = (LiveSyncWebSocketHandler.TOPIC_CHANGED_BURST / perPublisher) + 2;
    for (int p = 0; p < publishers; p++) {
      FakeSession pub = openMultiplexedSession(oidcUser("pub-" + p, "P" + p));
      for (int i = 0; i < perPublisher; i++) {
        handler.handleTextMessage(pub, changedFrame(topic, "overview"));
      }
    }
    int emitted = publishers * perPublisher;

    // The room's relayed frames are capped at exactly the per-topic burst — the clock is frozen
    // (see
    // nanoClock) so the bucket never refills across the publishers — far below the total emitted;
    // the
    // overflow is counted as topic_throttled.
    assertThat(emitted).isGreaterThan(LiveSyncWebSocketHandler.TOPIC_CHANGED_BURST);
    assertThat(subscriber.sent.size()).isEqualTo(LiveSyncWebSocketHandler.TOPIC_CHANGED_BURST);
    assertThat(dropCounter(MetricNames.DROPPED_TOPIC_THROTTLED, "operation")).isGreaterThan(0.0);
  }

  @Test
  void perTopicBuckets_areReapedWhenIdle() throws Exception {
    String topic = operationTopic();
    FakeSession pub = openMultiplexedSession(oidcUser("user-1", "Alice"));
    // A single accepted publish creates the topic's bucket (relay to the empty room is a no-op).
    handler.handleTextMessage(pub, changedFrame(topic, "overview"));
    assertThat(handler.topicBucketCount()).isEqualTo(1);

    // Reaping with a clock well past the idle window drops the idle bucket; recreating it full on
    // the next publish is behaviourally identical, so the map stays bounded to active rooms.
    handler.reapIdleTopicBuckets(
        nanoClock.get() + LiveSyncWebSocketHandler.TOPIC_BUCKET_IDLE_REAP_NANOS * 3);
    assertThat(handler.topicBucketCount()).isZero();
  }

  // ── Presence-frame hardening (#1245, ported onto the generalized handler) ──────────────────────

  @Test
  void presenceFrames_areRateLimitedPerSession() throws Exception {
    String topic = missionTopic();
    FakeSession alice = openSubscribed(topic, oidcUser("user-1", "Alice"));
    openSubscribed(topic, oidcUser("user-2", "Bob"));

    // Flood focus frames (distinct section keys, under the section cap) far past the presence
    // burst,
    // simulating a crafted client looping presence frames.
    int emitted = LiveSyncWebSocketHandler.PRESENCE_BURST + 20;
    for (int i = 0; i < emitted; i++) {
      handler.handleTextMessage(alice, presenceFrame("focus", topic, "sec-" + i));
    }

    // Every presence frame past the per-session token bucket is counted as a throttled drop.
    assertThat(dropCounter(MetricNames.DROPPED_THROTTLED)).isGreaterThan(0.0);
  }

  @Test
  void presenceFrame_withOverLongSectionKey_isDropped() throws Exception {
    String topic = missionTopic();
    FakeSession alice = openSubscribed(topic, oidcUser("user-1", "Alice"));

    // A section key longer than MAX_SECTION_KEY_LENGTH (64) is a crafted memory-bloat attempt and
    // is
    // dropped before it can insert a presence entry.
    String longKey = "x".repeat(65);
    handler.handleTextMessage(alice, presenceFrame("focus", topic, longKey));

    assertThat(service.get(topic, longKey, "user-1")).isNull();
  }

  // ── Materialbörse board (global multiplexed materialboard room) ──────────────────────────────

  @Test
  void multiplexedChanged_materialboardRoom_dropsSectionsOutsideWhitelist() throws Exception {
    FakeSession subscriber = openSubscribed("materialboard", oidcUser("user-2", "Bob"));
    subscriber.sent.clear();
    FakeSession publisher = openMultiplexedSession(oidcUser("user-1", "Alice"));

    handler.handleTextMessage(publisher, changedFrame("materialboard", "secret"));

    // Only `board` is accept-listed for the materialboard class; anything else is dropped.
    assertThat(subscriber.sent).isEmpty();
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

  // ── Reaper + connect-time refusal ────────────────────────────────────────────────────────────

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

    FakeSession session = multiplexedSession(oidcUser("user-1", "Alice"));
    reaperHandler.afterConnectionEstablished(session);
    reaperHandler.handleTextMessage(session, subscribeFrame(topic));
    session.sent.clear();

    reaperHandler.tickReaper();

    // The reaper fans exactly one fresh presence snapshot to the affected room's socket.
    assertThat(session.sent).hasSize(1);
    JsonNode frame = objectMapper.readTree(((TextMessage) session.sent.get(0)).getPayload());
    assertThat(frame.get("type").asString()).isEqualTo("presence");
  }

  @Test
  void establishSocket_withoutPrincipal_isRefused() throws Exception {
    // A /ws/sync socket that reaches the handler with no authenticated principal (Spring Security
    // failed to attach one) must be refused NOT_ACCEPTABLE, not registered, and sent no snapshot —
    // otherwise editor identities leak to an unauthenticated/malformed socket.
    FakeSession session = new FakeSession();
    session.open = true;
    session.uri = URI.create("ws://localhost/ws/sync");
    session.attributes.put(LiveSyncWebSocketHandler.ATTR_MULTIPLEXED, Boolean.TRUE);
    session.principal = null;

    handler.afterConnectionEstablished(session);

    assertThat(session.closeStatus).isEqualTo(CloseStatus.NOT_ACCEPTABLE);
    assertThat(presenceGauge()).isZero();
    assertThat(session.sent).isEmpty();
  }

  // -- Section-whitelist filtering is observable (REQ-FE-010 defect class) ----------------------

  @Test
  void changedFrame_withKeysOutsideTheWhitelist_countsOneSectionFilteredDrop() throws Exception {
    String topic = missionTopic();
    FakeSession alice = openMultiplexedSession(oidcUser("user-1", "Alice"));

    // Two unknown keys in ONE frame: the acting client's vocabulary has drifted from the relay's
    // accept-list, which is exactly how a panel goes stale for everyone with no error anywhere.
    handler.handleTextMessage(
        alice,
        new TextMessage(
            "{\"type\":\"changed\",\"topic\":\""
                + topic
                + "\",\"sections\":[\"crew\",\"bogus\",\"alsoBogus\"]}"));

    // Counted once per FRAME, not once per rejected key - otherwise one crafted frame is worth
    // MAX_CHANGED_SECTIONS increments and the series stops meaning "frames that lost a key".
    assertThat(dropCounter(MetricNames.DROPPED_SECTION_FILTERED)).isEqualTo(1.0);
  }

  @Test
  void changedFrame_withOnlyWhitelistedKeys_countsNoSectionFilteredDrop() throws Exception {
    String topic = missionTopic();
    FakeSession alice = openMultiplexedSession(oidcUser("user-1", "Alice"));

    handler.handleTextMessage(alice, changedFrame(topic, "crew", "crew", "mgmt"));

    // A collapsed duplicate is not vocabulary skew, so the healthy path leaves the series flat -
    // otherwise the signal is useless for spotting the real defect.
    assertThat(dropCounter(MetricNames.DROPPED_SECTION_FILTERED)).isZero();
  }

  @Test
  void changedFrame_withFilteredKeys_logsExactlyOneDebugLineCarryingTheSanitisedKey()
      throws Exception {
    String topic = missionTopic();
    FakeSession alice = openMultiplexedSession(oidcUser("user-1", "Alice"));
    logAppender.list.clear();

    handler.handleTextMessage(
        alice,
        new TextMessage(
            "{\"type\":\"changed\",\"topic\":\""
                + topic
                + "\",\"sections\":[\"bogusA\",\"bogusB\",\"bogusC\"]}"));

    // One line for the whole frame, at DEBUG because a client can emit these at will: the count is
    // reported and the first key is the sample, but the other rejected keys get no line of their
    // own.
    assertThat(logAppender.list).hasSize(1);
    ILoggingEvent event = logAppender.list.get(0);
    assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
    assertThat(event.getFormattedMessage()).contains("3", "bogusA").doesNotContain("bogusB");
  }

  @Test
  void changedFrame_withControlCharsInAFilteredKey_isSanitisedBeforeLogging() throws Exception {
    String topic = missionTopic();
    FakeSession alice = openMultiplexedSession(oidcUser("user-1", "Alice"));
    logAppender.list.clear();

    // The key is client-supplied free text: a newline plus a fabricated prefix must not be able to
    // forge a second log line (CWE-117).
    handler.handleTextMessage(
        alice,
        new TextMessage(
            "{\"type\":\"changed\",\"topic\":\""
                + topic
                + "\",\"sections\":[\"bo\\ngus ERROR --- forged\"]}"));

    assertThat(logAppender.list).hasSize(1);
    assertThat(logAppender.list.get(0).getFormattedMessage()).doesNotContain("\n");
  }

  @Test
  void changedFrame_withUnknownTopic_isLoggedAtDebugAndRelaysNothing() throws Exception {
    FakeSession alice = openMultiplexedSession(oidcUser("user-1", "Alice"));
    logAppender.list.clear();

    handler.handleTextMessage(
        alice, new TextMessage("{\"type\":\"changed\",\"topic\":\"bogus:not-a-thing\"}"));

    // Publish-side vocabulary skew used to be a bare return with no trace at all. DEBUG, since the
    // topic string is client-supplied.
    assertThat(logAppender.list).hasSize(1);
    assertThat(logAppender.list.get(0).getLevel()).isEqualTo(Level.DEBUG);
    assertThat(fanout.publishedTopics).isEmpty();
  }

  @Test
  void publishFromServer_withKeysOutsideTheWhitelist_countsTheSectionFilteredDrop() {
    // The server-originated publish path filters against the same whitelist, and a server/relay
    // vocabulary drift is the same silent staleness - so it is counted the same way.
    handler.publishFromServer("orders", List.of("bogus"));

    assertThat(dropCounter(MetricNames.DROPPED_SECTION_FILTERED, "orders_queue")).isEqualTo(1.0);
  }

  @Test
  void deliverFromFanout_withKeysOutsideTheWhitelist_countsTheSectionFilteredDrop() {
    // Defense-in-depth path: a peer replica on an older vocabulary is the cross-replica version of
    // the same defect, so it must be visible too.
    handler.deliverFromFanout("orders", List.of("bogus"));

    assertThat(dropCounter(MetricNames.DROPPED_SECTION_FILTERED, "orders_queue")).isEqualTo(1.0);
  }

  // -- Subscribe-deny reason split + fail-closed log level (M7) ---------------------------------

  @Test
  void multiplexedSubscribe_explicitDeny_tagsTheAuthzReason_andStaysBelowWarn() throws Exception {
    when(authorizer.authorize(any(), any(), any(), any()))
        .thenReturn(LiveSyncSubscriptionAuthorizer.Decision.DENY);
    FakeSession bob = openMultiplexedSession(oidcUser("user-2", "Bob"));
    logAppender.list.clear();
    subscribe(bob, operationTopic());

    // A real permission verdict: a steady trickle is normal, so it must not be tagged (or logged)
    // like an outage.
    assertThat(
            subscribeCounter(
                MetricNames.OUTCOME_DENIED, "operation", MetricNames.SUBSCRIBE_DENY_AUTHZ))
        .isEqualTo(1.0);
    assertThat(logAppender.list).noneMatch(e -> e.getLevel().isGreaterOrEqual(Level.WARN));
  }

  @Test
  void multiplexedSubscribe_indeterminateDeny_tagsItsOwnReason_andWarnsOnce() throws Exception {
    when(authorizer.authorize(any(), any(), any(), any()))
        .thenReturn(LiveSyncSubscriptionAuthorizer.Decision.DENY_INDETERMINATE);
    FakeSession bob = openMultiplexedSession(oidcUser("user-2", "Bob"));
    logAppender.list.clear();
    subscribe(bob, missionTopic());

    // A backend/token outage failing closed is NOT a permission verdict: its own reason value keeps
    // the two apart on the one always-on signal, and it is promoted to WARN because a denied
    // subscribe is terminal - the tab stays stale for the rest of the session. Exactly one line for
    // the one failure (REQ-OBS-001), not one per layer it passed through.
    assertThat(
            subscribeCounter(
                MetricNames.OUTCOME_DENIED, "mission", MetricNames.SUBSCRIBE_DENY_INDETERMINATE))
        .isEqualTo(1.0);
    assertThat(logAppender.list.stream().filter(e -> e.getLevel() == Level.WARN).count())
        .isEqualTo(1L);
  }

  @Test
  void multiplexedSubscribe_allowed_carriesTheReasonPlaceholder() throws Exception {
    FakeSession bob = openMultiplexedSession(oidcUser("user-2", "Bob"));
    subscribe(bob, operationTopic());

    // Micrometer rejects one meter name registered with differing tag-key sets, so the allowed
    // series must carry the reason tag too.
    assertThat(subscribeCounter(MetricNames.OUTCOME_ALLOWED, "operation", MetricNames.REASON_NONE))
        .isEqualTo(1.0);
  }

  @Test
  void multiplexedSubscribe_presenceClassAuthorizerThrows_warnsOnce() throws Exception {
    LiveSyncSubscriptionAuthorizer throwing = mock(LiveSyncSubscriptionAuthorizer.class);
    when(throwing.authorize(any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("probe blew up"));
    SimpleMeterRegistry reg = new SimpleMeterRegistry();
    LiveSyncPresenceService svc = new LiveSyncPresenceService(reg);
    LiveSyncWebSocketHandler h =
        new LiveSyncWebSocketHandler(svc, fanout, objectMapper, reg, throwing, Runnable::run);

    FakeSession bob = multiplexedSession(oidcUser("user-2", "Bob"));
    h.afterConnectionEstablished(bob);
    logAppender.list.clear();
    h.handleTextMessage(bob, subscribeFrame(missionTopic()));

    // A throwing probe on a presence class fails CLOSED - user-visible and backend-triggered, so it
    // is a WARN, not the DEBUG the fail-OPEN direction gets.
    assertThat(logAppender.list.stream().filter(e -> e.getLevel() == Level.WARN).count())
        .isEqualTo(1L);
  }

  @Test
  void multiplexedSubscribe_authorizerThrowsOnFailOpenClass_staysAtDebug() throws Exception {
    LiveSyncSubscriptionAuthorizer throwing = mock(LiveSyncSubscriptionAuthorizer.class);
    when(throwing.authorize(any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("probe blew up"));
    SimpleMeterRegistry reg = new SimpleMeterRegistry();
    LiveSyncPresenceService svc = new LiveSyncPresenceService(reg);
    LiveSyncWebSocketHandler h =
        new LiveSyncWebSocketHandler(svc, fanout, objectMapper, reg, throwing, Runnable::run);

    FakeSession bob = multiplexedSession(oidcUser("user-2", "Bob"));
    h.afterConnectionEstablished(bob);
    logAppender.list.clear();
    h.handleTextMessage(bob, subscribeFrame(operationTopic()));

    // Failing OPEN costs the user nothing (the subscribe is accepted), so it must not warn.
    assertThat(logAppender.list).noneMatch(e -> e.getLevel().isGreaterOrEqual(Level.WARN));
  }

  @Test
  void multiplexedSubscribe_executorSaturated_warns() throws Exception {
    SimpleMeterRegistry reg = new SimpleMeterRegistry();
    LiveSyncPresenceService svc = new LiveSyncPresenceService(reg);
    LiveSyncWebSocketHandler saturated =
        new LiveSyncWebSocketHandler(
            svc,
            fanout,
            objectMapper,
            reg,
            authorizer,
            runnable -> {
              throw new RejectedExecutionException("auth executor full");
            });

    FakeSession bob = multiplexedSession(oidcUser("user-2", "Bob"));
    saturated.afterConnectionEstablished(bob);
    logAppender.list.clear();
    saturated.handleTextMessage(bob, subscribeFrame(operationTopic()));

    // The saturation branch was previously unlogged entirely; it is infrastructure-triggered, so
    // WARN is not a client-drivable flood.
    assertThat(logAppender.list.stream().filter(e -> e.getLevel() == Level.WARN).count())
        .isEqualTo(1L);
  }

  // -- Subscribe-frame rate limit (the deny -> re-subscribe cycle) ------------------------------

  @Test
  void subscribeFrames_areRateLimitedPerSession() throws Exception {
    // The per-session topic cap cannot bound the subscribe path: completeSubscribe RELEASES the
    // reserved slot on a deny, so a subscribe -> deny -> subscribe cycle never reaches the cap.
    // Each turn of that cycle submits an authorization probe to the auth executor, so without a
    // bucket an authenticated client could drive the executor's queue to rejection at will — and
    // with it the saturation WARN. The subscribe bucket is what bounds the probe-submission rate.
    when(authorizer.authorize(any(), any(), any(), any()))
        .thenReturn(LiveSyncSubscriptionAuthorizer.Decision.DENY);
    String topic = operationTopic();
    FakeSession bob = openMultiplexedSession(oidcUser("user-2", "Bob"));
    bob.sent.clear();

    int emitted = LiveSyncWebSocketHandler.SUBSCRIBE_BURST + 20;
    for (int i = 0; i < emitted; i++) {
      subscribe(bob, topic);
    }

    // The clock is frozen (see nanoClock), so the bucket never refills mid-loop: exactly the burst
    // reaches the authorizer, and every excess frame is dropped as throttled.
    verify(authorizer, times(LiveSyncWebSocketHandler.SUBSCRIBE_BURST))
        .authorize(any(), any(), any(), any());
    assertThat(dropCounter(MetricNames.DROPPED_THROTTLED, "operation"))
        .isEqualTo(emitted - LiveSyncWebSocketHandler.SUBSCRIBE_BURST);
    // A throttled subscribe is answered with NOTHING — never a `denied` frame, which the client
    // treats as terminal for the room and would turn a transient burst into a permanently dead tab.
    assertThat(bob.sent).hasSize(LiveSyncWebSocketHandler.SUBSCRIBE_BURST);
  }

  @Test
  void subscribeThrottle_admitsAFullTopicCapWorthOfSubscribes() throws Exception {
    FakeSession bob = openMultiplexedSession(oidcUser("user-2", "Bob"));
    bob.sent.clear();

    // A page may legitimately hold MAX_TOPICS_PER_SESSION (16) rooms and subscribes to all of them
    // the instant its socket opens; the burst sits above that, so the throttle never bites the
    // feature it protects. (A reconnect re-subscribes on a fresh socket with a fresh, full bucket.)
    for (int i = 0; i < 16; i++) {
      subscribe(bob, operationTopic());
    }

    assertThat(bob.sent).hasSize(16);
    assertThat(lastBroadcast(bob).get("type").asString()).isEqualTo("subscribed");
    assertThat(dropCounter(MetricNames.DROPPED_THROTTLED, "operation")).isZero();
  }

  // -- Deny reason on the wire (retryable vs terminal) ------------------------------------------

  @Test
  void multiplexedSubscribe_indeterminateDeny_isRetryableOnTheWire() throws Exception {
    when(authorizer.authorize(any(), any(), any(), any()))
        .thenReturn(LiveSyncSubscriptionAuthorizer.Decision.DENY_INDETERMINATE);
    FakeSession bob = openMultiplexedSession(oidcUser("user-2", "Bob"));
    subscribe(bob, missionTopic());

    // Both deny flavours used to be the same opaque `denied` frame, so a 30-second backend blip
    // stripped live sync from that tab for good. The reason lets krt-live-sync.js retry this one
    // exactly once on its next reconnect.
    JsonNode frame = lastBroadcast(bob);
    assertThat(frame.get("type").asString()).isEqualTo("denied");
    assertThat(frame.get("reason").asString()).isEqualTo(MetricNames.SUBSCRIBE_DENY_INDETERMINATE);
  }

  @Test
  void multiplexedSubscribe_explicitDeny_carriesTheTerminalReasonOnTheWire() throws Exception {
    when(authorizer.authorize(any(), any(), any(), any()))
        .thenReturn(LiveSyncSubscriptionAuthorizer.Decision.DENY);
    FakeSession bob = openMultiplexedSession(oidcUser("user-2", "Bob"));
    subscribe(bob, operationTopic());

    // A permission verdict must NOT read as retryable — retrying it would just re-deny.
    JsonNode frame = lastBroadcast(bob);
    assertThat(frame.get("type").asString()).isEqualTo("denied");
    assertThat(frame.get("reason").asString()).isEqualTo(MetricNames.SUBSCRIBE_DENY_AUTHZ);
  }

  @Test
  void multiplexedSubscribe_preVerdictDenies_carryNoReasonAndAreTerminal() throws Exception {
    FakeSession bob = openMultiplexedSession(oidcUser("user-2", "Bob"));

    // Refusals decided before any authorization verdict exists: an unparseable topic and the
    // per-session topic cap. Neither is worth retrying, and the client's terminal default is
    // exactly "no reason field", so both must omit it.
    handler.handleTextMessage(
        bob, new TextMessage("{\"type\":\"subscribe\",\"topic\":\"bogus:not-a-thing\"}"));
    assertThat(lastBroadcast(bob).has("reason")).isFalse();

    for (int i = 0; i < 16; i++) {
      subscribe(bob, operationTopic());
    }
    bob.sent.clear();
    subscribe(bob, operationTopic()); // the 17th exceeds MAX_TOPICS_PER_SESSION

    JsonNode capped = lastBroadcast(bob);
    assertThat(capped.get("type").asString()).isEqualTo("denied");
    assertThat(capped.has("reason")).isFalse();
  }

  @Test
  void multiplexedSubscribe_presenceClassExecutorSaturated_deniedFrameIsRetryable()
      throws Exception {
    // Saturation is the archetypal indeterminate outcome: nothing about the caller's permissions
    // was learned. On a presence class it fails closed, so the refusal the client sees must carry
    // the retryable reason rather than look like a permission verdict.
    SimpleMeterRegistry reg = new SimpleMeterRegistry();
    LiveSyncPresenceService svc = new LiveSyncPresenceService(reg);
    LiveSyncWebSocketHandler saturated =
        new LiveSyncWebSocketHandler(
            svc,
            fanout,
            objectMapper,
            reg,
            authorizer,
            runnable -> {
              throw new RejectedExecutionException("auth executor full");
            });

    FakeSession bob = multiplexedSession(oidcUser("user-2", "Bob"));
    saturated.afterConnectionEstablished(bob);
    saturated.handleTextMessage(bob, subscribeFrame(missionTopic()));

    JsonNode frame = lastBroadcast(bob);
    assertThat(frame.get("type").asString()).isEqualTo("denied");
    assertThat(frame.get("reason").asString()).isEqualTo(MetricNames.SUBSCRIBE_DENY_INDETERMINATE);
  }

  // ── helpers ────────────────────────────────────────────────────────────────────────────────

  private static String missionTopic() {
    return "mission:" + UUID.randomUUID();
  }

  private static String operationTopic() {
    return "operation:" + UUID.randomUUID();
  }

  private static TextMessage changedFrame(String topic, String... sections) {
    StringBuilder json = new StringBuilder("{\"type\":\"changed\",\"topic\":\"").append(topic);
    json.append("\",\"sections\":[");
    for (int i = 0; i < sections.length; i++) {
      if (i > 0) {
        json.append(',');
      }
      json.append('"').append(sections[i]).append('"');
    }
    json.append("]}");
    return new TextMessage(json.toString());
  }

  private static TextMessage presenceFrame(String type, String topic, String sectionKey) {
    return new TextMessage(
        "{\"type\":\""
            + type
            + "\",\"topic\":\""
            + topic
            + "\",\"sectionKey\":\""
            + sectionKey
            + "\"}");
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
   * Opens a multiplexed {@code /ws/sync} socket and subscribes it to {@code topic}, so it joins
   * that room and receives its relays — the multiplexed equivalent of the old per-resource connect
   * that auto-joined a single implicit room.
   *
   * @param topic the canonical topic to subscribe to
   * @param user the socket owner
   * @return the established, subscribed session
   */
  private FakeSession openSubscribed(String topic, OidcUser user) throws Exception {
    FakeSession session = openMultiplexedSession(user);
    subscribe(session, topic);
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

  /**
   * Reads the subscribe counter for one exact {@code outcome} / {@code topic_class} / {@code
   * reason} triple, so a test can prove the deny series is split rather than merely present.
   *
   * @param outcome the {@code outcome} tag value
   * @param topicClass the {@code topic_class} tag value
   * @param reason the {@code reason} tag value
   * @return the counter's value, or {@code 0.0} when that exact series was never registered
   */
  private double subscribeCounter(String outcome, String topicClass, String reason) {
    var counter =
        registry
            .find(MetricNames.LIVESYNC_SUBSCRIBE)
            .tag(MetricNames.TAG_OUTCOME, outcome)
            .tag(MetricNames.TAG_TOPIC_CLASS, topicClass)
            .tag(MetricNames.TAG_REASON, reason)
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

  private double peerRoomsGauge(String topicClass) {
    return registry
        .get(MetricNames.LIVESYNC_PEER_ROOMS)
        .tag(MetricNames.TAG_TOPIC_CLASS, topicClass)
        .gauge()
        .value();
  }

  private double subscriptionsGauge(String topicClass) {
    return registry
        .get(MetricNames.LIVESYNC_SUBSCRIPTIONS)
        .tag(MetricNames.TAG_TOPIC_CLASS, topicClass)
        .gauge()
        .value();
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

  private double socketRejectedCounter(String reason) {
    var counter =
        registry
            .find(MetricNames.LIVESYNC_SOCKET_REJECTED)
            .tag(MetricNames.TAG_REASON, reason)
            .counter();
    return counter == null ? 0.0 : counter.count();
  }

  private double invalidTopicCounter() {
    var counter = registry.find(MetricNames.LIVESYNC_INVALID_TOPIC).counter();
    return counter == null ? 0.0 : counter.count();
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
    private final List<String> presenceTopics = new ArrayList<>();
    private final List<Map<String, List<LiveSyncPresenceService.PresenceEditor>>>
        presenceSnapshots = new ArrayList<>();

    @Override
    public void publish(String canonicalTopic, List<String> sections) {
      publishedTopics.add(canonicalTopic);
      publishedSections.add(List.copyOf(sections));
    }

    @Override
    public void publishPresence(
        String canonicalTopic, Map<String, List<LiveSyncPresenceService.PresenceEditor>> sections) {
      presenceTopics.add(canonicalTopic);
      presenceSnapshots.add(Map.copyOf(sections));
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
