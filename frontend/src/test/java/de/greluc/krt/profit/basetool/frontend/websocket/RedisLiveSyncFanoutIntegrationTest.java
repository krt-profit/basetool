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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
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
 * Testcontainers (ADR-0094): a {@code changed} signal published by one instance reaches a peer
 * instance's handler, while the publisher skips its own looped-back message.
 */
@Testcontainers
class RedisLiveSyncFanoutIntegrationTest {

  private static final String CHANNEL = "basetool:livesync:test";

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
        template, provider, new SimpleMeterRegistry(), CHANNEL, instanceId);
  }
}
