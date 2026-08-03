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
import de.greluc.krt.profit.basetool.frontend.service.LiveSyncPresenceService;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * replicas (ADR-0094) and the editor-presence dots consistent across them (ADR-0126).
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
 * <p><b>Two channels, one listener.</b> {@link #publishPresence(String, Map)} follows the identical
 * local-first + own-origin-skip shape on a second channel carrying {@code {v, topic, origin,
 * sections:{key:[{userId,displayName}]}}} — this instance's <em>complete</em> presence state for
 * the topic, not a delta. {@link #onMessage(Message, byte[])} dispatches on the channel the message
 * arrived on. The streams are separated because presence is periodic and cosmetic while the changed
 * relay is event-driven and load-bearing; mixing them would make one indistinguishable from the
 * other on the fan-out dashboard and let gossip volume mask a changed-relay outage.
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
  private final String presenceChannel;
  private final String instanceId;

  /**
   * Builds the Redis fan-out.
   *
   * @param redisTemplate the string Redis template used to publish
   * @param handlerProvider lazy provider of the relay handler (breaks the construction cycle)
   * @param meterRegistry registry the publish/consume/error counters bind to
   * @param channel the Redis channel {@code changed} signals cross
   * @param presenceChannel the Redis channel editor-presence snapshots cross (ADR-0126); must
   *     differ from {@code channel}, since {@link #onMessage(Message, byte[])} tells the two
   *     payload formats apart by the channel a message arrived on
   * @param instanceId this JVM's stable instance id, used to skip own-origin messages and to key
   *     the presence partition peers hold for this replica
   */
  public RedisLiveSyncFanout(
      @NotNull StringRedisTemplate redisTemplate,
      @NotNull ObjectProvider<LiveSyncWebSocketHandler> handlerProvider,
      @NotNull MeterRegistry meterRegistry,
      @NotNull String channel,
      @NotNull String presenceChannel,
      @NotNull String instanceId) {
    this.redisTemplate = redisTemplate;
    this.handlerProvider = handlerProvider;
    this.meterRegistry = meterRegistry;
    this.jsonMapper = JsonMapper.builder().build();
    this.channel = channel;
    this.presenceChannel = presenceChannel;
    this.instanceId = instanceId;
  }

  /**
   * Returns the Redis channel this fan-out publishes {@code changed} signals to and subscribes on
   * (used by the config to register the message-listener container).
   *
   * @return the changed-relay channel name
   */
  @NotNull
  public String channel() {
    return channel;
  }

  /**
   * Returns the Redis channel this fan-out gossips editor-presence snapshots on (ADR-0126), the
   * second topic the config registers on the message-listener container.
   *
   * @return the presence channel name
   */
  @NotNull
  public String presenceChannel() {
    return presenceChannel;
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

  /** {@inheritDoc} */
  @Override
  public void publishPresence(
      @NotNull String canonicalTopic,
      @NotNull Map<String, List<LiveSyncPresenceService.PresenceEditor>> sections) {
    try {
      ObjectNode root = jsonMapper.createObjectNode();
      root.put("v", PAYLOAD_VERSION);
      root.put("topic", canonicalTopic);
      root.put("origin", instanceId);
      ObjectNode sectionsNode = root.putObject("sections");
      for (Map.Entry<String, List<LiveSyncPresenceService.PresenceEditor>> entry :
          sections.entrySet()) {
        ArrayNode editors = sectionsNode.putArray(entry.getKey());
        for (LiveSyncPresenceService.PresenceEditor editor : entry.getValue()) {
          ObjectNode editorNode = editors.addObject();
          editorNode.put("userId", editor.userId());
          editorNode.put("displayName", editor.displayName());
        }
      }
      redisTemplate.convertAndSend(presenceChannel, jsonMapper.writeValueAsString(root));
      meterRegistry
          .counter(
              MetricNames.LIVESYNC_PRESENCE_PUBLISHED,
              MetricNames.TAG_TOPIC_CLASS,
              metricLabel(canonicalTopic))
          .increment();
    } catch (RuntimeException e) {
      // Same swallow-and-count contract as the changed relay: the local dots were already
      // broadcast, so a failed gossip costs peers a cosmetic dot until the next tick, never a
      // stale view of the data itself.
      meterRegistry
          .counter(
              MetricNames.LIVESYNC_REDIS_ERRORS,
              MetricNames.TAG_OP,
              MetricNames.OP_PRESENCE_PUBLISH)
          .increment();
      log.debug("Live-sync Redis presence gossip failed for topic {}", canonicalTopic, e);
    }
  }

  /**
   * Consumes one fan-out message, dispatching on the channel it arrived on: a {@code changed}
   * signal is relayed to the local rooms, a presence snapshot is merged into the local presence
   * mirror (ADR-0126). Either way this instance's own publications are skipped — the local
   * relay/broadcast already happened.
   *
   * @param message the raw Redis message
   * @param pattern the subscription pattern (unused; two exact channels are used)
   */
  @Override
  public void onMessage(@NotNull Message message, byte[] pattern) {
    if (presenceChannel.equals(new String(message.getChannel(), StandardCharsets.UTF_8))) {
      onPresenceMessage(message);
      return;
    }
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

  /**
   * Consume half of the presence gossip (ADR-0126): parses a peer's complete presence snapshot for
   * one topic and hands it to the handler, which sanitises it, replaces that origin's partition and
   * re-broadcasts the merged dots to the local room.
   *
   * <p>Unlike the {@code changed} path, an <b>empty</b> {@code sections} object is not discarded —
   * it is the peer saying "no editors here any more", which drops its partition immediately rather
   * than waiting out the partition TTL.
   *
   * @param message the raw Redis message from the presence channel
   */
  private void onPresenceMessage(@NotNull Message message) {
    try {
      JsonNode root = jsonMapper.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
      String origin = textOrNull(root, "origin");
      if (origin == null || origin.isBlank() || instanceId.equals(origin)) {
        // Our own gossip looped back (or a payload with no usable origin, which cannot key a
        // partition) — the local broadcast already carried our own state. Skip.
        return;
      }
      String topic = textOrNull(root, "topic");
      if (topic == null) {
        return;
      }
      Map<String, List<LiveSyncPresenceService.PresenceEditor>> sections = new LinkedHashMap<>();
      JsonNode sectionsNode = root.get("sections");
      if (sectionsNode != null && sectionsNode.isObject()) {
        for (Map.Entry<String, JsonNode> sectionEntry : sectionsNode.properties()) {
          List<LiveSyncPresenceService.PresenceEditor> editors =
              readEditors(sectionEntry.getValue());
          if (!editors.isEmpty()) {
            sections.put(sectionEntry.getKey(), editors);
          }
        }
      }
      handlerProvider.getObject().deliverPresenceFromFanout(topic, origin, sections);
      meterRegistry
          .counter(
              MetricNames.LIVESYNC_PRESENCE_CONSUMED,
              MetricNames.TAG_TOPIC_CLASS,
              metricLabel(topic))
          .increment();
    } catch (RuntimeException e) {
      meterRegistry
          .counter(
              MetricNames.LIVESYNC_REDIS_ERRORS,
              MetricNames.TAG_OP,
              MetricNames.OP_PRESENCE_CONSUME)
          .increment();
      log.debug("Live-sync Redis presence consume failed", e);
    }
  }

  /**
   * Reads one section's editor array from a gossiped presence snapshot, skipping any element that
   * is not an object with a non-empty {@code userId}. A missing {@code displayName} degrades to the
   * empty string rather than dropping the editor: the dot still belongs on the panel even if its
   * label is unusable.
   *
   * @param editorsNode the raw array node (may be {@code null} or not an array)
   * @return the parsed editors, possibly empty
   */
  @NotNull
  private static List<LiveSyncPresenceService.PresenceEditor> readEditors(JsonNode editorsNode) {
    List<LiveSyncPresenceService.PresenceEditor> editors = new ArrayList<>();
    if (editorsNode == null || !editorsNode.isArray()) {
      return editors;
    }
    for (JsonNode element : editorsNode) {
      if (element == null || !element.isObject()) {
        continue;
      }
      String userId = textOrNull(element, "userId");
      if (userId == null || userId.isBlank()) {
        continue;
      }
      String displayName = textOrNull(element, "displayName");
      editors.add(
          new LiveSyncPresenceService.PresenceEditor(
              userId, displayName == null ? "" : displayName));
    }
    return editors;
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
