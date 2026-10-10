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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import java.time.Clock;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Mirrors the registry as one JSON document under one {@code exchange:} key (REQ-XCH-003). */
@Slf4j
@RequiredArgsConstructor
public class RedisExchangeRegistryMirror implements ExchangeRegistryMirror {

  /** The template the document is written and read with. */
  @NotNull private final StringRedisTemplate redisTemplate;

  /** The key the document lives under. */
  @NotNull private final String key;

  /** The clock that stamps {@code writtenAt}. */
  @NotNull private final Clock clock;

  /** The mapper for the document. */
  @NotNull private final JsonMapper jsonMapper = JsonMapper.builder().build();

  /**
   * Always {@code true}: this mirror stores to Redis.
   *
   * @return {@code true}
   */
  @Override
  public boolean isActive() {
    return true;
  }

  /**
   * Writes the document with a plain {@code SET}, replacing any earlier one.
   *
   * @param snapshot the registry content
   * @param revision the revision the document carries
   */
  @Override
  public void write(@NotNull ExchangeRegistrySnapshot snapshot, long revision) {
    ExchangeRegistryMirrorDocument document =
        new ExchangeRegistryMirrorDocument(
            ExchangeRegistryMirrorDocument.SCHEMA_VERSION,
            revision,
            clock.instant(),
            snapshot.enabled(),
            snapshot.clients());
    redisTemplate.opsForValue().set(key, jsonMapper.writeValueAsString(document));
  }

  /**
   * Reads and parses the document; an unparseable document counts as absent, so the reconcile
   * overwrites it.
   *
   * @return the mirrored content, or empty
   */
  @NotNull
  @Override
  public Optional<ExchangeRegistrySnapshot> read() {
    String raw = redisTemplate.opsForValue().get(key);
    if (raw == null) {
      return Optional.empty();
    }
    try {
      ExchangeRegistryMirrorDocument document =
          jsonMapper.readValue(raw, ExchangeRegistryMirrorDocument.class);
      if (document.schemaVersion() != ExchangeRegistryMirrorDocument.SCHEMA_VERSION) {
        return Optional.empty();
      }
      return Optional.of(document.snapshot());
    } catch (JacksonException _) {
      log.warn("The exchange registry mirror under {} is unreadable and will be rewritten", key);
      return Optional.empty();
    }
  }
}
