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

package de.greluc.krt.profit.basetool.ingest.assembly;

import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start the gateway under the {@code prod} profile unless it reaches Redis as its own
 * ACL user (REQ-SEC-068): with a blank {@code spring.data.redis.username} it would authenticate as
 * {@code default}, whose rules reach every key, the exchange registry and revocations included.
 */
@Component
@RequiredArgsConstructor
public class RedisUsernameGuard implements InitializingBean {

  /** The property the Redis connection's username is bound from. */
  static final String USERNAME_PROPERTY = "spring.data.redis.username";

  /** The Redis user every service must not authenticate as in production. */
  static final String DEFAULT_USER = "default";

  /** The environment whose profiles and Redis username the guard reads. */
  private final @NotNull Environment environment;

  /**
   * Checks the configuration once it is bound.
   *
   * @throws IllegalStateException when production would reach Redis as {@code default}
   */
  @Override
  public void afterPropertiesSet() {
    check();
  }

  /**
   * Refuses a blank or {@code default} Redis username under {@code prod}.
   *
   * @throws IllegalStateException when production would reach Redis as {@code default}
   */
  void check() {
    if (!environment.matchesProfiles("prod")) {
      return;
    }
    String username = environment.getProperty(USERNAME_PROPERTY, "").strip();
    if (username.isEmpty() || DEFAULT_USER.equals(username)) {
      throw new IllegalStateException(
          USERNAME_PROPERTY
              + " is empty or 'default' under the prod profile; set REDIS_INGEST_USERNAME to"
              + " basetool-ingest and REDIS_INGEST_PASSWORD to its password, so the gateway reaches"
              + " Redis as its own ACL user (REQ-SEC-068).");
    }
  }
}
