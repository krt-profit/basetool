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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end integration test for {@link RedisNotificationFanout} against a real Redis in
 * Testcontainers (ADR-0094): a notification pushed on one backend instance reaches a peer
 * instance's emitters, while the publisher delivers locally and skips its own looped-back message.
 */
@Testcontainers
class RedisNotificationFanoutIntegrationTest {

  private static final String CHANNEL = "basetool:notify:test";

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
    UUID user = UUID.fromString("5f1d2c3b-0000-0000-0000-000000000042");

    // Peer instance B records what its emitters are asked to deliver.
    CountDownLatch peerDelivered = new CountDownLatch(1);
    List<Collection<UUID>> peerRecipients = new CopyOnWriteArrayList<>();
    NotificationStreamService streamB = mock(NotificationStreamService.class);
    doAnswer(
            invocation -> {
              peerRecipients.add(invocation.getArgument(0));
              peerDelivered.countDown();
              return null;
            })
        .when(streamB)
        .publish(anyCollection());
    RedisNotificationFanout instanceB =
        new RedisNotificationFanout(
            streamB, template, new SimpleMeterRegistry(), CHANNEL, "backend-B");

    // Origin instance A: its emitters get the local delivery (once), never the looped-back consume.
    NotificationStreamService streamA = mock(NotificationStreamService.class);
    RedisNotificationFanout instanceA =
        new RedisNotificationFanout(
            streamA, template, new SimpleMeterRegistry(), CHANNEL, "backend-A");

    listenerContainer.addMessageListener(instanceB, new ChannelTopic(CHANNEL));
    listenerContainer.addMessageListener(instanceA, new ChannelTopic(CHANNEL));
    Thread.sleep(300); // let the subscriptions register before publishing

    instanceA.publish(List.of(user));

    assertThat(peerDelivered.await(5, TimeUnit.SECONDS))
        .as("peer instance B delivered the signal to its emitters")
        .isTrue();
    assertThat(peerRecipients).containsExactly(List.of(user));
    // B has delivered, so A's own message has crossed Redis too; give A's listener a moment to
    // consume-and-skip it, then assert A delivered locally EXACTLY once (the own-origin skip means
    // the looped-back consume never triggers a second local publish).
    Thread.sleep(300);
    verify(streamA, times(1)).publish(List.of(user));
  }
}
