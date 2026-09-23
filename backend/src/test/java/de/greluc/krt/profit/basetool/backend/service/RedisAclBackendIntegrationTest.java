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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import de.greluc.krt.profit.basetool.testsupport.containers.TestImages;
import de.greluc.krt.profit.basetool.testsupport.redis.RedisAclTemplate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Runs the backend's two Redis fan-outs under its own ACL user, against the committed ACL template
 * with {@code default} switched off (REQ-SEC-068, ADR-0207).
 *
 * <p>The backend holds no key at all. It publishes and subscribes on the notification and live-sync
 * channels and asks {@code INFO} for its health indicator; everything else — a session key, a
 * handoff key, {@code SCAN}, the frontend's presence channel — is refused.
 */
@Testcontainers
class RedisAclBackendIntegrationTest {

  /** The notification fan-out's production channel. */
  private static final String NOTIFY_CHANNEL = "basetool:notify:published";

  /** The live-sync fan-out's production channel. */
  private static final String LIVESYNC_CHANNEL = "basetool:livesync:changed";

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse(TestImages.REDIS))
          .withExposedPorts(6379)
          .withCopyToContainer(
              Transferable.of(RedisAclTemplate.render(values())), "/etc/redis/users.acl")
          .withCommand(
              "redis-server",
              "--aclfile",
              "/etc/redis/users.acl",
              "--notify-keyspace-events",
              "Egx");

  private LettuceConnectionFactory connectionFactory;
  private RedisMessageListenerContainer listenerContainer;
  private StringRedisTemplate template;

  @BeforeEach
  void setUp() {
    RedisStandaloneConfiguration configuration =
        new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
    configuration.setUsername(RedisAclTemplate.BACKEND_USER);
    configuration.setPassword(RedisAclTemplate.E2E_PASSWORDS.get("REDIS_BACKEND_PASSWORD"));
    connectionFactory = new LettuceConnectionFactory(configuration);
    connectionFactory.afterPropertiesSet();
    connectionFactory.start();
    listenerContainer = new RedisMessageListenerContainer();
    listenerContainer.setConnectionFactory(connectionFactory);
    listenerContainer.afterPropertiesSet();
    listenerContainer.start();
    template = new StringRedisTemplate(connectionFactory);
    template.afterPropertiesSet();
  }

  @AfterEach
  void tearDown() {
    listenerContainer.stop();
    connectionFactory.destroy();
  }

  @Test
  void theNotificationFanoutPublishesAndSubscribesUnderTheBackendUser() throws Exception {
    CountDownLatch delivered = new CountDownLatch(1);
    NotificationStreamService peerStream = mock(NotificationStreamService.class);
    doAnswer(
            invocation -> {
              delivered.countDown();
              return null;
            })
        .when(peerStream)
        .publish(anyCollection(), any());
    RedisNotificationFanout peer =
        new RedisNotificationFanout(
            peerStream, template, new SimpleMeterRegistry(), NOTIFY_CHANNEL, "backend-B");
    RedisNotificationFanout origin =
        new RedisNotificationFanout(
            mock(NotificationStreamService.class),
            template,
            new SimpleMeterRegistry(),
            NOTIFY_CHANNEL,
            "backend-A");
    listenerContainer.addMessageListener(peer, new ChannelTopic(NOTIFY_CHANNEL));
    waitUntilListening();

    origin.publish(
        List.of(UUID.fromString("5f1d2c3b-0000-0000-0000-000000000042")),
        NotificationSignal.refreshOnly());

    assertThat(delivered.await(5, TimeUnit.SECONDS)).as("the peer received the signal").isTrue();
  }

  @Test
  void theLiveSyncChannelIsReachable() throws Exception {
    CountDownLatch received = new CountDownLatch(1);
    listenerContainer.addMessageListener(
        (message, pattern) -> received.countDown(), new ChannelTopic(LIVESYNC_CHANNEL));
    waitUntilListening();

    template.convertAndSend(LIVESYNC_CHANNEL, "{}");

    assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  void theHealthIndicatorsInfoAnswers() {
    try (RedisConnection connection = connectionFactory.getConnection()) {
      assertThat(connection.serverCommands().info("server")).isNotEmpty();
    }
  }

  @Test
  void everythingBeyondItsTwoChannelsIsRefused() {
    assertThatThrownBy(() -> template.opsForValue().get("basetool:session:sessions:x"))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> template.opsForValue().set("ingest:handoff:a:b", "v"))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> template.convertAndSend("basetool:livesync:presence", "{}"))
        .as("the presence gossip is the frontend's channel")
        .isInstanceOf(DataAccessException.class);
    try (RedisConnection connection = connectionFactory.getConnection()) {
      assertThatThrownBy(
              () ->
                  connection
                      .keyCommands()
                      .scan(org.springframework.data.redis.core.ScanOptions.NONE)
                      .hasNext())
          .isInstanceOf(DataAccessException.class);
    }
  }

  /**
   * Waits until the listener container's subscriptions are live.
   *
   * @throws InterruptedException when interrupted while waiting.
   */
  private void waitUntilListening() throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (!listenerContainer.isListening() && System.nanoTime() < deadline) {
      Thread.sleep(50);
    }
    assertThat(listenerContainer.isListening()).isTrue();
    Thread.sleep(300);
  }

  /**
   * The template's variables for this suite.
   *
   * @return the E2E throwaway passwords plus {@code REDIS_DEFAULT_USER=off}.
   */
  private static Map<String, String> values() {
    Map<String, String> values = new HashMap<>(RedisAclTemplate.E2E_PASSWORDS);
    values.put("REDIS_DEFAULT_USER", "off");
    return values;
  }
}
