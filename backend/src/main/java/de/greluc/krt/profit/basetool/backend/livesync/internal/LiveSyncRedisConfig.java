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

import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncFanout;
import de.greluc.krt.profit.basetool.backend.livesync.api.RedisLiveSyncFanout;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Wires the live-sync fan-out (ADR-0274): the Redis publisher when {@code
 * app.live-sync.redis-fanout.enabled=true}, otherwise the local no-op, so the backend starts
 * without Redis (ADR-0084).
 */
@Slf4j
@Configuration
public class LiveSyncRedisConfig {

  /**
   * The Redis publisher, when it is switched on.
   *
   * @param redisTemplate the string template used to publish
   * @param meterRegistry registry the counters bind to
   * @param properties the channel and the on/off switch
   * @return the publisher, registered as the fan-out
   */
  @NotNull
  @Bean
  @ConditionalOnProperty(
      prefix = "app.live-sync.redis-fanout",
      name = "enabled",
      havingValue = "true")
  public RedisLiveSyncFanout redisLiveSyncFanout(
      StringRedisTemplate redisTemplate,
      MeterRegistry meterRegistry,
      LiveSyncFanoutProperties properties) {
    String instanceId = UUID.randomUUID().toString();
    log.info(
        "Live-sync Redis publisher enabled (channel={}, instanceId={})",
        properties.channel(),
        instanceId);
    return new RedisLiveSyncFanout(redisTemplate, meterRegistry, properties.channel(), instanceId);
  }

  /**
   * The fallback fan-out, used whenever the Redis publisher is absent.
   *
   * @return a fan-out that carries nothing beyond this JVM
   */
  @NotNull
  @Bean
  @ConditionalOnMissingBean(LiveSyncFanout.class)
  public LiveSyncFanout localLiveSyncFanout() {
    log.info("Live-sync Redis publisher disabled — open pages will not see backend writes");
    return new LocalLiveSyncFanout();
  }
}
