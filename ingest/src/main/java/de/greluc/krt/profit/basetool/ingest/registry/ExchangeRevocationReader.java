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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads the revocations the backend mirrors, on every request and never through a cache: a denied
 * installation key and a client revocation per member (REQ-XCH-008).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExchangeRevocationReader {

  /** The key prefix of a denied installation key. */
  static final String DENY_PREFIX = "exchange:deny:";

  /** The key prefix of a client revocation. */
  static final String REVOKED_PREFIX = "exchange:revoked:";

  private final StringRedisTemplate redisTemplate;

  /**
   * Whether an installation key is denied.
   *
   * @param keyThumbprint the token's {@code cnf.jkt}
   * @return {@code true} when the key is on the deny list
   * @throws ExchangeUnavailableException if Redis cannot be read
   */
  public boolean isDenied(@NotNull String keyThumbprint) {
    return get(DENY_PREFIX + keyThumbprint) != null;
  }

  /**
   * Returns when a member last disconnected a client.
   *
   * @param clientId the client's Keycloak client id
   * @param member the member's subject
   * @return the revocation's epoch second, or {@code null} when there is none
   * @throws ExchangeUnavailableException if Redis cannot be read
   */
  public @Nullable Long revokedAt(@NotNull String clientId, @NotNull String member) {
    String value = get(REVOKED_PREFIX + clientId + ":" + member);
    if (value == null) {
      return null;
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      throw new ExchangeUnavailableException("A revocation entry is unreadable.", e);
    }
  }

  /**
   * Reads one key.
   *
   * @param key the key
   * @return its value, or {@code null}
   * @throws ExchangeUnavailableException if Redis cannot be read
   */
  private @Nullable String get(@NotNull String key) {
    try {
      return redisTemplate.opsForValue().get(key);
    } catch (RuntimeException e) {
      log.warn("Exchange revocation read failed: {}", e.getClass().getSimpleName());
      throw new ExchangeUnavailableException("The revocations cannot be read.", e);
    }
  }
}
