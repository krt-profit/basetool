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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.frontend.service.LiveSyncPresenceService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Unit tests for {@link RedisLiveSyncFanout} using a mocked {@link StringRedisTemplate} and
 * handler, so the publish/consume logic, own-origin skip and error counting are verified
 * deterministically without a Redis container (the end-to-end round trip is covered by {@code
 * RedisLiveSyncFanoutIntegrationTest}).
 *
 * <p>Covers both channels: the {@code changed} relay (ADR-0094) and the editor-presence gossip
 * (ADR-0126), including that a message is routed by the channel it arrived on and that the two
 * streams keep separate publish / consume / error series.
 */
class RedisLiveSyncFanoutTest {

  private static final String CHANNEL = "basetool:livesync:changed";
  private static final String PRESENCE_CHANNEL = "basetool:livesync:presence";
  private static final String SELF = "instance-A";

  private StringRedisTemplate redisTemplate;
  private LiveSyncWebSocketHandler handler;
  private SimpleMeterRegistry registry;
  private RedisLiveSyncFanout fanout;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    redisTemplate = mock(StringRedisTemplate.class);
    handler = mock(LiveSyncWebSocketHandler.class);
    registry = new SimpleMeterRegistry();
    ObjectProvider<LiveSyncWebSocketHandler> handlerProvider = mock(ObjectProvider.class);
    when(handlerProvider.getObject()).thenReturn(handler);
    fanout =
        new RedisLiveSyncFanout(
            redisTemplate, handlerProvider, registry, CHANNEL, PRESENCE_CHANNEL, SELF);
  }

  @Test
  void publish_serialisesTopicSectionsAndOrigin_andCountsPerTopicClass() {
    String topic = "mission:5f1d2c3b-0000-0000-0000-000000000001";

    fanout.publish(topic, List.of("crew", "finance"));

    ArgumentCaptor<String> payload = ArgumentCaptor.captor();
    verify(redisTemplate).convertAndSend(eq(CHANNEL), payload.capture());
    assertThat(payload.getValue())
        .contains("\"topic\":\"" + topic + "\"")
        .contains("\"origin\":\"" + SELF + "\"")
        .contains("crew")
        .contains("finance");
    assertThat(publishedCount("mission")).isEqualTo(1.0);
  }

  @Test
  void publish_swallowsRedisFailure_andCountsAPublishError() {
    doThrow(new IllegalStateException("redis down"))
        .when(redisTemplate)
        .convertAndSend(anyString(), anyString());

    // Must not propagate — the local relay already delivered to this instance's viewers.
    fanout.publish("mission:5f1d2c3b-0000-0000-0000-000000000001", List.of("crew"));

    assertThat(errorCount(MetricNames.OP_PUBLISH)).isEqualTo(1.0);
    assertThat(publishedCount("mission")).isZero();
  }

  @Test
  void onMessage_skipsOwnOriginPublications() {
    String body =
        "{\"v\":1,\"topic\":\"mission:5f1d2c3b-0000-0000-0000-000000000001\","
            + "\"origin\":\""
            + SELF
            + "\",\"sections\":[\"crew\"]}";

    fanout.onMessage(
        new DefaultMessage(CHANNEL.getBytes(StandardCharsets.UTF_8), bytes(body)), null);

    // Our own publication looped back — the local relay already delivered it.
    verify(handler, never()).deliverFromFanout(anyString(), anyList());
    assertThat(consumedCount("mission")).isZero();
  }

  @Test
  void onMessage_relaysAPeerReplicasSignal_andCountsPerTopicClass() {
    String topic = "mission:5f1d2c3b-0000-0000-0000-000000000001";
    String body =
        "{\"v\":1,\"topic\":\""
            + topic
            + "\",\"origin\":\"instance-B\",\"sections\":[\"crew\",\"mgmt\"]}";

    fanout.onMessage(
        new DefaultMessage(CHANNEL.getBytes(StandardCharsets.UTF_8), bytes(body)), null);

    verify(handler).deliverFromFanout(topic, List.of("crew", "mgmt"));
    assertThat(consumedCount("mission")).isEqualTo(1.0);
  }

  @Test
  void onMessage_ignoresMalformedPayload_andCountsAConsumeError() {
    fanout.onMessage(
        new DefaultMessage(CHANNEL.getBytes(StandardCharsets.UTF_8), bytes("{not json")), null);

    verify(handler, never()).deliverFromFanout(anyString(), anyList());
    assertThat(errorCount(MetricNames.OP_CONSUME)).isEqualTo(1.0);
  }

  @Test
  void publishPresence_serialisesTheSnapshotOnTheSeparatePresenceChannel() {
    String topic = "mission:5f1d2c3b-0000-0000-0000-000000000001";

    fanout.publishPresence(
        topic,
        Map.of("crew", List.of(new LiveSyncPresenceService.PresenceEditor("user-1", "Alice"))));

    ArgumentCaptor<String> payload = ArgumentCaptor.captor();
    verify(redisTemplate).convertAndSend(eq(PRESENCE_CHANNEL), payload.capture());
    assertThat(payload.getValue())
        .contains("\"topic\":\"" + topic + "\"")
        .contains("\"origin\":\"" + SELF + "\"")
        .contains("\"userId\":\"user-1\"")
        .contains("\"displayName\":\"Alice\"");
    assertThat(presencePublishedCount("mission")).isEqualTo(1.0);
    // The changed relay must stay untouched — its dashboard panel and alert key off that stream
    // alone.
    assertThat(publishedCount("mission")).isZero();
  }

  @Test
  void publishPresence_swallowsRedisFailure_andCountsADistinctPresencePublishError() {
    doThrow(new IllegalStateException("redis down"))
        .when(redisTemplate)
        .convertAndSend(anyString(), anyString());

    fanout.publishPresence(
        "mission:5f1d2c3b-0000-0000-0000-000000000001",
        Map.of("crew", List.of(new LiveSyncPresenceService.PresenceEditor("user-1", "Alice"))));

    // A distinct op value keeps LiveSyncRedisFanoutBroken firing on the changed relay only: a lost
    // gossip costs a cosmetic dot, a lost changed frame costs correctness.
    assertThat(errorCount(MetricNames.OP_PRESENCE_PUBLISH)).isEqualTo(1.0);
    assertThat(errorCount(MetricNames.OP_PUBLISH)).isZero();
  }

  @Test
  @SuppressWarnings("unchecked")
  void onMessage_onThePresenceChannel_mirrorsAPeerReplicasSnapshot() {
    String topic = "mission:5f1d2c3b-0000-0000-0000-000000000001";
    String body =
        "{\"v\":1,\"topic\":\""
            + topic
            + "\",\"origin\":\"instance-B\",\"sections\":{\"crew\":"
            + "[{\"userId\":\"user-2\",\"displayName\":\"Bob\"}]}}";

    fanout.onMessage(
        new DefaultMessage(PRESENCE_CHANNEL.getBytes(StandardCharsets.UTF_8), bytes(body)), null);

    ArgumentCaptor<Map<String, List<LiveSyncPresenceService.PresenceEditor>>> sections =
        ArgumentCaptor.captor();
    verify(handler).deliverPresenceFromFanout(eq(topic), eq("instance-B"), sections.capture());
    assertThat(sections.getValue())
        .containsExactly(
            Map.entry(
                "crew", List.of(new LiveSyncPresenceService.PresenceEditor("user-2", "Bob"))));
    assertThat(presenceConsumedCount("mission")).isEqualTo(1.0);
    // A presence payload must never be mistaken for a changed frame: the channel decides.
    verify(handler, never()).deliverFromFanout(anyString(), anyList());
  }

  @Test
  void onMessage_onThePresenceChannel_forwardsAnEmptySnapshot() {
    String topic = "mission:5f1d2c3b-0000-0000-0000-000000000001";
    String body = "{\"v\":1,\"topic\":\"" + topic + "\",\"origin\":\"instance-B\",\"sections\":{}}";

    fanout.onMessage(
        new DefaultMessage(PRESENCE_CHANNEL.getBytes(StandardCharsets.UTF_8), bytes(body)), null);

    // "Nobody is editing here any more" is a message, not a no-op: it drops the peer's partition
    // immediately instead of leaving stale dots up until the partition TTL.
    verify(handler).deliverPresenceFromFanout(topic, "instance-B", Map.of());
  }

  @Test
  void onMessage_onThePresenceChannel_skipsOwnOriginGossip() {
    String body =
        "{\"v\":1,\"topic\":\"mission:5f1d2c3b-0000-0000-0000-000000000001\",\"origin\":\""
            + SELF
            + "\",\"sections\":{\"crew\":[{\"userId\":\"user-1\",\"displayName\":\"Alice\"}]}}";

    fanout.onMessage(
        new DefaultMessage(PRESENCE_CHANNEL.getBytes(StandardCharsets.UTF_8), bytes(body)), null);

    verify(handler, never()).deliverPresenceFromFanout(anyString(), anyString(), anyMap());
    assertThat(presenceConsumedCount("mission")).isZero();
  }

  @Test
  void onMessage_onThePresenceChannel_ignoresMalformedPayload_andCountsAConsumeError() {
    fanout.onMessage(
        new DefaultMessage(PRESENCE_CHANNEL.getBytes(StandardCharsets.UTF_8), bytes("{not json")),
        null);

    verify(handler, never()).deliverPresenceFromFanout(anyString(), anyString(), anyMap());
    assertThat(errorCount(MetricNames.OP_PRESENCE_CONSUME)).isEqualTo(1.0);
    assertThat(errorCount(MetricNames.OP_CONSUME)).isZero();
  }

  private static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  private double presencePublishedCount(String topicClass) {
    var counter =
        registry
            .find(MetricNames.LIVESYNC_PRESENCE_PUBLISHED)
            .tag(MetricNames.TAG_TOPIC_CLASS, topicClass)
            .counter();
    return counter == null ? 0.0 : counter.count();
  }

  private double presenceConsumedCount(String topicClass) {
    var counter =
        registry
            .find(MetricNames.LIVESYNC_PRESENCE_CONSUMED)
            .tag(MetricNames.TAG_TOPIC_CLASS, topicClass)
            .counter();
    return counter == null ? 0.0 : counter.count();
  }

  private double publishedCount(String topicClass) {
    var counter =
        registry
            .find(MetricNames.LIVESYNC_REDIS_PUBLISHED)
            .tag(MetricNames.TAG_TOPIC_CLASS, topicClass)
            .counter();
    return counter == null ? 0.0 : counter.count();
  }

  private double consumedCount(String topicClass) {
    var counter =
        registry
            .find(MetricNames.LIVESYNC_REDIS_CONSUMED)
            .tag(MetricNames.TAG_TOPIC_CLASS, topicClass)
            .counter();
    return counter == null ? 0.0 : counter.count();
  }

  private double errorCount(String op) {
    var counter =
        registry.find(MetricNames.LIVESYNC_REDIS_ERRORS).tag(MetricNames.TAG_OP, op).counter();
    return counter == null ? 0.0 : counter.count();
  }
}
