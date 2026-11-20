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

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulConnection;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.resource.ClientResources;
import java.time.Duration;
import java.util.Objects;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionProvider;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.util.StringUtils;

/**
 * The gateway's one connection factory to Redis, from {@code spring.data.redis.*}: every command
 * runs at most once (ADR-0389).
 *
 * <p>Lettuce's defaults reconnect a dropped connection and replay what it had not answered, which
 * would run the queued writes of an optimistic transaction outside it and twice. Here a dropped
 * connection stays closed and fails its commands; the pool tests each connection it lends and
 * replaces a closed one; no connection is shared, so a step's reads, watch and writes all run on
 * the connection it borrowed; and every connection is a {@link DiscardableConnection}, so a failed
 * step can close the connection it borrowed before the pool lends it again.
 */
@Configuration(proxyBeanMethods = false)
public class RedisClientConfig {

  /** The command and connect timeout when {@code spring.data.redis.timeout} is not set. */
  static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(2);

  /**
   * Provides the connection factory, which replaces Spring Boot's auto-configured one.
   *
   * @param redis {@code spring.data.redis.*}: the server, the user, the timeouts and the pool size
   * @param clientResources the Lettuce client resources, with their metrics and tracing
   * @return the factory
   */
  @Bean
  public @NotNull LettuceConnectionFactory redisConnectionFactory(
      @NotNull DataRedisProperties redis, @NotNull ClientResources clientResources) {
    return connectionFactory(redis, clientResources);
  }

  /**
   * Builds an unstarted connection factory to a standalone Redis.
   *
   * @param redis the server, the user, the timeouts and the pool size; a blank username or password
   *     is left unset, a missing timeout is {@link #DEFAULT_TIMEOUT}
   * @param clientResources the Lettuce client resources
   * @return the factory
   */
  static @NotNull LettuceConnectionFactory connectionFactory(
      @NotNull DataRedisProperties redis, @NotNull ClientResources clientResources) {
    RedisStandaloneConfiguration server =
        new RedisStandaloneConfiguration(redis.getHost(), redis.getPort());
    server.setDatabase(redis.getDatabase());
    if (StringUtils.hasText(redis.getUsername())) {
      server.setUsername(redis.getUsername());
    }
    if (StringUtils.hasText(redis.getPassword())) {
      server.setPassword(redis.getPassword());
    }
    Duration commandTimeout = Objects.requireNonNullElse(redis.getTimeout(), DEFAULT_TIMEOUT);
    Duration connectTimeout =
        Objects.requireNonNullElse(redis.getConnectTimeout(), DEFAULT_TIMEOUT);
    ClientOptions options =
        ClientOptions.builder()
            .autoReconnect(false)
            .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
            .timeoutOptions(TimeoutOptions.enabled())
            .socketOptions(SocketOptions.builder().connectTimeout(connectTimeout).build())
            .build();
    LettucePoolingClientConfiguration client =
        LettucePoolingClientConfiguration.builder()
            .poolConfig(pool(redis.getLettuce().getPool()))
            .clientResources(clientResources)
            .clientOptions(options)
            .commandTimeout(commandTimeout)
            .build();
    LettuceConnectionFactory factory = new DiscardableConnectionFactory(server, client);
    factory.setShareNativeConnection(false);
    return factory;
  }

  /**
   * Builds the pool that tests each connection before it lends it.
   *
   * @param pool {@code spring.data.redis.lettuce.pool.*}
   * @return the pool's configuration
   */
  private static @NotNull GenericObjectPoolConfig<StatefulConnection<?, ?>> pool(
      DataRedisProperties.@NotNull Pool pool) {
    GenericObjectPoolConfig<StatefulConnection<?, ?>> config = new GenericObjectPoolConfig<>();
    config.setMaxTotal(pool.getMaxActive());
    config.setMaxIdle(pool.getMaxIdle());
    config.setMinIdle(pool.getMinIdle());
    if (pool.getMaxWait() != null) {
      config.setMaxWait(pool.getMaxWait());
    }
    config.setTestOnBorrow(true);
    return config;
  }

  /**
   * A {@link LettuceConnectionFactory} whose connections are {@link DiscardableConnection
   * discardable}. They keep {@link LettuceConnection}'s default pipelining flush policy; the
   * gateway does not pipeline.
   */
  static final class DiscardableConnectionFactory extends LettuceConnectionFactory {

    /**
     * Creates the factory.
     *
     * @param server the standalone server
     * @param client the client configuration
     */
    DiscardableConnectionFactory(
        RedisStandaloneConfiguration server, LettuceClientConfiguration client) {
      super(server, client);
    }

    /**
     * Creates a connection that borrows its native connections through a tracking provider.
     *
     * @param sharedConnection the shared native connection, {@code null} when none is shared
     * @param connectionProvider the pooled provider the native connections come from
     * @param timeout the command timeout in milliseconds
     * @param database the database index
     * @return the discardable connection
     */
    @Override
    protected @NotNull LettuceConnection doCreateLettuceConnection(
        @Nullable StatefulRedisConnection<byte[], byte[]> sharedConnection,
        @NotNull LettuceConnectionProvider connectionProvider,
        long timeout,
        int database) {
      return new DiscardableLettuceConnection(
          sharedConnection,
          new DiscardableLettuceConnection.TrackingConnectionProvider(connectionProvider),
          timeout,
          database);
    }
  }
}
