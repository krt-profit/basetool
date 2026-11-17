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

package de.greluc.krt.profit.basetool.frontend.kernel.redis;

import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The frontend's connection to the shared Redis: live sync's pub/sub and the ingest handoff pickup
 * (ADR-0388).
 *
 * <p>Spring Session keeps the auto-configured connection to the session store. This connection is
 * held here rather than published as a {@link RedisConnectionFactory} bean, because a second bean
 * of that type would switch the auto-configuration off and leave an unqualified injection free to
 * pick either store.
 */
public class SharedRedis implements DisposableBean {

  /** The started factory every connection to the shared Redis comes from. */
  private final @NotNull LettuceConnectionFactory connectionFactory;

  /** String commands and publishing on the shared Redis. */
  private final @NotNull StringRedisTemplate template;

  /**
   * Starts the factory and builds the template over it; no connection is opened until the first
   * command.
   *
   * @param connectionFactory a factory not yet started
   */
  SharedRedis(@NotNull LettuceConnectionFactory connectionFactory) {
    connectionFactory.afterPropertiesSet();
    this.connectionFactory = connectionFactory;
    this.template = new StringRedisTemplate(connectionFactory);
  }

  /**
   * Returns the connection factory, for a message-listener container subscribing on the shared
   * Redis.
   *
   * @return the started factory
   */
  public @NotNull RedisConnectionFactory connectionFactory() {
    return connectionFactory;
  }

  /**
   * Returns the string template for commands and publications on the shared Redis.
   *
   * @return the template
   */
  public @NotNull StringRedisTemplate template() {
    return template;
  }

  /** Closes every connection to the shared Redis and releases the client. */
  @Override
  public void destroy() {
    connectionFactory.destroy();
  }
}
