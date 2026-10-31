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

package de.greluc.krt.profit.basetool.backend.livesync.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncFanout;
import de.greluc.krt.profit.basetool.backend.livesync.api.RedisLiveSyncFanout;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Which fan-out {@link LiveSyncRedisConfig} wires, and that the backend subscribes to nothing
 * (ADR-0274).
 */
class LiveSyncRedisConfigContextTest {

  /**
   * Builds a runner with the collaborators the configuration needs.
   *
   * @param enabled what {@code app.live-sync.redis-fanout.enabled} is set to
   * @return the configured runner
   */
  private static ApplicationContextRunner runner(boolean enabled) {
    return new ApplicationContextRunner()
        .withPropertyValues("app.live-sync.redis-fanout.enabled=" + enabled)
        .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
        .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
        .withBean(
            LiveSyncFanoutProperties.class,
            () -> new LiveSyncFanoutProperties(enabled, "basetool:livesync:changed"))
        .withUserConfiguration(LiveSyncRedisConfig.class);
  }

  @Test
  @DisplayName("the enabled configuration wires the Redis publisher and no listener container")
  void enabledWiresThePublisherOnly() {
    runner(true)
        .run(context ->
            assertThat(context)
                .hasNotFailed()
                .hasSingleBean(RedisLiveSyncFanout.class)
                .doesNotHaveBean(
                    org.springframework.data.redis.listener.RedisMessageListenerContainer.class));
  }

  @Test
  @DisplayName("the disabled configuration falls back to the local no-op, so the backend starts")
  void disabledFallsBackToTheLocalNoop() {
    runner(false)
        .run(context ->
            assertThat(context)
                .hasNotFailed()
                .doesNotHaveBean(RedisLiveSyncFanout.class)
                .getBean(LiveSyncFanout.class)
                .isInstanceOf(LocalLiveSyncFanout.class));
  }
}
