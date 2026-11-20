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

package de.greluc.krt.profit.basetool.ingest.store;

import org.jetbrains.annotations.NotNull;
import org.springframework.data.redis.connection.DecoratedRedisConnection;
import org.springframework.data.redis.connection.RedisConnection;

/**
 * A Redis connection that can close the native connections it borrowed from the pool, so the pool
 * replaces them rather than lending them again (ADR-0389).
 *
 * <p>Public because a {@code RedisTemplate} hands its callbacks a JDK proxy of the connection,
 * whose invocation handler calls this interface's method reflectively from Spring Data's package.
 */
public interface DiscardableConnection {

  /**
   * Closes every native connection this connection borrowed and has not released yet; they still go
   * back to the pool when this connection is released, and the pool's borrow test rejects them.
   */
  void discard();

  /**
   * Finds the discardable connection behind the connection a {@code RedisTemplate} hands to a
   * callback, which a {@code StringRedisTemplate} wraps in a {@link DecoratedRedisConnection}.
   *
   * @param connection the connection the callback received
   * @return the discardable connection
   * @throws IllegalStateException if the connection was not made by {@link RedisClientConfig}
   */
  static @NotNull DiscardableConnection behind(@NotNull RedisConnection connection) {
    RedisConnection target =
        connection instanceof DecoratedRedisConnection decorated
            ? decorated.getDelegate()
            : connection;
    if (target instanceof DiscardableConnection discardable) {
      return discardable;
    }
    throw new IllegalStateException(
        "The Redis connection "
            + target.getClass().getName()
            + " was not made by RedisClientConfig and cannot be discarded.");
  }
}
