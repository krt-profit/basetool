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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Unit tests for {@link RedisNotificationFanout} with a mocked {@link StringRedisTemplate} and
 * stream service: local-first delivery, own-origin skip, cross-instance consume and error counting
 * are verified deterministically without a Redis container (the round trip is covered by {@code
 * RedisNotificationFanoutIntegrationTest}).
 */
class RedisNotificationFanoutTest {

  private static final String CHANNEL = "basetool:notify:published";
  private static final String SELF = "backend-A";
  private static final UUID USER = UUID.fromString("5f1d2c3b-0000-0000-0000-000000000001");

  private NotificationStreamService streamService;
  private StringRedisTemplate redisTemplate;
  private SimpleMeterRegistry registry;
  private RedisNotificationFanout fanout;

  @BeforeEach
  void setUp() {
    streamService = mock(NotificationStreamService.class);
    redisTemplate = mock(StringRedisTemplate.class);
    registry = new SimpleMeterRegistry();
    fanout = new RedisNotificationFanout(streamService, redisTemplate, registry, CHANNEL, SELF);
  }

  @Test
  void publish_deliversLocallyFirst_thenPublishesToRedis_andCounts() {
    fanout.publish(List.of(USER));

    // Local delivery happens before (and independent of) the cross-replica publish.
    verify(streamService).publish(List.of(USER));
    verify(redisTemplate).convertAndSend(anyString(), anyString());
    assertThat(publishedCount()).isEqualTo(1.0);
  }

  @Test
  void publish_stillDeliversLocally_whenRedisPublishFails_andCountsAnError() {
    doThrow(new IllegalStateException("redis down"))
        .when(redisTemplate)
        .convertAndSend(anyString(), anyString());

    fanout.publish(List.of(USER));

    // The local delivery already happened; the failed cross-replica publish is swallowed + counted.
    verify(streamService).publish(List.of(USER));
    assertThat(errorCount(MetricNames.OP_PUBLISH)).isEqualTo(1.0);
    assertThat(publishedCount()).isZero();
  }

  @Test
  void onMessage_skipsOwnOriginPublications() {
    String body = "{\"v\":1,\"origin\":\"" + SELF + "\",\"recipients\":[\"" + USER + "\"]}";

    fanout.onMessage(message(body), null);

    verify(streamService, never()).publish(anyCollection());
    assertThat(consumedCount()).isZero();
  }

  @Test
  void onMessage_deliversAPeerReplicasSignalLocally_andCounts() {
    String body = "{\"v\":1,\"origin\":\"backend-B\",\"recipients\":[\"" + USER + "\"]}";

    fanout.onMessage(message(body), null);

    verify(streamService).publish(List.of(USER));
    assertThat(consumedCount()).isEqualTo(1.0);
  }

  @Test
  void onMessage_ignoresMalformedPayload_andCountsAConsumeError() {
    fanout.onMessage(message("{not json"), null);

    verify(streamService, never()).publish(anyCollection());
    assertThat(errorCount(MetricNames.OP_CONSUME)).isEqualTo(1.0);
  }

  private static DefaultMessage message(String body) {
    return new DefaultMessage(
        CHANNEL.getBytes(StandardCharsets.UTF_8), body.getBytes(StandardCharsets.UTF_8));
  }

  private double publishedCount() {
    var counter = registry.find(MetricNames.SSE_REDIS_PUBLISHED).counter();
    return counter == null ? 0.0 : counter.count();
  }

  private double consumedCount() {
    var counter = registry.find(MetricNames.SSE_REDIS_CONSUMED).counter();
    return counter == null ? 0.0 : counter.count();
  }

  private double errorCount(String op) {
    var counter = registry.find(MetricNames.SSE_REDIS_ERRORS).tag(MetricNames.TAG_OP, op).counter();
    return counter == null ? 0.0 : counter.count();
  }
}
