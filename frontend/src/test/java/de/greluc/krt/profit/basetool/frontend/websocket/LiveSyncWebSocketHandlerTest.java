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
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.security.Principal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
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
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests {@link LiveSyncWebSocketHandler}, the multiplexed {@code /ws/sync} relay, through a
 * recording {@link FakeSession}: wire format, room membership, principal resolution and broadcast.
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
    assertThat(fanout.presenceTopics).isEmpty();
  }

  @Test
  void deliverPresenceFromFanout_ignoresANonPresenceTopicClass() {
    String topic = "operation:5f1d2c3b-0000-0000-0000-000000000009";

    handler.deliverPresenceFromFanout(
        topic,
        "instance-B",
        Map.of("overview", List.of(new LiveSyncPresenceService.PresenceEditor("user-2", "Bob"))));

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

    assertThat(service.remotePartitionCount()).isZero();
  }

  @Test
  void reaperTick_reGossipsEveryTrackedPresenceTopic() throws Exception {
    String topic = missionTopic();
    FakeSession session = openSubscribed(topic, oidcUser("user-1", "Alice"));
    handler.handleTextMessage(session, presenceFrame("focus", topic, "crew"));
    fanout.presenceTopics.clear();

    handler.tickReaper();

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

    assertThat(service.get(topic, "crew", "user-1")).isNull();
    assertThat(service.get(topic, "steps", "user-2")).isNotNull();
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
    assertThat(sectionsOf(relayed)).containsExactly("crew", "mgmt");
  }

  @Test
  void changedSignal_relaysEverySectionOfTheMissionSeamMap() throws Exception {
    String topic = missionTopic();
    FakeSession bob = openSubscribed(topic, oidcUser("user-2", "Bob"));
    bob.sent.clear();
    FakeSession alice = openMultiplexedSession(oidcUser("user-1", "Alice"));

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

    int emitted = LiveSyncWebSocketHandler.CHANGED_BURST + 40;
    for (int i = 0; i < emitted; i++) {
      handler.handleTextMessage(alice, changedFrame(topic, "crew"));
    }

    assertThat(bob.sent.size()).isEqualTo(LiveSyncWebSocketHandler.CHANGED_BURST);
  }

  @Test
  void changedSignal_isPublishedToTheFanoutAfterLocalRelay() throws Exception {
    String topic = missionTopic();
    openSubscribed(topic, oidcUser("user-2", "Bob"));
    FakeSession alice = openMultiplexedSession(oidcUser("user-1", "Alice"));

    handler.handleTextMessage(alice, changedFrame(topic, "crew", "finance"));

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

    handler.deliverFromFanout(topic, List.of("crew"));

    assertThat(sectionsOf(lastBroadcast(alice))).containsExactly("crew");
    assertThat(sectionsOf(lastBroadcast(bob))).containsExactly("crew");
    assertThat(fanout.publishedTopics).isEmpty();
  }

  @Test
  void broadcasts_countSnapshotAndChangedRelayFrames() throws Exception {
    String topic = missionTopic();
    openSubscribed(topic, oidcUser("user-2", "Bob"));
    FakeSession alice = openMultiplexedSession(oidcUser("user-1", "Alice"));

    handler.handleTextMessage(alice, changedFrame(topic, "crew"));

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

    assertThat(dropCounter(MetricNames.DROPPED_THROTTLED)).isGreaterThan(0.0);
  }

  @Test
  void sendFailureToBrokenPeer_isCountedAsDroppedSendFailed() throws Exception {
    String topic = missionTopic();
    FakeSession bob = openSubscribed(topic, oidcUser("user-2", "Bob"));
    bob.failSend = true;
    FakeSession alice = openMultiplexedSession(oidcUser("user-1", "Alice"));

    handler.handleTextMessage(alice, changedFrame(topic, "crew"));

    assertThat(dropCounter(MetricNames.DROPPED_SEND_FAILED)).isGreaterThanOrEqualTo(1.0);
    assertThat(frameCounter(MetricNames.FRAME_CHANGED)).isZero();
  }

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
    assertThat(invalidTopicCounter()).isEqualTo(1.0);
  }

  @Test
  void multiplexedSubscribe_reSubscribe_isIdempotent() throws Exception {
    String topic = operationTopic();
    FakeSession bob = openMultiplexedSession(oidcUser("user-2", "Bob"));
    subscribe(bob, topic);
    subscribe(bob, topic);

    assertThat(lastBroadcast(bob).get("type").asString()).isEqualTo("subscribed");
    FakeSession alice = openMultiplexedSession(oidcUser("user-1", "Alice"));
    bob.sent.clear();
    handler.handleTextMessage(alice, changedFrame(topic, "overview"));
    assertThat(bob.sent).hasSize(1);
  }

  @Test
  void multiplexedSubscribe_beyondTopicCap_isDeniedAndCounted() throws Exception {
    FakeSession bob = openMultiplexedSession(oidcUser("user-2", "Bob"));
    for (int i = 0; i < 16; i++) {
      subscribe(bob, operationTopic());
    }
    bob.sent.clear();
    subscribe(bob, operationTopic());

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
    when(authorizer.authorize(any(), any(), any(), any()))
        .thenReturn(LiveSyncSubscriptionAuthorizer.Decision.DENY);
    String topic = operationTopic();
    FakeSession bob = openMultiplexedSession(oidcUser("user-2", "Bob"));
    subscribe(bob, topic);
    assertThat(lastBroadcast(bob).get("type").asString()).isEqualTo("denied");
    bob.sent.clear();

    FakeSession alice = openMultiplexedSession(oidcUser("user-1", "Alice"));
    handler.handleTextMessage(alice, changedFrame(topic, "overview"));

    assertThat(bob.sent).isEmpty();
  }

  @Test
  void multiplexedSubscribe_authorizerThrows_failsOpen() throws Exception {
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

    assertThat(lastBroadcast(bob).get("type").asString()).isEqualTo("subscribed");
    bob.sent.clear();
    FakeSession alice = multiplexedSession(oidcUser("user-1", "Alice"));
    h.afterConnectionEstablished(alice);
    h.handleTextMessage(alice, changedFrame(topic, "overview"));
    assertThat(sectionsOf(lastBroadcast(bob))).containsExactly("overview");
  }

  @Test
  void multiplexedSubscribe_presenceClassAuthorizerThrows_failsClosed() throws Exception {
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
    List<Runnable> deferred = new ArrayList<>();
    SimpleMeterRegistry reg = new SimpleMeterRegistry();
    LiveSyncPresenceService svc = new LiveSyncPresenceService(reg);
    LiveSyncWebSocketHandler h =
        new LiveSyncWebSocketHandler(svc, fanout, objectMapper, reg, authorizer, deferred::add);

    String topic = operationTopic();
    FakeSession bob = multiplexedSession(oidcUser("user-2", "Bob"));
    h.afterConnectionEstablished(bob);
    h.handleTextMessage(bob, subscribeFrame(topic));
    assertThat(deferred).hasSize(1);

    bob.open = false;
    h.afterConnectionClosed(bob, CloseStatus.NORMAL);
    bob.sent.clear();

    deferred.get(0).run();

    assertThat(bob.sent).isEmpty();
    assertThat(presenceGauge(reg)).isZero();
  }

  @Test
  void multiplexedSubscribe_socketClosesBetweenJoinAndAck_leavesRoom() throws Exception {
    List<Runnable> deferred = new ArrayList<>();
    SimpleMeterRegistry reg = new SimpleMeterRegistry();
    LiveSyncPresenceService svc = new LiveSyncPresenceService(reg);
    LiveSyncWebSocketHandler h =
        new LiveSyncWebSocketHandler(svc, fanout, objectMapper, reg, authorizer, deferred::add);

    String topic = operationTopic();
    FakeSession bob = multiplexedSession(oidcUser("user-2", "Bob"));
    h.afterConnectionEstablished(bob);
    h.handleTextMessage(bob, subscribeFrame(topic));
    bob.flipOpenAfter = 1;

    deferred.get(0).run();

    assertThat(presenceGauge(reg)).isZero();
    assertThat(bob.sent).isEmpty();
  }

  @Test
  void peerRoomsGauge_countsOnlyRoomsThatActuallyHoldPeers() throws Exception {
    String room = operationTopic();
    FakeSession alice = openMultiplexedSession(oidcUser("user-1", "Alice"));
    subscribe(alice, room);

    assertThat(subscriptionsGauge("operation")).isEqualTo(1.0);
    assertThat(peerRoomsGauge("operation")).isZero();

    FakeSession bob = openMultiplexedSession(oidcUser("user-2", "Bob"));
    subscribe(bob, room);

    assertThat(peerRoomsGauge("operation")).isEqualTo(1.0);

    bob.open = false;
    handler.afterConnectionClosed(bob, CloseStatus.NORMAL);
    assertThat(peerRoomsGauge("operation")).isZero();
  }

  @Test
  void peerRoomsGauge_separatesCoPresenceFromTwoLoneViewers() throws Exception {
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
    handler.handleTextMessage(
        alice,
        new TextMessage(
            "{\"type\":\"focus\",\"topic\":\"" + topic + "\",\"sectionKey\":\"crew\"}"));

    assertThat(service.get(topic, "crew", "user-1")).isNull();
  }

  @Test
  void publishFromServer_relaysToLocalRoom_andFansOut() throws Exception {
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

  /**
   * Verifies that a socket marked by the consent gate is closed with code {@code 4003} and the
   * consent page as reason.
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

  /** Verifies that a consent-gated refusal consumes no per-user socket slot, even past the cap. */
  @Test
  void consentGate_refusalTakesNoUserSocketSlot() throws Exception {
    OidcUser bob = oidcUser("user-2", "Bob");
    for (int i = 0; i < LiveSyncWebSocketHandler.MAX_SOCKETS_PER_USER + 1; i++) {
      FakeSession gated = multiplexedSession(bob);
      gated.attributes.put(LiveSyncWebSocketHandler.ATTR_TERMS_GATE, "/terms/accept");
      handler.afterConnectionEstablished(gated);
    }

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

  @Test
  void perUserSocketCap_refusesBeyondTheCap_andCounts() throws Exception {
    OidcUser bob = oidcUser("user-2", "Bob");
    for (int i = 0; i < LiveSyncWebSocketHandler.MAX_SOCKETS_PER_USER; i++) {
      assertThat(openMultiplexedSession(bob).closeStatus).isNull();
    }

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
    assertThat(openMultiplexedSession(bob).closeStatus)
        .isEqualTo(LiveSyncWebSocketHandler.SOCKET_CAP_EXCEEDED);

    FakeSession first = sockets.get(0);
    first.open = false;
    handler.afterConnectionClosed(first, CloseStatus.NORMAL);

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

    assertThat(openMultiplexedSession(oidcUser("user-1", "Alice")).closeStatus).isNull();
  }

  @Test
  void perTopicThrottle_boundsAggregateRelayAcrossPublishers() throws Exception {
    String topic = operationTopic();
    FakeSession subscriber = openMultiplexedSession(oidcUser("sub", "Sub"));
    subscribe(subscriber, topic);
    subscriber.sent.clear();

    int perPublisher = LiveSyncWebSocketHandler.CHANGED_BURST;
    int publishers = (LiveSyncWebSocketHandler.TOPIC_CHANGED_BURST / perPublisher) + 2;
    for (int p = 0; p < publishers; p++) {
      FakeSession pub = openMultiplexedSession(oidcUser("pub-" + p, "P" + p));
      for (int i = 0; i < perPublisher; i++) {
        handler.handleTextMessage(pub, changedFrame(topic, "overview"));
      }
    }
    int emitted = publishers * perPublisher;

    assertThat(emitted).isGreaterThan(LiveSyncWebSocketHandler.TOPIC_CHANGED_BURST);
    assertThat(subscriber.sent.size()).isEqualTo(LiveSyncWebSocketHandler.TOPIC_CHANGED_BURST);
    assertThat(dropCounter(MetricNames.DROPPED_TOPIC_THROTTLED, "operation")).isGreaterThan(0.0);
  }

  @Test
  void perTopicBuckets_areReapedWhenIdle() throws Exception {
    String topic = operationTopic();
    FakeSession pub = openMultiplexedSession(oidcUser("user-1", "Alice"));
    handler.handleTextMessage(pub, changedFrame(topic, "overview"));
    assertThat(handler.topicBucketCount()).isEqualTo(1);

    handler.reapIdleTopicBuckets(
        nanoClock.get() + LiveSyncWebSocketHandler.TOPIC_BUCKET_IDLE_REAP_NANOS * 3);
    assertThat(handler.topicBucketCount()).isZero();
  }

  @Test
  void presenceFrames_areRateLimitedPerSession() throws Exception {
    String topic = missionTopic();
    FakeSession alice = openSubscribed(topic, oidcUser("user-1", "Alice"));
    openSubscribed(topic, oidcUser("user-2", "Bob"));

    int emitted = LiveSyncWebSocketHandler.PRESENCE_BURST + 20;
    for (int i = 0; i < emitted; i++) {
      handler.handleTextMessage(alice, presenceFrame("focus", topic, "sec-" + i));
    }

    assertThat(dropCounter(MetricNames.DROPPED_THROTTLED)).isGreaterThan(0.0);
  }

  @Test
  void presenceFrame_withOverLongSectionKey_isDropped() throws Exception {
    String topic = missionTopic();
    FakeSession alice = openSubscribed(topic, oidcUser("user-1", "Alice"));

    String longKey = "x".repeat(65);
    handler.handleTextMessage(alice, presenceFrame("focus", topic, longKey));

    assertThat(service.get(topic, longKey, "user-1")).isNull();
  }

  @Test
  void multiplexedChanged_materialboardRoom_dropsSectionsOutsideWhitelist() throws Exception {
    FakeSession subscriber = openSubscribed("materialboard", oidcUser("user-2", "Bob"));
    subscriber.sent.clear();
    FakeSession publisher = openMultiplexedSession(oidcUser("user-1", "Alice"));

    handler.handleTextMessage(publisher, changedFrame("materialboard", "secret"));

    assertThat(subscriber.sent).isEmpty();
  }

  @Test
  void multiplexedChanged_materialboardRoom_relaysBoardSection() throws Exception {
    FakeSession subscriber = openMultiplexedSession(oidcUser("user-2", "Bob"));
    subscribe(subscriber, "materialboard");
    subscriber.sent.clear();
    FakeSession publisher = openMultiplexedSession(oidcUser("user-1", "Alice"));

    handler.handleTextMessage(publisher, changedFrame("materialboard", "board"));

    assertThat(sectionsOf(lastBroadcast(subscriber))).containsExactly("board");
  }

  @Test
  void tickReaper_broadcastsFreshSnapshotToAffectedRoom() throws Exception {
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

    assertThat(session.sent).hasSize(1);
    JsonNode frame = objectMapper.readTree(((TextMessage) session.sent.get(0)).getPayload());
    assertThat(frame.get("type").asString()).isEqualTo("presence");
  }

  @Test
  void establishSocket_withoutPrincipal_isRefused() throws Exception {
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

  @Test
  void changedFrame_withKeysOutsideTheWhitelist_countsOneSectionFilteredDrop() throws Exception {
    String topic = missionTopic();
    FakeSession alice = openMultiplexedSession(oidcUser("user-1", "Alice"));

    handler.handleTextMessage(
        alice,
        new TextMessage(
            "{\"type\":\"changed\",\"topic\":\""
                + topic
                + "\",\"sections\":[\"crew\",\"bogus\",\"alsoBogus\"]}"));

    assertThat(dropCounter(MetricNames.DROPPED_SECTION_FILTERED)).isEqualTo(1.0);
  }

  @Test
  void changedFrame_withOnlyWhitelistedKeys_countsNoSectionFilteredDrop() throws Exception {
    String topic = missionTopic();
    FakeSession alice = openMultiplexedSession(oidcUser("user-1", "Alice"));

    handler.handleTextMessage(alice, changedFrame(topic, "crew", "crew", "mgmt"));

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

    assertThat(logAppender.list).hasSize(1);
    assertThat(logAppender.list.get(0).getLevel()).isEqualTo(Level.DEBUG);
    assertThat(fanout.publishedTopics).isEmpty();
  }

  @Test
  void publishFromServer_withKeysOutsideTheWhitelist_countsTheSectionFilteredDrop() {
    handler.publishFromServer("orders", List.of("bogus"));

    assertThat(dropCounter(MetricNames.DROPPED_SECTION_FILTERED, "orders_queue")).isEqualTo(1.0);
  }

  @Test
  void deliverFromFanout_withKeysOutsideTheWhitelist_countsTheSectionFilteredDrop() {
    handler.deliverFromFanout("orders", List.of("bogus"));

    assertThat(dropCounter(MetricNames.DROPPED_SECTION_FILTERED, "orders_queue")).isEqualTo(1.0);
  }

  @Test
  void multiplexedSubscribe_explicitDeny_tagsTheAuthzReason_andStaysBelowWarn() throws Exception {
    when(authorizer.authorize(any(), any(), any(), any()))
        .thenReturn(LiveSyncSubscriptionAuthorizer.Decision.DENY);
    FakeSession bob = openMultiplexedSession(oidcUser("user-2", "Bob"));
    logAppender.list.clear();
    subscribe(bob, operationTopic());

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

    assertThat(logAppender.list.stream().filter(e -> e.getLevel() == Level.WARN).count())
        .isEqualTo(1L);
  }

  @Test
  void subscribeFrames_areRateLimitedPerSession() throws Exception {
    when(authorizer.authorize(any(), any(), any(), any()))
        .thenReturn(LiveSyncSubscriptionAuthorizer.Decision.DENY);
    String topic = operationTopic();
    FakeSession bob = openMultiplexedSession(oidcUser("user-2", "Bob"));
    bob.sent.clear();

    int emitted = LiveSyncWebSocketHandler.SUBSCRIBE_BURST + 20;
    for (int i = 0; i < emitted; i++) {
      subscribe(bob, topic);
    }

    verify(authorizer, times(LiveSyncWebSocketHandler.SUBSCRIBE_BURST))
        .authorize(any(), any(), any(), any());
    assertThat(dropCounter(MetricNames.DROPPED_THROTTLED, "operation"))
        .isEqualTo(emitted - LiveSyncWebSocketHandler.SUBSCRIBE_BURST);
    assertThat(bob.sent).hasSize(LiveSyncWebSocketHandler.SUBSCRIBE_BURST);
  }

  @Test
  void subscribeThrottle_admitsAFullTopicCapWorthOfSubscribes() throws Exception {
    FakeSession bob = openMultiplexedSession(oidcUser("user-2", "Bob"));
    bob.sent.clear();

    for (int i = 0; i < 16; i++) {
      subscribe(bob, operationTopic());
    }

    assertThat(bob.sent).hasSize(16);
    assertThat(lastBroadcast(bob).get("type").asString()).isEqualTo("subscribed");
    assertThat(dropCounter(MetricNames.DROPPED_THROTTLED, "operation")).isZero();
  }

  @Test
  void multiplexedSubscribe_indeterminateDeny_isRetryableOnTheWire() throws Exception {
    when(authorizer.authorize(any(), any(), any(), any()))
        .thenReturn(LiveSyncSubscriptionAuthorizer.Decision.DENY_INDETERMINATE);
    FakeSession bob = openMultiplexedSession(oidcUser("user-2", "Bob"));
    subscribe(bob, missionTopic());

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

    JsonNode frame = lastBroadcast(bob);
    assertThat(frame.get("type").asString()).isEqualTo("denied");
    assertThat(frame.get("reason").asString()).isEqualTo(MetricNames.SUBSCRIBE_DENY_AUTHZ);
  }

  @Test
  void multiplexedSubscribe_preVerdictDenies_carryNoReasonAndAreTerminal() throws Exception {
    FakeSession bob = openMultiplexedSession(oidcUser("user-2", "Bob"));

    handler.handleTextMessage(
        bob, new TextMessage("{\"type\":\"subscribe\",\"topic\":\"bogus:not-a-thing\"}"));
    assertThat(lastBroadcast(bob).has("reason")).isFalse();

    for (int i = 0; i < 16; i++) {
      subscribe(bob, operationTopic());
    }
    bob.sent.clear();
    subscribe(bob, operationTopic());

    JsonNode capped = lastBroadcast(bob);
    assertThat(capped.get("type").asString()).isEqualTo("denied");
    assertThat(capped.has("reason")).isFalse();
  }

  @Test
  void multiplexedSubscribe_presenceClassExecutorSaturated_deniedFrameIsRetryable()
      throws Exception {
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

  @Test
  void keepaliveSweep_pingsEverySocket_includingOneThatJoinedNoRoom() throws Exception {
    FakeSession subscribed = openSubscribed(missionTopic(), oidcUser("user-1", "Alice"));
    FakeSession roomless = openMultiplexedSession(oidcUser("user-2", "Bob"));
    subscribed.sent.clear();
    roomless.sent.clear();

    handler.tickKeepalive();

    assertThat(subscribed.sent).singleElement().isInstanceOf(PingMessage.class);
    assertThat(roomless.sent).singleElement().isInstanceOf(PingMessage.class);
  }

  @Test
  void keepaliveSweep_skipsAClosedSocket_andStopsPingingItAfterTheCloseCallback() throws Exception {
    FakeSession session = openSubscribed(missionTopic(), oidcUser("user-1", "Alice"));
    session.sent.clear();
    session.open = false;

    handler.tickKeepalive();
    assertThat(session.sent).isEmpty();

    session.open = true;
    handler.tickKeepalive();
    assertThat(session.sent).isEmpty();
  }

  @Test
  void keepaliveSweep_dropsABrokenSocket_andKeepsPingingTheRest() throws Exception {
    FakeSession broken = openSubscribed(missionTopic(), oidcUser("user-1", "Alice"));
    FakeSession healthy = openSubscribed(missionTopic(), oidcUser("user-2", "Bob"));
    broken.sent.clear();
    healthy.sent.clear();
    broken.failSend = true;

    handler.tickKeepalive();

    assertThat(broken.sent).isEmpty();
    assertThat(healthy.sent).singleElement().isInstanceOf(PingMessage.class);

    broken.failSend = false;
    handler.tickKeepalive();
    assertThat(broken.sent).isEmpty();
    assertThat(healthy.sent).hasSize(2);
  }

  @Test
  void closedSocket_isRemovedFromTheKeepaliveSweep() throws Exception {
    String topic = missionTopic();
    FakeSession session = openSubscribed(topic, oidcUser("user-1", "Alice"));

    handler.afterConnectionClosed(session, CloseStatus.NORMAL);
    session.sent.clear();
    handler.tickKeepalive();

    assertThat(session.sent).isEmpty();
  }

  @Test
  void socketLifetime_isRecordedWhenTheSocketCloses() throws Exception {
    FakeSession session = openSubscribed(missionTopic(), oidcUser("user-1", "Alice"));
    nanoClock.addAndGet(Duration.ofMinutes(7).toNanos());

    handler.afterConnectionClosed(session, CloseStatus.NORMAL);

    Timer timer = registry.find(MetricNames.LIVESYNC_SOCKET_LIFETIME).timer();
    assertThat(timer).isNotNull();
    assertThat(timer.count()).isEqualTo(1L);
    assertThat(timer.totalTime(TimeUnit.SECONDS)).isEqualTo(Duration.ofMinutes(7).toSeconds());
  }

  @Test
  void socketRefusedAtConnect_recordsNoLifetime() throws Exception {
    FakeSession session = multiplexedSession(oidcUser("user-1", "Alice"));
    session.principal = null;
    handler.afterConnectionEstablished(session);

    handler.afterConnectionClosed(session, CloseStatus.NOT_ACCEPTABLE);

    Timer timer = registry.find(MetricNames.LIVESYNC_SOCKET_LIFETIME).timer();
    assertThat(timer == null ? 0L : timer.count()).isZero();
  }

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
   * Opens a multiplexed {@code /ws/sync} socket and subscribes it to {@code topic}.
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
   * Builds, without establishing, a multiplexed {@code /ws/sync} {@link FakeSession} for a test
   * that establishes it on its own handler.
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
   * reason} triple.
   *
   * @param outcome the {@code outcome} tag value
   * @param topicClass the {@code topic_class} tag value
   * @param reason the {@code reason} tag value
   * @return the counter's value, or {@code 0.0} when that series was never registered
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
     * calls and {@code false} thereafter; {@code -1} reports the plain {@link #open} field.
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
    public void setTextMessageSizeLimit(int messageSizeLimit) {}

    @Override
    public int getTextMessageSizeLimit() {
      return 0;
    }

    @Override
    public void setBinaryMessageSizeLimit(int messageSizeLimit) {}

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

    @SuppressWarnings("unused")
    private void touchUnusedSymbols() {
      ByteBuffer.allocate(0);
    }
  }
}
