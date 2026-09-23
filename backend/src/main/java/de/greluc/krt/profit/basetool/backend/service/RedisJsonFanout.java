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
 * The Redis pub/sub transport the two cross-replica fan-outs share (ADR-0094, ADR-0143): a JSON
 * object published on one channel, the {@code origin} echo filter, and the error counter.
 *
 * <p>Composition, not a base class: {@link RedisNotificationFanout} and {@link RedisLiveSyncFanout}
 * each own one of these and keep everything that is theirs — the payload fields and their order on
 * the wire, what a consumed message is delivered to, and their own published / consumed counters.
 * What lives here is exactly what the two had copied from each other: build and send the JSON,
 * swallow and count a failure in either direction (a Redis outage must cost peer delivery and
 * nothing else), and skip a message this instance published itself.
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
   * Builds the transport.
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
   * Publishes one JSON object. {@code fill} writes every field, in the order the wire format
   * defines, including {@code origin} ({@link #instanceId()}); {@code onSent} runs only after the
   * send succeeded. Any failure — while building, serialising or sending — is swallowed, counted as
   * {@code op=publish} and logged at DEBUG, because the local delivery has already happened.
   *
   * @param fill writes the payload fields onto the empty root object
   * @param onSent bumps the owner's published counter
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
   * Consumes one message: parses it, skips it when its {@code origin} is this instance (the local
   * delivery already happened), and hands anything else to {@code handler}. Any failure — a frame
   * that is not JSON, or one the handler cannot use — is swallowed, counted as {@code op=consume}
   * and logged at DEBUG: the sender is another process on a shared channel, possibly a different
   * build.
   *
   * @param message the raw Redis message
   * @param handler delivers a peer's parsed payload to the local streams
   */
  void consume(@NotNull Message message, @NotNull Consumer<JsonNode> handler) {
    try {
      JsonNode root = jsonMapper.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
      JsonNode origin = root.get("origin");
      if (origin != null && origin.isString() && instanceId.equals(origin.asString())) {
        // Our own publication looped back — the local delivery already happened. Skip.
        return;
      }
      handler.accept(root);
    } catch (RuntimeException e) {
      meterRegistry.counter(errorsMetric, MetricNames.TAG_OP, MetricNames.OP_CONSUME).increment();
      log.debug("{} Redis consume failed", logLabel, e);
    }
  }
}
