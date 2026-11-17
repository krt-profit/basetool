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

package de.greluc.krt.profit.basetool.frontend.kernel.livesync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.kernel.observability.MetricNames;
import de.greluc.krt.profit.basetool.frontend.kernel.redis.SharedRedis;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The frontend starts while the shared Redis is unreachable: the live-sync subscription comes up
 * unsubscribed and retries instead of failing the context, and its gauge reads 0 (ADR-0143,
 * ADR-0388).
 */
class LiveSyncRedisStartTest {

  /** A local port nothing listens on, so a connection is refused at once. */
  private static final int CLOSED_PORT = 1;

  private final LiveSyncRedisConfig config = new LiveSyncRedisConfig();
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final LettuceConnectionFactory unreachable =
      new LettuceConnectionFactory(new RedisStandaloneConfiguration("localhost", CLOSED_PORT));
  private final ThreadPoolTaskExecutor executor = config.liveSyncRedisListenerExecutor();
  private RedisMessageListenerContainer container;

  @AfterEach
  void tearDown() throws Exception {
    if (container != null) {
      container.destroy();
    }
    executor.shutdown();
    unreachable.destroy();
  }

  @Test
  void theLiveSyncContainerStartsUnsubscribedWhileTheSharedRedisIsUnreachable() throws Exception {
    unreachable.afterPropertiesSet();
    SharedRedis sharedRedis = mock(SharedRedis.class);
    when(sharedRedis.connectionFactory()).thenReturn(unreachable);
    RedisLiveSyncFanout fanout = mock(RedisLiveSyncFanout.class);
    when(fanout.channel()).thenReturn("basetool:livesync:changed");
    when(fanout.presenceChannel()).thenReturn("basetool:livesync:presence");

    container = config.liveSyncRedisMessageListenerContainer(sharedRedis, fanout, executor, meters);
    container.afterPropertiesSet();

    assertThatNoException().as("the context refresh survives").isThrownBy(container::start);
    assertThat(container.isListening()).isFalse();
    assertThat(
            meters
                .get(MetricNames.REDIS_FANOUT_SUBSCRIBED)
                .tag(MetricNames.TAG_FANOUT, MetricNames.FANOUT_LIVESYNC)
                .gauge()
                .value())
        .isZero();
  }
}
