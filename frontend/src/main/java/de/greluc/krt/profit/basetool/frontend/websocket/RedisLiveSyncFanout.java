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

import de.greluc.krt.profit.basetool.frontend.metrics.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Redis pub/sub {@link LiveSyncFanout} that makes the {@code changed} relay correct across frontend
 * replicas (ADR-0092).
 *
 * <p>The handler always relays a {@code changed} frame to <em>this</em> instance's local rooms
 * first, then calls {@link #publish(String, List)}, which serialises {@code {v, topic, sections,
 * origin}} and publishes it on the configured channel. Every replica (including this one) receives
 * it via {@link #onMessage(Message, byte[])}; a message whose {@code origin} is this instance is
 * skipped (the local relay already delivered it), and any other is relayed to this instance's local
 * rooms through {@link LiveSyncWebSocketHandler#deliverFromFanout(String, List)}. Because local
 * relay happens before publish, a Redis outage degrades to exactly the single-instance behaviour —
 * publish simply fails, is counted, and the local viewers are already up to date.
 *
 * <p>The handler reference is injected lazily through an {@link ObjectProvider} to break the
 * construction cycle (the handler depends on the fan-out to publish; the fan-out depends on the
 * handler to deliver on consume).
 */
@Slf4j
public class RedisLiveSyncFanout implements LiveSyncFanout, MessageListener {

  /** Current payload schema version, so a future format change can be detected on consume. */
  private static final int PAYLOAD_VERSION = 1;

  private final StringRedisTemplate redisTemplate;
  private final ObjectProvider<LiveSyncWebSocketHandler> handlerProvider;
  private final MeterRegistry meterRegistry;
  private final JsonMapper jsonMapper;
  private final String channel;
  private final String instanceId;

  /**
   * Builds the Redis fan-out.
   *
   * @param redisTemplate the string Redis template used to publish
   * @param handlerProvider lazy provider of the relay handler (breaks the construction cycle)
   * @param meterRegistry registry the publish/consume/error counters bind to
   * @param channel the Redis channel {@code changed} signals cross
   * @param instanceId this JVM's stable instance id, used to skip own-origin messages
   */
  public RedisLiveSyncFanout(
      @NotNull StringRedisTemplate redisTemplate,
      @NotNull ObjectProvider<LiveSyncWebSocketHandler> handlerProvider,
      @NotNull MeterRegistry meterRegistry,
      @NotNull String channel,
      @NotNull String instanceId) {
    this.redisTemplate = redisTemplate;
    this.handlerProvider = handlerProvider;
    this.meterRegistry = meterRegistry;
    this.jsonMapper = JsonMapper.builder().build();
    this.channel = channel;
    this.instanceId = instanceId;
  }

  /**
   * Returns the Redis channel this fan-out publishes to and subscribes on (used by the config to
   * register the message-listener container).
   *
   * @return the channel name
   */
  @NotNull
  public String channel() {
    return channel;
  }

  /** {@inheritDoc} */
  @Override
  public void publish(@NotNull String canonicalTopic, @NotNull List<String> sections) {
    try {
      ObjectNode root = jsonMapper.createObjectNode();
      root.put("v", PAYLOAD_VERSION);
      root.put("topic", canonicalTopic);
      root.put("origin", instanceId);
      ArrayNode sectionsArray = root.putArray("sections");
      for (String key : sections) {
        sectionsArray.add(key);
      }
      redisTemplate.convertAndSend(channel, jsonMapper.writeValueAsString(root));
      meterRegistry
          .counter(
              MetricNames.LIVESYNC_REDIS_PUBLISHED,
              MetricNames.TAG_TOPIC_CLASS,
              metricLabel(canonicalTopic))
          .increment();
    } catch (RuntimeException e) {
      // Local relay already delivered to this instance's viewers; a failed cross-replica publish
      // only degrades peer delivery, so swallow-and-count rather than propagate to the caller.
      meterRegistry
          .counter(MetricNames.LIVESYNC_REDIS_ERRORS, MetricNames.TAG_OP, MetricNames.OP_PUBLISH)
          .increment();
      log.debug("Live-sync Redis publish failed for topic {}", canonicalTopic, e);
    }
  }

  /**
   * Consumes a {@code changed} signal from a peer replica: skips this instance's own publications
   * and relays anything else to the local rooms.
   *
   * @param message the raw Redis message
   * @param pattern the subscription pattern (unused; a single exact channel is used)
   */
  @Override
  public void onMessage(@NotNull Message message, byte[] pattern) {
    try {
      JsonNode root = jsonMapper.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
      String origin = textOrNull(root, "origin");
      if (instanceId.equals(origin)) {
        // Our own publication looped back — the local relay already delivered it. Skip.
        return;
      }
      String topic = textOrNull(root, "topic");
      if (topic == null) {
        return;
      }
      List<String> sections = new ArrayList<>();
      JsonNode sectionsNode = root.get("sections");
      if (sectionsNode != null && sectionsNode.isArray()) {
        for (JsonNode element : sectionsNode) {
          if (element != null && element.isString()) {
            sections.add(element.asString());
          }
        }
      }
      if (sections.isEmpty()) {
        return;
      }
      handlerProvider.getObject().deliverFromFanout(topic, sections);
      meterRegistry
          .counter(
              MetricNames.LIVESYNC_REDIS_CONSUMED, MetricNames.TAG_TOPIC_CLASS, metricLabel(topic))
          .increment();
    } catch (RuntimeException e) {
      meterRegistry
          .counter(MetricNames.LIVESYNC_REDIS_ERRORS, MetricNames.TAG_OP, MetricNames.OP_CONSUME)
          .increment();
      log.debug("Live-sync Redis consume failed", e);
    }
  }

  private static String textOrNull(@NotNull JsonNode node, @NotNull String field) {
    JsonNode value = node.get(field);
    return value != null && value.isString() ? value.asString() : null;
  }

  /**
   * Resolves the bounded {@code topic_class} metric label for a canonical topic, falling back to
   * {@code unknown} for an unparseable one so the counter never carries an unbounded label.
   *
   * @param canonicalTopic the canonical topic string
   * @return the metric label
   */
  private static String metricLabel(@NotNull String canonicalTopic) {
    LiveSyncTopic topic = LiveSyncTopic.parse(canonicalTopic);
    return topic != null ? topic.topicClass().metricLabel() : "unknown";
  }
}
