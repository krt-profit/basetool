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
import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Redis pub/sub {@link NotificationFanout} that makes the real-time notification push correct
 * across backend replicas (ADR-0094), discharging the ADR-0016 single-instance follow-up.
 *
 * <p>{@link #publish(Collection)} delivers to <em>this</em> instance's SSE emitters first (via
 * {@link NotificationStreamService#publish(Collection)}), then publishes {@code {v, recipients,
 * origin}} on the channel. Every replica receives it via {@link #onMessage(Message, byte[])}; a
 * message whose {@code origin} is this instance is skipped (already delivered locally), and any
 * other is delivered to this instance's emitters. Because local delivery happens before publish, a
 * Redis outage degrades to exactly the single-instance behaviour — publish fails, is counted, and
 * the frontend polling fallback (REQ-NOTIF-006) still keeps every badge correct.
 */
@Slf4j
public class RedisNotificationFanout implements NotificationFanout, MessageListener {

  /** Current payload schema version, so a future format change can be detected on consume. */
  private static final int PAYLOAD_VERSION = 1;

  private final NotificationStreamService notificationStreamService;
  private final StringRedisTemplate redisTemplate;
  private final MeterRegistry meterRegistry;
  private final JsonMapper jsonMapper;
  private final String channel;
  private final String instanceId;

  /**
   * Builds the Redis notification fan-out.
   *
   * @param notificationStreamService the local SSE emitter registry (delivers to this instance)
   * @param redisTemplate the string Redis template used to publish
   * @param meterRegistry registry the publish/consume/error counters bind to
   * @param channel the Redis channel notification signals cross
   * @param instanceId this JVM's stable instance id, used to skip own-origin messages
   */
  public RedisNotificationFanout(
      @NotNull NotificationStreamService notificationStreamService,
      @NotNull StringRedisTemplate redisTemplate,
      @NotNull MeterRegistry meterRegistry,
      @NotNull String channel,
      @NotNull String instanceId) {
    this.notificationStreamService = notificationStreamService;
    this.redisTemplate = redisTemplate;
    this.meterRegistry = meterRegistry;
    this.jsonMapper = JsonMapper.builder().build();
    this.channel = channel;
    this.instanceId = instanceId;
  }

  /**
   * Returns the Redis channel this fan-out publishes to and subscribes on.
   *
   * @return the channel name
   */
  @NotNull
  public String channel() {
    return channel;
  }

  /** {@inheritDoc} */
  @Override
  public void publish(@NotNull Collection<UUID> recipientSubs, @NotNull NotificationSignal signal) {
    // Deliver to this instance's emitters first — a Redis failure then only degrades peer delivery.
    notificationStreamService.publish(recipientSubs, signal);
    try {
      ObjectNode root = jsonMapper.createObjectNode();
      root.put("v", PAYLOAD_VERSION);
      root.put("origin", instanceId);
      ArrayNode recipients = root.putArray("recipients");
      for (UUID sub : recipientSubs) {
        recipients.add(sub.toString());
      }
      // Optional by design, and the version stays at 1. A peer on an older build ignores the field
      // and pushes the bare refresh it always did; this build receiving a message without one does
      // the same. Neither direction of a rolling deploy needs the other to have landed first.
      if (signal.describesNotification()) {
        ObjectNode signalNode = root.putObject("signal");
        signalNode.put("type", String.valueOf(signal.type()));
        signalNode.put("entityType", signal.entityType());
        signalNode.put("entityId", signal.entityId() == null ? null : signal.entityId().toString());
        ObjectNode params = signalNode.putObject("params");
        signal.params().forEach(params::put);
      }
      redisTemplate.convertAndSend(channel, jsonMapper.writeValueAsString(root));
      meterRegistry.counter(MetricNames.SSE_REDIS_PUBLISHED).increment();
    } catch (RuntimeException e) {
      meterRegistry
          .counter(MetricNames.SSE_REDIS_ERRORS, MetricNames.TAG_OP, MetricNames.OP_PUBLISH)
          .increment();
      log.debug("Notification Redis publish failed", e);
    }
  }

  /**
   * Reads the signal a peer attached, if any.
   *
   * <p>Defensive in the same way the recipient list is: a malformed or absent signal degrades to
   * the bare refresh rather than dropping the push, because a client that cannot be told what
   * arrived can still be told that something did.
   *
   * @param root the parsed message
   * @return the signal, or {@link NotificationSignal#refreshOnly()}
   */
  @NotNull
  private NotificationSignal readSignal(@NotNull JsonNode root) {
    JsonNode node = root.get("signal");
    if (node == null || !node.isObject()) {
      return NotificationSignal.refreshOnly();
    }
    NotificationType type;
    try {
      type = NotificationType.valueOf(node.path("type").asString(""));
    } catch (IllegalArgumentException e) {
      // A type this build does not know: a peer running a newer version. The push still lands.
      log.debug("Skipping unknown notification type in fan-out message", e);
      return NotificationSignal.refreshOnly();
    }
    UUID entityId = null;
    String rawId = node.path("entityId").asString("");
    if (!rawId.isEmpty()) {
      try {
        entityId = UUID.fromString(rawId);
      } catch (IllegalArgumentException e) {
        log.debug("Skipping malformed entity id in fan-out message", e);
      }
    }
    Map<String, String> params = new LinkedHashMap<>();
    JsonNode paramsNode = node.get("params");
    if (paramsNode != null && paramsNode.isObject()) {
      paramsNode
          .propertyStream()
          .forEach(entry -> params.put(entry.getKey(), entry.getValue().asString("")));
    }
    String entityType = node.path("entityType").asString("");
    return new NotificationSignal(type, entityType.isEmpty() ? null : entityType, entityId, params);
  }

  /**
   * Consumes a notification signal from a peer replica: skips this instance's own publications and
   * delivers anything else to the local emitters.
   *
   * @param message the raw Redis message
   * @param pattern the subscription pattern (unused; a single exact channel is used)
   */
  @Override
  public void onMessage(@NotNull Message message, byte[] pattern) {
    try {
      JsonNode root = jsonMapper.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
      JsonNode originNode = root.get("origin");
      if (originNode != null && originNode.isString() && instanceId.equals(originNode.asString())) {
        // Our own publication looped back — the local delivery already happened. Skip.
        return;
      }
      List<UUID> recipients = new ArrayList<>();
      JsonNode recipientsNode = root.get("recipients");
      if (recipientsNode != null && recipientsNode.isArray()) {
        for (JsonNode element : recipientsNode) {
          if (element != null && element.isString()) {
            // Skip a single malformed UUID rather than letting it abort the whole batch (F7): one
            // bad entry from a future/older or tampered peer must not drop every other recipient's
            // push. Matches the frontend consume path's per-element defensiveness.
            try {
              recipients.add(UUID.fromString(element.asString()));
            } catch (IllegalArgumentException e) {
              log.debug("Skipping malformed recipient sub in notification fan-out message", e);
            }
          }
        }
      }
      if (recipients.isEmpty()) {
        return;
      }
      notificationStreamService.publish(recipients, readSignal(root));
      meterRegistry.counter(MetricNames.SSE_REDIS_CONSUMED).increment();
    } catch (RuntimeException e) {
      meterRegistry
          .counter(MetricNames.SSE_REDIS_ERRORS, MetricNames.TAG_OP, MetricNames.OP_CONSUME)
          .increment();
      log.debug("Notification Redis consume failed", e);
    }
  }
}
