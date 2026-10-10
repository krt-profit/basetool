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
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Mirrors denials as {@code exchange:deny:<thumbprint>} and client revocations as {@code
 * exchange:revoked:<clientId>:<member>}, each holding the revocation's epoch second and expiring
 * with it (REQ-XCH-008).
 */
@RequiredArgsConstructor
public class RedisExchangeRevocationMirror implements ExchangeRevocationMirror {

  /** The key prefix of a denied installation key. */
  static final String DENY_PREFIX = "exchange:deny:";

  /** The key prefix of a client revocation. */
  static final String REVOKED_PREFIX = "exchange:revoked:";

  /** The template the keys are written and read with. */
  @NotNull private final StringRedisTemplate redisTemplate;

  /** The clock the remaining lifetime is measured with. */
  @NotNull private final Clock clock;

  /**
   * Always {@code true}.
   *
   * @return {@code true}
   */
  @Override
  public boolean isActive() {
    return true;
  }

  @Override
  public void deny(@NotNull String keyThumbprint, @NotNull Instant revokedAt) {
    write(DENY_PREFIX + keyThumbprint, revokedAt);
  }

  @Override
  public void revoke(@NotNull String clientId, @NotNull UUID member, @NotNull Instant revokedAt) {
    write(REVOKED_PREFIX + clientId + ":" + member, revokedAt);
  }

  @Override
  public boolean isDenied(@NotNull String keyThumbprint) {
    return redisTemplate.opsForValue().get(DENY_PREFIX + keyThumbprint) != null;
  }

  @Override
  public boolean isRevoked(
      @NotNull String clientId, @NotNull UUID member, @NotNull Instant revokedAt) {
    return String.valueOf(revokedAt.getEpochSecond())
        .equals(redisTemplate.opsForValue().get(REVOKED_PREFIX + clientId + ":" + member));
  }

  /**
   * Reads the revocation's epoch second.
   *
   * @param clientId the Keycloak client id
   * @param member the member
   * @return the revocation time, or {@code null} when none is mirrored
   * @throws IllegalStateException when the entry is not an epoch second
   */
  @Override
  public @Nullable Instant revokedAt(@NotNull String clientId, @NotNull UUID member) {
    String value = redisTemplate.opsForValue().get(REVOKED_PREFIX + clientId + ":" + member);
    if (value == null) {
      return null;
    }
    try {
      return Instant.ofEpochSecond(Long.parseLong(value));
    } catch (NumberFormatException e) {
      throw new IllegalStateException("A mirrored client revocation is unreadable", e);
    }
  }

  /**
   * Writes one entry with the lifetime left until {@link #RETENTION} after the revocation; an entry
   * already past it is not written.
   *
   * @param key the Redis key
   * @param revokedAt the revocation time
   */
  private void write(@NotNull String key, @NotNull Instant revokedAt) {
    Duration left = Duration.between(clock.instant(), revokedAt.plus(RETENTION));
    if (left.isNegative() || left.isZero()) {
      return;
    }
    redisTemplate.opsForValue().set(key, String.valueOf(revokedAt.getEpochSecond()), left);
  }
}
