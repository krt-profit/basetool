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
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Redis pub/sub transport shared by {@link RedisNotificationFanout} and {@link
 * RedisLiveSyncFanout}: publishes a JSON object on one channel, skips messages of its own origin
 * and counts failures (ADR-0094, ADR-0143).
 *
 * <p>Failures in either direction are swallowed, so a Redis outage only costs peer delivery.
 */
@Slf4j
final class RedisJsonFanout {

  private final StringRedisTemplate redisTemplate;
  private final MeterRegistry meterRegistry;
  private final JsonMapper jsonMapper = JsonMapper.builder().build();
  private final String channel;
  private final String instanceId;
  private final String errorsMetric;
  private final String logLabel;

  /**
   * Creates the transport.
   *
   * @param redisTemplate the string Redis template used to publish
   * @param meterRegistry registry the error counter binds to
   * @param channel the Redis channel the messages cross
   * @param instanceId this JVM's stable instance id, written as {@code origin} and used to skip
   *     own-origin messages
   * @param errorsMetric the error counter's name, tagged {@code op=publish|consume}
   * @param logLabel how the owning fan-out names itself in its DEBUG failure lines
   */
  RedisJsonFanout(
      @NotNull StringRedisTemplate redisTemplate,
      @NotNull MeterRegistry meterRegistry,
      @NotNull String channel,
      @NotNull String instanceId,
      @NotNull String errorsMetric,
      @NotNull String logLabel) {
    this.redisTemplate = redisTemplate;
    this.meterRegistry = meterRegistry;
    this.channel = channel;
    this.instanceId = instanceId;
    this.errorsMetric = errorsMetric;
    this.logLabel = logLabel;
  }

  /**
   * Returns the Redis channel this transport publishes to and its owner subscribes on.
   *
   * @return the channel name
   */
  @NotNull
  String channel() {
    return channel;
  }

  /**
   * Returns this JVM's instance id, the value a payload carries as {@code origin}.
   *
   * @return the instance id
   */
  @NotNull
  String instanceId() {
    return instanceId;
  }

  /**
   * Publishes one JSON object; any failure is swallowed, counted as {@code op=publish} and logged
   * at DEBUG.
   *
   * @param fill writes the payload fields, including {@code origin}, onto the empty root object
   * @param onSent runs only after a successful send
   */
  void publish(@NotNull Consumer<ObjectNode> fill, @NotNull Runnable onSent) {
    try {
      ObjectNode root = jsonMapper.createObjectNode();
      fill.accept(root);
      redisTemplate.convertAndSend(channel, jsonMapper.writeValueAsString(root));
      onSent.run();
    } catch (RuntimeException e) {
      meterRegistry.counter(errorsMetric, MetricNames.TAG_OP, MetricNames.OP_PUBLISH).increment();
      log.debug("{} Redis publish failed", logLabel, e);
    }
  }

  /**
   * Parses one message and hands it to {@code handler} unless its {@code origin} is this instance;
   * any failure is swallowed, counted as {@code op=consume} and logged at DEBUG.
   *
   * @param message the raw Redis message
   * @param handler delivers a peer's parsed payload to the local streams
   */
  void consume(@NotNull Message message, @NotNull Consumer<JsonNode> handler) {
    try {
      JsonNode root = jsonMapper.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
      JsonNode origin = root.get("origin");
      if (origin != null && origin.isString() && instanceId.equals(origin.asString())) {
        return;
      }
      handler.accept(root);
    } catch (RuntimeException e) {
      meterRegistry.counter(errorsMetric, MetricNames.TAG_OP, MetricNames.OP_CONSUME).increment();
      log.debug("{} Redis consume failed", logLabel, e);
    }
  }
}
