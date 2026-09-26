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

package de.greluc.krt.profit.basetool.frontend.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.session.data.redis.config.ConfigureNotifyKeyspaceEventsAction;
import org.springframework.session.data.redis.config.ConfigureRedisAction;

/**
 * Enables Redis keyspace notifications at startup and carries on when the Redis user may not run
 * {@code CONFIG} (REQ-SEC-068, ADR-0207).
 *
 * <p>A {@code NOPERM} refusal is taken to mean the server already runs with {@code
 * --notify-keyspace-events Egx}; any other failure is rethrown, so an unreachable Redis still fails
 * startup. Used only with the shared {@code default} user; a dedicated ACL user gets {@link
 * ServerConfiguredKeyspaceNotificationsAction}.
 */
@Slf4j
@RequiredArgsConstructor
public final class TolerantKeyspaceNotificationsAction implements ConfigureRedisAction {

  /** Spring Session's own action, which this one runs first and only guards. */
  private final @NotNull ConfigureRedisAction delegate;

  /**
   * Creates the action around Spring Session's default {@link ConfigureNotifyKeyspaceEventsAction}.
   */
  public TolerantKeyspaceNotificationsAction() {
    this(new ConfigureNotifyKeyspaceEventsAction());
  }

  /**
   * Runs the delegate, and logs rather than fails when the ACL forbids {@code CONFIG}.
   *
   * @param connection the connection Spring Session hands over at startup.
   * @throws DataAccessException any failure that is not an ACL refusal.
   */
  @Override
  public void configure(@NotNull RedisConnection connection) {
    try {
      delegate.configure(connection);
    } catch (DataAccessException ex) {
      if (!isAclRefusal(ex)) {
        throw ex;
      }
      log.info(
          "The session store's Redis user may not run CONFIG (per-service ACL user). Keyspace"
              + " notifications are taken from the server's own --notify-keyspace-events, which"
              + " must include Egx or session expiry events are never delivered.");
    }
  }

  /**
   * Whether a failure is an ACL refusal, detected by the {@code NOPERM} code anywhere on the cause
   * chain.
   *
   * @param failure what the delegate threw
   * @return {@code true} when a message on the cause chain contains {@code NOPERM}
   */
  static boolean isAclRefusal(@Nullable Throwable failure) {
    for (Throwable cursor = failure; cursor != null; cursor = cursor.getCause()) {
      String message = cursor.getMessage();
      if (message != null && message.contains("NOPERM")) {
        return true;
      }
      if (cursor.getCause() == cursor) {
        break;
      }
    }
    return false;
  }
}
