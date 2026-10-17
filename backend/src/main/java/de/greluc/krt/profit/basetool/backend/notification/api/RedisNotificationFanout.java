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

package de.greluc.krt.profit.basetool.backend.notification.api;

import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import de.greluc.krt.profit.basetool.backend.platform.api.RedisJsonFanout;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
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
   * @param channel the Redis channel the pushes cross
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
  public void publish(@NotNull Collection<UUID> recipientUserIds) {
    notificationStreamService.publish(recipientUserIds);
    transport.publish(
        root -> writePayload(root, recipientUserIds),
        () -> meterRegistry.counter(MetricNames.SSE_REDIS_PUBLISHED).increment());
  }

  /**
   * Writes the wire payload {@code {v, origin, recipients}} (ADR-0273).
   *
   * @param root the empty message object
   * @param recipientUserIds the {@code sub} of every recipient
   */
  private void writePayload(@NotNull ObjectNode root, @NotNull Collection<UUID> recipientUserIds) {
    root.put("v", PAYLOAD_VERSION);
    root.put("origin", transport.instanceId());
    ArrayNode recipients = root.putArray("recipients");
    for (UUID sub : recipientUserIds) {
      recipients.add(sub.toString());
    }
  }

  /**
   * Consumes a notification signal from a peer replica: skips this instance's own publications and
   * delivers anything else to the local emitters.
   *
   * @param message the raw Redis message
   * @param pattern the subscription pattern (unused; a single exact channel is used)
   */
  @Override
  public void onMessage(@NotNull Message message, @Nullable byte[] pattern) {
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
    notificationStreamService.publish(recipients);
    meterRegistry.counter(MetricNames.SSE_REDIS_CONSUMED).increment();
  }
}
