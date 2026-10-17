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

package de.greluc.krt.profit.basetool.backend.livesync.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The wire contract of the publisher: what this backend puts on the frontend's channel, and that a
 * Redis failure costs a refresh rather than the write (ADR-0274).
 */
@ExtendWith(MockitoExtension.class)
class RedisLiveSyncFanoutTest {

  private static final String CHANNEL = "basetool:livesync:changed";
  private static final String INSTANCE = "backend-instance-1";
  private static final UUID UNIT_ID = UUID.fromString("8f14e45f-ceea-467a-9c5b-5f1f52a3a1c2");

  @Mock private StringRedisTemplate redisTemplate;

  private MeterRegistry meterRegistry;
  private RedisLiveSyncFanout fanout;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    fanout = new RedisLiveSyncFanout(redisTemplate, meterRegistry, CHANNEL, INSTANCE);
  }

  @Test
  @DisplayName("a published frame carries the payload the frontend already speaks")
  void publishedPayloadMatchesTheFrontendShape() {
    fanout.publish(LiveSyncTopic.global(LiveSyncTopicClass.INVENTORY_ALL), List.of("stock"));

    ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
    verify(redisTemplate).convertAndSend(eq(CHANNEL), payload.capture());
    assertThat(payload.getValue())
        .contains("\"v\":1")
        .contains("\"topic\":\"inventory\"")
        .contains("\"origin\":\"" + INSTANCE + "\"")
        .contains("\"sections\":[\"stock\"]");
  }

  @Test
  @DisplayName("a per-resource room carries its id in the canonical topic")
  void aPerResourceRoomCarriesItsId() {
    fanout.publish(LiveSyncTopic.of(LiveSyncTopicClass.HANGAR_UNIT, UNIT_ID), List.of("ships"));

    ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
    verify(redisTemplate).convertAndSend(eq(CHANNEL), payload.capture());
    assertThat(payload.getValue()).contains("\"topic\":\"hangar-unit:" + UNIT_ID + "\"");
  }

  @Test
  @DisplayName("a published frame is counted per topic class")
  void aPublishedFrameIsCounted() {
    fanout.publish(LiveSyncTopic.global(LiveSyncTopicClass.MATERIALBOARD), List.of("board"));

    assertThat(
            meterRegistry
                .counter(
                    MetricNames.LIVESYNC_REDIS_PUBLISHED,
                    MetricNames.TAG_TOPIC_CLASS,
                    LiveSyncTopicClass.MATERIALBOARD.metricLabel())
                .count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("a publish failure is swallowed and counted, never raised")
  void aPublishFailureIsSwallowed() {
    doThrow(new IllegalStateException("redis down"))
        .when(redisTemplate)
        .convertAndSend(any(), any());

    fanout.publish(LiveSyncTopic.global(LiveSyncTopicClass.INVENTORY_ALL), List.of("stock"));

    assertThat(
            meterRegistry
                .counter(
                    MetricNames.LIVESYNC_REDIS_ERRORS, MetricNames.TAG_OP, MetricNames.OP_PUBLISH)
                .count())
        .isEqualTo(1.0);
  }
}
