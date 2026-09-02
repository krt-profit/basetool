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
 * Context-wiring guard for {@link LiveSyncRedisConfig} with the Redis fan-out <b>enabled</b> and
 * Redis <b>refusing every connection</b>.
 *
 * <p>This is the 2026-09-02 07:07:09Z production outage written down as a test: {@code
 * ApplicationContextException: Failed to start bean 'liveSyncRedisMessageListenerContainer'}. Redis
 * was recreated during a deploy; the backend restarted into that window, its listener container
 * threw out of {@code SmartLifecycle#start()} while subscribing, Spring cancelled the refresh, and
 * the backend crash-looped with no API for the frontend at all — because an <em>optional</em>
 * fan-out could not reach an optional dependency. {@link LiveSyncRedisConfig}'s own class Javadoc
 * had promised the opposite ("an optional external must never keep the container from starting")
 * since it was written.
 *
 * <p>Note what is deliberately <em>not</em> here: no {@code setAutoStartup(false)} post-processor.
 * The sibling {@code NotificationRedisConfigContextTest} installs one so it can check bean wiring
 * without touching Redis, and that is precisely why it could stay green while this failure mode
 * shipped. Auto-start is the mechanism under test.
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
