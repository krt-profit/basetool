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

import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import de.greluc.krt.profit.basetool.backend.platform.api.RedisJsonFanout;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.node.ArrayNode;

/**
 * Publishes the backend's {@code changed} frames onto the frontend's Redis channel {@code
 * basetool:livesync:changed}, where every frontend replica's {@code /ws/sync} sessions pick them up
 * (ADR-0274).
 *
 * <p>Publish-only: the backend serves no live-sync client of its own, so it never subscribes to the
 * channel. A Redis outage therefore costs a page its refresh, never the write.
 */
public class RedisLiveSyncFanout implements LiveSyncFanout {

  /** Payload schema version, so a later format change is detectable on consume. */
  private static final int PAYLOAD_VERSION = 1;

  private final MeterRegistry meterRegistry;
  private final RedisJsonFanout transport;

  /**
   * Creates the Redis live-sync publisher.
   *
   * @param redisTemplate the string template used to publish
   * @param meterRegistry registry the publish and error counters bind to
   * @param channel the shared channel the frontend listens on
   * @param instanceId this JVM's stable id, carried so a consumer can skip its own frames
   */
  public RedisLiveSyncFanout(
      @NotNull StringRedisTemplate redisTemplate,
      @NotNull MeterRegistry meterRegistry,
      @NotNull String channel,
      @NotNull String instanceId) {
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
   * Returns the channel this publisher writes to.
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
}
