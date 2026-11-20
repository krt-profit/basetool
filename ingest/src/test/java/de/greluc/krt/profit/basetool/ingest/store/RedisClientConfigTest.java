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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.resource.ClientResources;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionProvider;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;

/**
 * The gateway's connection factory runs every command at most once (ADR-0389): it never reconnects
 * or replays, shares no connection, its pool tests what it lends, and each connection can close the
 * native connection it borrowed.
 */
class RedisClientConfigTest {

  private final ClientResources clientResources = mock(ClientResources.class);

  @Test
  void aDroppedConnectionIsNeitherReconnectedNorReplayed() {
    LettuceConnectionFactory factory =
        RedisClientConfig.connectionFactory(new DataRedisProperties(), clientResources);

    ClientOptions options = factory.getClientConfiguration().getClientOptions().orElseThrow();
    assertThat(options.isAutoReconnect()).isFalse();
    assertThat(options.getDisconnectedBehavior())
        .isEqualTo(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
    assertThat(options.getTimeoutOptions().isTimeoutCommands()).isTrue();
  }

  @Test
  void noConnectionIsSharedAndThePoolTestsWhatItLends() {
    DataRedisProperties redis = new DataRedisProperties();
    redis.getLettuce().getPool().setMaxActive(16);
    redis.getLettuce().getPool().setMaxWait(Duration.ofSeconds(2));

    LettuceConnectionFactory factory = RedisClientConfig.connectionFactory(redis, clientResources);

    assertThat(factory.getShareNativeConnection()).isFalse();
    assertThat(factory.getClientConfiguration())
        .isInstanceOfSatisfying(
            LettucePoolingClientConfiguration.class,
            pooling -> {
              assertThat(pooling.getPoolConfig().getTestOnBorrow()).isTrue();
              assertThat(pooling.getPoolConfig().getMaxTotal()).isEqualTo(16);
              assertThat(pooling.getPoolConfig().getMaxWaitDuration())
                  .isEqualTo(Duration.ofSeconds(2));
            });
  }

  @Test
  void everyConnectionCanCloseTheNativeConnectionItBorrowed() {
    LettuceConnectionFactory factory =
        RedisClientConfig.connectionFactory(new DataRedisProperties(), clientResources);

    assertThat(factory)
        .isInstanceOfSatisfying(
            RedisClientConfig.DiscardableConnectionFactory.class,
            discardable ->
                assertThat(
                        discardable.doCreateLettuceConnection(
                            null, mock(LettuceConnectionProvider.class), 2_000L, 0))
                    .isInstanceOf(DiscardableConnection.class));
  }

  @Test
  void theTimeoutsAreBoundedEvenWhenUnset() {
    DataRedisProperties redis = new DataRedisProperties();

    LettuceConnectionFactory factory = RedisClientConfig.connectionFactory(redis, clientResources);

    assertThat(factory.getClientConfiguration().getCommandTimeout())
        .isEqualTo(RedisClientConfig.DEFAULT_TIMEOUT);
    assertThat(factory.getClientConfiguration().getClientOptions().orElseThrow().getSocketOptions())
        .satisfies(socket ->
            assertThat(socket.getConnectTimeout()).isEqualTo(RedisClientConfig.DEFAULT_TIMEOUT));
  }

  @Test
  void theServerAndTheUserComeFromTheProperties() {
    DataRedisProperties redis = new DataRedisProperties();
    redis.setHost("redis-shared");
    redis.setPort(6380);
    redis.setUsername("basetool-ingest");
    redis.setPassword("secret");
    redis.setTimeout(Duration.ofMillis(1500));

    LettuceConnectionFactory factory = RedisClientConfig.connectionFactory(redis, clientResources);

    assertThat(factory.getStandaloneConfiguration().getHostName()).isEqualTo("redis-shared");
    assertThat(factory.getStandaloneConfiguration().getPort()).isEqualTo(6380);
    assertThat(factory.getStandaloneConfiguration().getUsername()).isEqualTo("basetool-ingest");
    assertThat(factory.getStandaloneConfiguration().getPassword())
        .isEqualTo(RedisPassword.of("secret"));
    assertThat(factory.getClientConfiguration().getCommandTimeout())
        .isEqualTo(Duration.ofMillis(1500));
  }
}
