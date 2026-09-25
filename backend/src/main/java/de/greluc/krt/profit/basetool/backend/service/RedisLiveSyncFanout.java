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

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.support.LiveSyncTopic;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

/**
 * The bridge itself: publishes app-originated {@code changed} frames onto the frontend's Redis
 * channel and delivers the frontend's frames to the app's SSE streams (ADR-0143).
 *
 * <p>Both directions ride one channel, {@code basetool:livesync:changed}, with the payload ADR-0094
 * defined — {@code {"v":1,"topic":…,"sections":[…],"origin":…}}. Nothing here is app-specific on
 * the wire, which is the point: a frontend instance cannot tell an app frame from a peer
 * frontend's, and neither can this class tell a frontend frame from a peer backend's. Both simply
 * skip their own origin.
 *
 * <p>Local delivery happens in {@link LiveSyncRelayService} <em>before</em> {@link #publish} is
 * called, so a Redis outage costs peer delivery and nothing else — the same ordering, for the same
 * reason, as {@link RedisNotificationFanout}.
 */
public class RedisLiveSyncFanout implements LiveSyncFanout, MessageListener {

  /** Payload schema version, so a later format change is detectable on consume. */
  private static final int PAYLOAD_VERSION = 1;

  private final LiveSyncStreamService streamService;
  private final MeterRegistry meterRegistry;
  private final RedisJsonFanout transport;

  /**
   * Builds the Redis live-sync bridge.
   *
   * @param streamService the local emitter registry a consumed frame is delivered to
   * @param redisTemplate the string template used to publish
   * @param meterRegistry registry the publish/consume/error counters bind to
   * @param channel the shared channel — the frontend's, not a second one
   * @param instanceId this JVM's stable id, used to skip frames this instance published
   */
  public RedisLiveSyncFanout(
      @NotNull LiveSyncStreamService streamService,
      @NotNull StringRedisTemplate redisTemplate,
      @NotNull MeterRegistry meterRegistry,
      @NotNull String channel,
      @NotNull String instanceId) {
    this.streamService = streamService;
    this.meterRegistry = meterRegistry;
    this.transport =
        new RedisJsonFanout(
            redisTemplate,
            meterRegistry,
            channel,
            instanceId,
            MetricNames.LIVESYNC_REDIS_ERRORS,
            "Live-sync");
  }

  /**
   * Returns the channel this bridge publishes to and listens on.
   *
   * @return the channel name
   */
  @NotNull
  public String channel() {
    return transport.channel();
  }

  /** {@inheritDoc} */
  @Override
  public void publish(@NotNull LiveSyncTopic topic, @NotNull List<String> sections) {
    transport.publish(
        root -> {
          root.put("v", PAYLOAD_VERSION);
          root.put("topic", topic.canonical());
          root.put("origin", transport.instanceId());
          ArrayNode sectionsArray = root.putArray("sections");
          for (String section : sections) {
            sectionsArray.add(section);
          }
        },
        () ->
            meterRegistry
                .counter(
                    MetricNames.LIVESYNC_REDIS_PUBLISHED,
                    MetricNames.TAG_TOPIC_CLASS,
                    topic.topicClass().metricLabel())
                .increment());
  }

  /**
   * Consumes a frame from a frontend instance or a peer backend replica.
   *
   * <p>Skips this instance's own publications — the local delivery already happened — and drops
   * anything it cannot make sense of rather than raising: the sender is another process on a shared
   * channel, possibly a different build, and a frame naming a room this backend does not serve (the
   * frontend's staff-only rooms, for one) is an ordinary occurrence, not a fault.
   *
   * @param message the raw Redis message
   * @param pattern the subscription pattern; unused, a single exact channel is subscribed
   */
  @Override
  public void onMessage(@NotNull Message message, byte[] pattern) {
    transport.consume(message, this::deliver);
  }

  /**
   * Delivers a peer's parsed frame to this instance's streams.
   *
   * @param root the parsed payload, already known not to be this instance's own
   */
  private void deliver(@NotNull JsonNode root) {
    LiveSyncTopic topic = LiveSyncTopic.parse(text(root, "topic"));
    if (topic == null) {
      meterRegistry
          .counter(
              MetricNames.LIVESYNC_REDIS_SKIPPED,
              MetricNames.TAG_REASON,
              MetricNames.REASON_UNKNOWN_TOPIC)
          .increment();
      return;
    }
    List<String> sections = topic.topicClass().clipSections(sections(root));
    if (sections.isEmpty()) {
      return;
    }
    streamService.deliver(topic, sections);
    meterRegistry
        .counter(
            MetricNames.LIVESYNC_REDIS_CONSUMED,
            MetricNames.TAG_TOPIC_CLASS,
            topic.topicClass().metricLabel())
        .increment();
  }

  /**
   * Reads the {@code sections} array defensively.
   *
   * <p>A non-string or null element is skipped rather than aborting the frame, matching the
   * per-element tolerance of the notification consume path: one malformed entry from a peer must
   * not cost every other section its refresh.
   *
   * @param root the parsed payload
   * @return the raw section strings, in wire order
   */
  @NotNull
  private static List<String> sections(@NotNull JsonNode root) {
    List<String> raw = new ArrayList<>();
    JsonNode node = root.get("sections");
    if (node == null || !node.isArray()) {
      return raw;
    }
    for (JsonNode element : node) {
      if (element != null && element.isString()) {
        raw.add(element.asString());
      }
    }
    return raw;
  }

  /**
   * Reads a string field, tolerating absence and a wrong type.
   *
   * @param root the parsed payload
   * @param field the field name
   * @return the text, or {@code null} if the field is missing or not a string
   */
  @Nullable
  private static String text(@NotNull JsonNode root, @NotNull String field) {
    JsonNode node = root.get(field);
    return (node != null && node.isString()) ? node.asString() : null;
  }
}
