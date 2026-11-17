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

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.resource.ClientResources;
import java.time.Duration;
import org.jetbrains.annotations.NotNull;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.util.StringUtils;

/**
 * Builds the frontend's connection to the shared Redis (ADR-0388) from {@code app.shared-redis.*},
 * with the command and connect timeouts of {@code spring.data.redis.*} and the auto-configured
 * Lettuce client resources, so both stores share one event loop, its metrics and its tracing.
 */
@Configuration(proxyBeanMethods = false)
public class SharedRedisConfig {

  /**
   * Provides the connection to the shared Redis.
   *
   * @param shared where the shared Redis is and whom the frontend authenticates as
   * @param sessionStore the session store's settings, supplying the timeouts
   * @param clientResources the Lettuce client resources of the auto-configured connection
   * @return the connection, started but not yet connected
   */
  @Bean
  public @NotNull SharedRedis sharedRedis(
      @NotNull SharedRedisProperties shared,
      @NotNull DataRedisProperties sessionStore,
      @NotNull ClientResources clientResources) {
    return new SharedRedis(connectionFactory(shared, sessionStore, clientResources));
  }

  /**
   * Builds an unstarted factory for a standalone Redis.
   *
   * @param shared the server and its credentials; a blank username or password is left unset
   * @param sessionStore the settings supplying the command and connect timeouts, each optional
   * @param clientResources the Lettuce client resources to share
   * @return the factory
   */
  static @NotNull LettuceConnectionFactory connectionFactory(
      @NotNull SharedRedisProperties shared,
      @NotNull DataRedisProperties sessionStore,
      @NotNull ClientResources clientResources) {
    RedisStandaloneConfiguration server =
        new RedisStandaloneConfiguration(shared.host(), shared.port());
    if (StringUtils.hasText(shared.username())) {
      server.setUsername(shared.username());
    }
    if (StringUtils.hasText(shared.password())) {
      server.setPassword(shared.password());
    }
    ClientOptions.Builder options =
        ClientOptions.builder().timeoutOptions(TimeoutOptions.enabled());
    Duration connectTimeout = sessionStore.getConnectTimeout();
    if (connectTimeout != null) {
      options.socketOptions(SocketOptions.builder().connectTimeout(connectTimeout).build());
    }
    LettuceClientConfiguration.LettuceClientConfigurationBuilder client =
        LettuceClientConfiguration.builder()
            .clientResources(clientResources)
            .clientOptions(options.build());
    Duration commandTimeout = sessionStore.getTimeout();
    if (commandTimeout != null) {
      client.commandTimeout(commandTimeout);
    }
    return new LettuceConnectionFactory(server, client.build());
  }
}
