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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Redis pub/sub {@link NotificationFanout} that delivers real-time notification pushes across
 * backend replicas (ADR-0094).
 *
 * <p>{@link #publish(Collection)} delivers to this instance's emitters first, then publishes on the
 * channel; {@link #onMessage(Message, byte[])} delivers peers' messages and skips its own. A Redis
 * outage leaves local delivery and the polling fallback intact (REQ-NOTIF-006).
 */
@Slf4j
public class RedisNotificationFanout implements NotificationFanout, MessageListener {

  /** Current payload schema version, so a future format change can be detected on consume. */
  private static final int PAYLOAD_VERSION = 1;

  private final NotificationStreamService notificationStreamService;
  private final MeterRegistry meterRegistry;
  private final RedisJsonFanout transport;

  /**
   * Creates the Redis notification fan-out.
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
    this.meterRegistry = meterRegistry;
    this.transport =
        new RedisJsonFanout(
            redisTemplate,
            meterRegistry,
            channel,
            instanceId,
            MetricNames.SSE_REDIS_ERRORS,
            "Notification");
  }

  /**
   * Returns the Redis channel this fan-out publishes to and subscribes on.
   *
   * @return the channel name
   */
  @NotNull
  public String channel() {
    return transport.channel();
  }

  /** {@inheritDoc} */
  @Override
  public void publish(
      @NotNull Collection<UUID> recipientUserIds, @NotNull NotificationSignal signal) {
    notificationStreamService.publish(recipientUserIds, signal);
    transport.publish(
        root -> writePayload(root, recipientUserIds, signal),
        () -> meterRegistry.counter(MetricNames.SSE_REDIS_PUBLISHED).increment());
  }

  /**
   * Writes the wire payload {@code {v, origin, recipients[, signal]}}.
   *
   * @param root the empty message object
   * @param recipientUserIds the {@code sub} of every recipient
   * @param signal what arrived; written only when it describes a notification
   */
  private void writePayload(
      @NotNull ObjectNode root,
      @NotNull Collection<UUID> recipientUserIds,
      @NotNull NotificationSignal signal) {
    root.put("v", PAYLOAD_VERSION);
    root.put("origin", transport.instanceId());
    ArrayNode recipients = root.putArray("recipients");
    for (UUID sub : recipientUserIds) {
      recipients.add(sub.toString());
    }
    if (signal.describesNotification()) {
      ObjectNode signalNode = root.putObject("signal");
      signalNode.put("type", String.valueOf(signal.type()));
      signalNode.put("entityType", signal.entityType());
      signalNode.put("entityId", signal.entityId() == null ? null : signal.entityId().toString());
      ObjectNode params = signalNode.putObject("params");
      signal.params().forEach(params::put);
    }
  }

  /**
   * Reads the signal a peer attached; a malformed or absent signal falls back to a bare refresh.
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
    transport.consume(message, this::deliver);
  }

  /**
   * Delivers a peer's parsed message to this instance's emitters.
   *
   * @param root the parsed payload, already known not to be this instance's own
   */
  private void deliver(@NotNull JsonNode root) {
    List<UUID> recipients = new ArrayList<>();
    JsonNode recipientsNode = root.get("recipients");
    if (recipientsNode != null && recipientsNode.isArray()) {
      for (JsonNode element : recipientsNode) {
        if (element != null && element.isString()) {
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
  }
}
