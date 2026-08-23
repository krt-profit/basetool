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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.support.LiveSyncTopic;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The wire contract of the bridge: what this backend puts on the frontend's channel, and what it
 * accepts back off it (ADR-0143).
 */
@ExtendWith(MockitoExtension.class)
class RedisLiveSyncFanoutTest {

  private static final String CHANNEL = "basetool:livesync:changed";
  private static final String INSTANCE = "backend-instance-1";
  private static final UUID MISSION_ID = UUID.fromString("8f14e45f-ceea-467a-9c5b-5f1f52a3a1c2");

  @Mock private LiveSyncStreamService streamService;
  @Mock private StringRedisTemplate redisTemplate;

  private MeterRegistry meterRegistry;
  private RedisLiveSyncFanout fanout;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    fanout =
        new RedisLiveSyncFanout(streamService, redisTemplate, meterRegistry, CHANNEL, INSTANCE);
  }

  @Test
  @DisplayName("a published frame carries the payload the frontend already speaks")
  void publishedPayloadMatchesTheFrontendShape() {
    fanout.publish(LiveSyncTopic.parse("inventory"), List.of("stock"));

    ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
    verify(redisTemplate).convertAndSend(eq(CHANNEL), payload.capture());
    // The whole bridge rests on this being byte-compatible with what a frontend instance sends:
    // v, topic, origin, sections.
    assertThat(payload.getValue())
        .contains("\"v\":1")
        .contains("\"topic\":\"inventory\"")
        .contains("\"origin\":\"" + INSTANCE + "\"")
        .contains("\"sections\":[\"stock\"]");
  }

  @Test
  @DisplayName("a publish failure is swallowed and counted, never raised")
  void aPublishFailureIsSwallowed() {
    doThrow(new IllegalStateException("redis down"))
        .when(redisTemplate)
        .convertAndSend(any(), any());

    fanout.publish(LiveSyncTopic.parse("inventory"), List.of("stock"));

    // Local delivery already happened in the relay; a fan-out failure must not fail the request.
    assertThat(errors(MetricNames.OP_PUBLISH)).isEqualTo(1.0);
  }

  @Test
  @DisplayName("a frontend frame is delivered to this instance's streams")
  void aFrontendFrameIsDelivered() {
    fanout.onMessage(
        message(payload("frontend-instance", "mission:" + MISSION_ID, "\"crew\"")), null);

    verify(streamService).deliver(LiveSyncTopic.parse("mission:" + MISSION_ID), List.of("crew"));
    assertThat(
            meterRegistry
                .counter(
                    MetricNames.LIVESYNC_REDIS_CONSUMED, MetricNames.TAG_TOPIC_CLASS, "mission")
                .count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("this instance's own frame loops back and is skipped")
  void ownFramesAreSkipped() {
    fanout.onMessage(message(payload(INSTANCE, "inventory", "\"stock\"")), null);

    // It was already delivered locally before it was published; delivering again would double
    // every app-originated refresh.
    verify(streamService, never()).deliver(any(), any());
  }

  @Test
  @DisplayName("a frame naming a room this backend does not serve is dropped, not fatal")
  void unknownRoomsAreDropped() {
    // The frontend's staff rooms ride the same channel. Seeing them is normal, not an error.
    fanout.onMessage(message(payload("frontend-instance", "bank", "\"grid\"")), null);

    verify(streamService, never()).deliver(any(), any());
    // Under "skipped", not "errors": a permanent non-zero rate beneath the alert that watches the
    // error series would teach everyone to ignore it.
    assertThat(
            meterRegistry
                .counter(
                    MetricNames.LIVESYNC_REDIS_SKIPPED,
                    MetricNames.TAG_REASON,
                    MetricNames.REASON_UNKNOWN_TOPIC)
                .count())
        .isEqualTo(1.0);
    assertThat(meterRegistry.find(MetricNames.LIVESYNC_REDIS_ERRORS).counters()).isEmpty();
  }

  @Test
  @DisplayName("sections outside the class whitelist are clipped away on arrival")
  void unknownSectionsAreClippedOnArrival() {
    fanout.onMessage(
        message(payload("frontend-instance", "inventory", "\"stock\",\"grid\",17,null")), null);

    verify(streamService).deliver(LiveSyncTopic.parse("inventory"), List.of("stock"));
  }

  @Test
  @DisplayName("a frame whose sections all clip away is not delivered")
  void aFrameWithNoUsableSectionIsNotDelivered() {
    fanout.onMessage(message(payload("frontend-instance", "inventory", "\"grid\"")), null);

    verify(streamService, never()).deliver(any(), any());
  }

  @Test
  @DisplayName("malformed JSON is counted and dropped rather than killing the listener")
  void malformedJsonIsSwallowed() {
    fanout.onMessage(
        new DefaultMessage(
            CHANNEL.getBytes(StandardCharsets.UTF_8), "{not json".getBytes(StandardCharsets.UTF_8)),
        null);

    assertThat(errors(MetricNames.OP_CONSUME)).isEqualTo(1.0);
    verify(streamService, never()).deliver(any(), any());
  }

  private static String payload(String origin, String topic, String sections) {
    return "{\"v\":1,\"topic\":\""
        + topic
        + "\",\"origin\":\""
        + origin
        + "\",\"sections\":["
        + sections
        + "]}";
  }

  private static DefaultMessage message(String body) {
    return new DefaultMessage(
        CHANNEL.getBytes(StandardCharsets.UTF_8), body.getBytes(StandardCharsets.UTF_8));
  }

  private double errors(String op) {
    return meterRegistry.get(MetricNames.LIVESYNC_REDIS_ERRORS).counters().stream()
        .filter(counter -> op.equals(counter.getId().getTag(MetricNames.TAG_OP)))
        .mapToDouble(io.micrometer.core.instrument.Counter::count)
        .sum();
  }
}
