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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.resource.ClientResources;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * The connection to the shared Redis takes its server and credentials from {@code
 * app.shared-redis.*} and its timeouts and client resources from the session store's connection
 * (ADR-0388).
 */
class SharedRedisConfigTest {

  private final ClientResources clientResources = mock(ClientResources.class);

  @Test
  void theServerAndTheCredentialsComeFromTheSharedRedisProperties() {
    LettuceConnectionFactory factory =
        SharedRedisConfig.connectionFactory(
            new SharedRedisProperties("redis-shared", 6380, "basetool-frontend", "secret"),
            new DataRedisProperties(),
            clientResources);

    assertThat(factory.getStandaloneConfiguration().getHostName()).isEqualTo("redis-shared");
    assertThat(factory.getStandaloneConfiguration().getPort()).isEqualTo(6380);
    assertThat(factory.getStandaloneConfiguration().getUsername()).isEqualTo("basetool-frontend");
    assertThat(factory.getStandaloneConfiguration().getPassword())
        .isEqualTo(RedisPassword.of("secret"));
  }

  @Test
  void aBlankUserAndPasswordAreLeftUnset() {
    LettuceConnectionFactory factory =
        SharedRedisConfig.connectionFactory(
            new SharedRedisProperties("localhost", 6379, " ", ""),
            new DataRedisProperties(),
            clientResources);

    assertThat(factory.getStandaloneConfiguration().getUsername()).isNull();
    assertThat(factory.getStandaloneConfiguration().getPassword()).isEqualTo(RedisPassword.none());
  }

  @Test
  void theTimeoutsAndTheClientResourcesAreTheSessionStores() {
    DataRedisProperties sessionStore = new DataRedisProperties();
    sessionStore.setTimeout(Duration.ofSeconds(2));
    sessionStore.setConnectTimeout(Duration.ofMillis(1500));

    LettuceConnectionFactory factory =
        SharedRedisConfig.connectionFactory(
            new SharedRedisProperties("localhost", 6379, null, null),
            sessionStore,
            clientResources);

    assertThat(factory.getClientConfiguration().getCommandTimeout())
        .isEqualTo(Duration.ofSeconds(2));
    assertThat(factory.getClientConfiguration().getClientResources()).contains(clientResources);
    assertThat(factory.getClientConfiguration().getClientOptions())
        .map(ClientOptions::getSocketOptions)
        .hasValueSatisfying(
            socket -> assertThat(socket.getConnectTimeout()).isEqualTo(Duration.ofMillis(1500)));
    assertThat(factory.getClientConfiguration().getClientOptions())
        .map(options -> options.getTimeoutOptions().isTimeoutCommands())
        .contains(true);
  }

  @Test
  void theDescriptionLeavesThePasswordOut() {
    assertThat(new SharedRedisProperties("redis-shared", 6379, "basetool-frontend", "secret"))
        .asString()
        .contains("redis-shared", "basetool-frontend")
        .doesNotContain("secret");
  }
}
