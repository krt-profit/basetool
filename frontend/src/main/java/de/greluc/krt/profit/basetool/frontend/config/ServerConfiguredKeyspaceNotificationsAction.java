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
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.session.data.redis.config.ConfigureRedisAction;

/**
 * The startup step Spring Session runs against Redis when the frontend authenticates as its own ACL
 * user: it proves the session store answers, and sends no {@code CONFIG} at all (REQ-SEC-068,
 * ADR-0207).
 *
 * <p>The {@code basetool-frontend} user is deliberately not granted {@code CONFIG}; the server
 * carries {@code --notify-keyspace-events Egx} on its own command line instead. Asking for {@code
 * CONFIG GET} anyway, as {@link TolerantKeyspaceNotificationsAction} does, is refused on every
 * start and each refusal lands in Redis's {@code ACL LOG} and in {@code
 * redis_acl_access_denied_cmd_total} — the counter {@code RedisAclDenials} sums, which then sat at
 * its threshold on every frontend restart and taught operators to ignore the alert that means "a
 * service's ACL is wrong" (observed on production 2026-09-25).
 *
 * <p>What the {@code CONFIG GET} also did — open a connection and fail the context refresh when the
 * session store cannot be reached or the credentials are wrong (ADR-0084: Redis is mandatory for
 * the frontend) — is kept by a {@code PING}, which the user's {@code +@connection} allows. Any
 * failure of it propagates unchanged.
 */
@Slf4j
@RequiredArgsConstructor
public final class ServerConfiguredKeyspaceNotificationsAction implements ConfigureRedisAction {

  /** The reply Redis gives a {@code PING} without an argument. */
  static final String PONG = "PONG";

  /**
   * The ACL user the frontend authenticates as ({@code spring.data.redis.username}), named in the
   * startup log line so an operator can match it against {@code ACL LOG} and {@code CLIENT LIST}. A
   * service account's name, never a person's.
   */
  private final @NotNull String username;

  /**
   * Sends one {@code PING} over the connection Spring Session opened for its startup step, and
   * nothing else.
   *
   * @param connection the connection Spring Session's keyspace-notification initializer hands over
   *     at startup and closes afterwards.
   * @throws DataAccessException when Redis cannot be reached, refuses the credentials, or times out
   *     — which fails the frontend's startup, exactly as the {@code CONFIG GET} of before did.
   * @throws IllegalStateException when Redis answers the {@code PING} with anything but {@code
   *     PONG}, which no healthy session store does.
   */
  @Override
  public void configure(@NotNull RedisConnection connection) {
    String reply = connection.ping();
    if (!PONG.equalsIgnoreCase(reply)) {
      throw new IllegalStateException(
          "The session store answered PING with '" + reply + "' instead of PONG");
    }
    log.info(
        "Session store reachable as Redis ACL user '{}'. No CONFIG is sent: keyspace notifications"
            + " come from the server's own --notify-keyspace-events, which must include Egx or"
            + " session expiry events are never delivered.",
        username);
  }
}
