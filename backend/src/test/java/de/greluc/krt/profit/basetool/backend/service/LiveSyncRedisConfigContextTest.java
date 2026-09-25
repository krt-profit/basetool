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

package de.greluc.krt.profit.basetool.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.support.LiveSyncFanoutProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Verifies that the context starts with the {@link LiveSyncRedisConfig} fan-out enabled while Redis
 * refuses every connection, with the listener container's auto-start left on.
 */
class LiveSyncRedisConfigContextTest {

  /**
   * Starts the enabled configuration against a connection factory that always refuses, and asserts
   * the context comes up anyway with the fan-out container still present.
   *
   * <p>The second assertion is what rules out the cheap fix: simply not creating (or disabling) the
   * container would satisfy "the context starts" while silently ending cross-instance live sync.
   */
  @Test
  void contextStarts_whenRedisRefusesTheSubscription() {
    RedisConnectionFactory refusing = mock(RedisConnectionFactory.class);
    when(refusing.getConnection())
        .thenThrow(new RedisConnectionFailureException("Connection refused"));

    new ApplicationContextRunner()
        .withPropertyValues("app.live-sync.redis-fanout.enabled=true")
        .withBean(LiveSyncStreamService.class, () -> mock(LiveSyncStreamService.class))
        .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
        .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
        .withBean(
            LiveSyncFanoutProperties.class,
            () -> new LiveSyncFanoutProperties(true, "basetool:livesync:published"))
        .withBean(RedisConnectionFactory.class, () -> refusing)
        .withUserConfiguration(LiveSyncRedisConfig.class)
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(RedisMessageListenerContainer.class));
  }
}
