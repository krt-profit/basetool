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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.service.LiveSyncPresenceService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end integration test for {@link RedisLiveSyncFanout} against a real Redis in
 * Testcontainers (ADR-0094, ADR-0126): a {@code changed} signal and an editor-presence snapshot
 * published by one instance each reach a peer instance's handler on their own channel, while the
 * publisher skips its own looped-back message.
 */
@Testcontainers
class RedisLiveSyncFanoutIntegrationTest {

  private static final String CHANNEL = "basetool:livesync:test";
  private static final String PRESENCE_CHANNEL = "basetool:livesync:test:presence";

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  private LettuceConnectionFactory connectionFactory;
  private RedisMessageListenerContainer listenerContainer;

  @BeforeEach
  void setUp() {
    connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
    connectionFactory.afterPropertiesSet();
    connectionFactory.start();
    listenerContainer = new RedisMessageListenerContainer();
    listenerContainer.setConnectionFactory(connectionFactory);
    listenerContainer.afterPropertiesSet();
    listenerContainer.start();
  }

  @AfterEach
  void tearDown() {
    listenerContainer.stop();
    connectionFactory.destroy();
  }

  @Test
  void publishReachesAPeerInstanceButNotItsOwnOrigin() throws Exception {
    StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
    template.afterPropertiesSet();
    String topic = "mission:5f1d2c3b-0000-0000-0000-000000000042";

    // Peer instance B: its handler records what it is asked to relay.
    CountDownLatch peerDelivered = new CountDownLatch(1);
    List<String> peerTopics = new CopyOnWriteArrayList<>();
    List<List<String>> peerSections = new CopyOnWriteArrayList<>();
    RedisLiveSyncFanout instanceB =
        newFanout(template, "instance-B", peerTopics, peerSections, peerDelivered);

    // Origin instance A: its handler must NOT be called for its own publication.
    CountDownLatch ownDelivered = new CountDownLatch(1);
    RedisLiveSyncFanout instanceA =
        newFanout(
            template,
            "instance-A",
            new CopyOnWriteArrayList<>(),
            new CopyOnWriteArrayList<>(),
            ownDelivered);

    listenerContainer.addMessageListener(instanceB, new ChannelTopic(CHANNEL));
    listenerContainer.addMessageListener(instanceA, new ChannelTopic(CHANNEL));
    // Give the subscriptions a moment to register before publishing.
    Thread.sleep(300);

    instanceA.publish(topic, List.of("crew", "mgmt"));

    assertThat(peerDelivered.await(5, TimeUnit.SECONDS))
        .as("peer instance B relayed the signal")
        .isTrue();
    assertThat(peerTopics).containsExactly(topic);
    assertThat(peerSections).containsExactly(List.of("crew", "mgmt"));
    // The origin's own handler must never be invoked for its own publication (own-origin skip).
    assertThat(ownDelivered.await(1, TimeUnit.SECONDS))
        .as("origin instance A skipped its own message")
        .isFalse();
  }

  @Test
  void presenceGossipReachesAPeerInstanceOnItsOwnChannel() throws Exception {
    StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
    template.afterPropertiesSet();
    String topic = "mission:5f1d2c3b-0000-0000-0000-000000000043";

    CountDownLatch peerMirrored = new CountDownLatch(1);
    List<String> peerTopics = new CopyOnWriteArrayList<>();
    List<String> peerOrigins = new CopyOnWriteArrayList<>();
    List<Map<String, List<LiveSyncPresenceService.PresenceEditor>>> peerSnapshots =
        new CopyOnWriteArrayList<>();
    RedisLiveSyncFanout instanceB =
        newPresenceFanout(
            template, "instance-B", peerTopics, peerOrigins, peerSnapshots, peerMirrored);

    CountDownLatch ownMirrored = new CountDownLatch(1);
    RedisLiveSyncFanout instanceA =
        newPresenceFanout(
            template,
            "instance-A",
            new CopyOnWriteArrayList<>(),
            new CopyOnWriteArrayList<>(),
            new CopyOnWriteArrayList<>(),
            ownMirrored);

    listenerContainer.addMessageListener(instanceB, new ChannelTopic(PRESENCE_CHANNEL));
    listenerContainer.addMessageListener(instanceA, new ChannelTopic(PRESENCE_CHANNEL));
    // Give the subscriptions a moment to register before publishing.
    Thread.sleep(300);

    instanceA.publishPresence(
        topic,
        Map.of("crew", List.of(new LiveSyncPresenceService.PresenceEditor("user-1", "Alice"))));

    assertThat(peerMirrored.await(5, TimeUnit.SECONDS))
        .as("peer instance B mirrored the presence snapshot")
        .isTrue();
    assertThat(peerTopics).containsExactly(topic);
    assertThat(peerOrigins).containsExactly("instance-A");
    assertThat(peerSnapshots)
        .containsExactly(
            Map.of("crew", List.of(new LiveSyncPresenceService.PresenceEditor("user-1", "Alice"))));
    // The origin must skip its own gossip exactly as it skips its own changed frames.
    assertThat(ownMirrored.await(1, TimeUnit.SECONDS))
        .as("origin instance A skipped its own gossip")
        .isFalse();
  }

  @SuppressWarnings("unchecked")
  private RedisLiveSyncFanout newFanout(
      StringRedisTemplate template,
      String instanceId,
      List<String> topics,
      List<List<String>> sections,
      CountDownLatch latch) {
    LiveSyncWebSocketHandler handler = mock(LiveSyncWebSocketHandler.class);
    doAnswer(
            invocation -> {
              topics.add(invocation.getArgument(0));
              sections.add(invocation.getArgument(1));
              latch.countDown();
              return null;
            })
        .when(handler)
        .deliverFromFanout(anyString(), anyList());
    ObjectProvider<LiveSyncWebSocketHandler> provider = mock(ObjectProvider.class);
    when(provider.getObject()).thenReturn(handler);
    return new RedisLiveSyncFanout(
        template, provider, new SimpleMeterRegistry(), CHANNEL, PRESENCE_CHANNEL, instanceId);
  }

  /**
   * Builds a fan-out whose mocked handler records the presence snapshots it is asked to mirror —
   * the presence-channel counterpart of {@link #newFanout}.
   *
   * @param template the Redis template both instances publish through
   * @param instanceId the instance id this fan-out publishes under and skips on consume
   * @param topics collects the canonical topics mirrored
   * @param origins collects the publishing instance ids
   * @param snapshots collects the mirrored per-section editor maps
   * @param latch counted down on each mirrored snapshot
   * @return the fan-out, not yet subscribed
   */
  @SuppressWarnings("unchecked")
  private RedisLiveSyncFanout newPresenceFanout(
      StringRedisTemplate template,
      String instanceId,
      List<String> topics,
      List<String> origins,
      List<Map<String, List<LiveSyncPresenceService.PresenceEditor>>> snapshots,
      CountDownLatch latch) {
    LiveSyncWebSocketHandler handler = mock(LiveSyncWebSocketHandler.class);
    doAnswer(
            invocation -> {
              topics.add(invocation.getArgument(0));
              origins.add(invocation.getArgument(1));
              snapshots.add(invocation.getArgument(2));
              latch.countDown();
              return null;
            })
        .when(handler)
        .deliverPresenceFromFanout(anyString(), anyString(), anyMap());
    ObjectProvider<LiveSyncWebSocketHandler> provider = mock(ObjectProvider.class);
    when(provider.getObject()).thenReturn(handler);
    return new RedisLiveSyncFanout(
        template, provider, new SimpleMeterRegistry(), CHANNEL, PRESENCE_CHANNEL, instanceId);
  }
}
