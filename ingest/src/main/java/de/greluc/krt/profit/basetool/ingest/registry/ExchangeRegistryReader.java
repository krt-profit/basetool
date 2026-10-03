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

package de.greluc.krt.profit.basetool.ingest.registry;

import de.greluc.krt.profit.basetool.ingest.config.ExchangeGatewayProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the registry mirror the backend writes, through a short cache, and fails closed: an
 * unreadable, missing or unknown-format document is an {@link ExchangeUnavailableException}, never
 * an empty registry (REQ-XCH-003).
 */
@Slf4j
@Component
public class ExchangeRegistryReader {

  /** The mirror document format this reader understands. */
  public static final int SCHEMA_VERSION = 1;

  /** The registry status that admits a client. */
  public static final String STATUS_ACTIVE = "ACTIVE";

  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;
  private final ExchangeGatewayProperties properties;
  private final Clock clock;

  private volatile @Nullable Cached cached;

  /**
   * Creates the reader on the system clock.
   *
   * @param redisTemplate the Redis access
   * @param objectMapper parses the document
   * @param properties the key and the cache lifetime
   */
  @Autowired
  public ExchangeRegistryReader(
      @NotNull StringRedisTemplate redisTemplate,
      @NotNull ObjectMapper objectMapper,
      @NotNull ExchangeGatewayProperties properties) {
    this(redisTemplate, objectMapper, properties, Clock.systemUTC());
  }

  /**
   * Creates the reader on the given clock, so a test can move time past the cache.
   *
   * @param redisTemplate the Redis access
   * @param objectMapper parses the document
   * @param properties the key and the cache lifetime
   * @param clock the time source
   */
  ExchangeRegistryReader(
      @NotNull StringRedisTemplate redisTemplate,
      @NotNull ObjectMapper objectMapper,
      @NotNull ExchangeGatewayProperties properties,
      @NotNull Clock clock) {
    this.redisTemplate = redisTemplate;
    this.objectMapper = objectMapper;
    this.properties = properties;
    this.clock = clock;
  }

  /**
   * Returns how long ago the mirror was last read successfully.
   *
   * @return the seconds since the last successful read, or {@link Double#NaN} before the first
   */
  public double secondsSinceLastRead() {
    Cached hit = cached;
    if (hit == null) {
      return Double.NaN;
    }
    return Duration.between(hit.readAt(), clock.instant()).toMillis() / 1000.0d;
  }

  /**
   * Returns the registry, from the cache while it is fresh.
   *
   * @return the registry
   * @throws ExchangeUnavailableException if Redis cannot be read or the document is unusable
   */
  public @NotNull ExchangeRegistry current() {
    Instant now = clock.instant();
    Cached hit = cached;
    if (hit != null && now.isBefore(hit.readAt().plus(properties.registryCacheTtl()))) {
      return hit.registry();
    }
    ExchangeRegistry registry = read();
    cached = new Cached(registry, now);
    return registry;
  }

  /**
   * Reads and parses the mirror document.
   *
   * @return the registry
   * @throws ExchangeUnavailableException if Redis cannot be read or the document is unusable
   */
  private @NotNull ExchangeRegistry read() {
    String json;
    try {
      json = redisTemplate.opsForValue().get(properties.registryKey());
    } catch (RuntimeException e) {
      log.warn("Exchange registry read failed: {}", e.getClass().getSimpleName());
      throw new ExchangeUnavailableException("The registry mirror cannot be read.", e);
    }
    if (json == null) {
      throw new ExchangeUnavailableException("The registry mirror is missing.", null);
    }
    try {
      return parse(objectMapper.readTree(json));
    } catch (ExchangeUnavailableException e) {
      throw e;
    } catch (RuntimeException e) {
      log.warn("Exchange registry document is unreadable: {}", e.getClass().getSimpleName());
      throw new ExchangeUnavailableException("The registry mirror is unreadable.", e);
    }
  }

  /**
   * Parses the document.
   *
   * @param root the document
   * @return the registry
   * @throws ExchangeUnavailableException if the format is unknown
   */
  public static @NotNull ExchangeRegistry parse(@NotNull JsonNode root) {
    JsonNode version = root.get("schemaVersion");
    if (version == null || !version.isInt() || version.intValue() != SCHEMA_VERSION) {
      throw new ExchangeUnavailableException("The registry mirror has an unknown format.", null);
    }
    JsonNode revision = root.get("revision");
    JsonNode enabled = root.get("enabled");
    Map<String, ExchangeRegistry.Client> clients = new HashMap<>();
    JsonNode clientNodes = root.get("clients");
    if (clientNodes != null && clientNodes.isObject()) {
      for (Map.Entry<String, JsonNode> entry : clientNodes.properties()) {
        clients.put(entry.getKey(), client(entry.getValue()));
      }
    }
    return new ExchangeRegistry(
        revision != null && revision.isNumber() ? revision.longValue() : 0L,
        enabled != null && enabled.isBoolean() && enabled.booleanValue(),
        clients);
  }

  /**
   * Parses one client.
   *
   * @param node the client's node
   * @return the client
   */
  private static @NotNull ExchangeRegistry.Client client(@NotNull JsonNode node) {
    Set<String> capabilities = new HashSet<>();
    JsonNode capabilityNodes = node.get("capabilities");
    if (capabilityNodes != null && capabilityNodes.isArray()) {
      for (JsonNode capability : capabilityNodes) {
        if (capability.isString()) {
          capabilities.add(capability.stringValue());
        }
      }
    }
    return new ExchangeRegistry.Client(
        text(node, "displayName") == null ? "" : text(node, "displayName"),
        STATUS_ACTIVE.equals(text(node, "status")),
        capabilities,
        text(node, "minClientVersion"),
        integer(node, "requestsPerMinute"),
        integer(node, "writesPerDay"));
  }

  /**
   * Reads a text member.
   *
   * @param node the object
   * @param name the member
   * @return its text, or {@code null} when absent or not text
   */
  private static @Nullable String text(@NotNull JsonNode node, @NotNull String name) {
    JsonNode value = node.get(name);
    return value != null && value.isString() ? value.stringValue() : null;
  }

  /**
   * Reads an integer member.
   *
   * @param node the object
   * @param name the member
   * @return its value, or {@code null} when absent or not an integer
   */
  private static @Nullable Integer integer(@NotNull JsonNode node, @NotNull String name) {
    JsonNode value = node.get(name);
    return value != null && value.isInt() ? value.intValue() : null;
  }

  /**
   * A registry and when it was read.
   *
   * @param registry the registry
   * @param readAt the read time
   */
  private record Cached(@NotNull ExchangeRegistry registry, @NotNull Instant readAt) {}
}
