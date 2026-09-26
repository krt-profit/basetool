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

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.support.LiveSyncFanoutProperties;
import de.greluc.krt.profit.basetool.backend.support.ResilientRedisMessageListenerContainer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Wires the app live-sync fan-out (ADR-0143): the Redis bridge when {@code
 * app.live-sync.redis-fanout.enabled=true}, otherwise the local no-op, so the backend starts
 * without Redis (ADR-0084).
 */
@Slf4j
@Configuration
public class LiveSyncRedisConfig {

  /**
   * The Redis bridge, when it is switched on.
   *
   * @param streamService the local emitter registry a consumed frame is delivered to
   * @param redisTemplate the string template used to publish
   * @param meterRegistry registry the counters bind to
   * @param properties the channel and the on/off switch
   * @return the bridge, registered both as the fan-out and as the channel's message listener
   */
  @NotNull
  @Bean
  @ConditionalOnProperty(
      prefix = "app.live-sync.redis-fanout",
      name = "enabled",
      havingValue = "true")
  public RedisLiveSyncFanout redisLiveSyncFanout(
      LiveSyncStreamService streamService,
      StringRedisTemplate redisTemplate,
      MeterRegistry meterRegistry,
      LiveSyncFanoutProperties properties) {
    String instanceId = UUID.randomUUID().toString();
    log.info(
        "App live-sync Redis bridge enabled (channel={}, instanceId={})",
        properties.channel(),
        instanceId);
    return new RedisLiveSyncFanout(
        streamService, redisTemplate, meterRegistry, properties.channel(), instanceId);
  }

  /**
   * The fallback fan-out, used whenever the Redis bridge is absent.
   *
   * @return a fan-out that carries nothing beyond this JVM
   */
  @NotNull
  @Bean
  @ConditionalOnMissingBean(LiveSyncFanout.class)
  public LiveSyncFanout localLiveSyncFanout() {
    log.info("App live-sync Redis bridge disabled — frames stay on this instance");
    return new LocalLiveSyncFanout();
  }

  /**
   * The bounded, caller-runs listener pool the Redis container dispatches consumed frames on.
   *
   * @return the initialised executor
   */
  @NotNull
  @Bean(destroyMethod = "shutdown")
  @ConditionalOnProperty(
      prefix = "app.live-sync.redis-fanout",
      name = "enabled",
      havingValue = "true")
  public ThreadPoolTaskExecutor liveSyncRedisListenerExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(4);
    executor.setMaxPoolSize(16);
    executor.setQueueCapacity(2000);
    executor.setThreadNamePrefix("livesync-redis-");
    executor.setDaemon(true);
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    executor.initialize();
    return executor;
  }

  /**
   * Subscribes the bridge to its channel through a {@link ResilientRedisMessageListenerContainer},
   * so a failed first subscription does not abort context startup.
   *
   * @param connectionFactory the Redis connection factory
   * @param fanout the bridge, acting as the message listener
   * @param listenerExecutor the bounded pool messages are dispatched on
   * @param meterRegistry registry the subscription gauge binds to
   * @return the listener container
   */
  @NotNull
  @Bean
  @ConditionalOnProperty(
      prefix = "app.live-sync.redis-fanout",
      name = "enabled",
      havingValue = "true")
  public RedisMessageListenerContainer liveSyncRedisMessageListenerContainer(
      RedisConnectionFactory connectionFactory,
      RedisLiveSyncFanout fanout,
      @Qualifier("liveSyncRedisListenerExecutor") ThreadPoolTaskExecutor listenerExecutor,
      MeterRegistry meterRegistry) {
    ResilientRedisMessageListenerContainer container = new ResilientRedisMessageListenerContainer();
    container.setConnectionFactory(connectionFactory);
    container.setTaskExecutor(listenerExecutor);
    container.addMessageListener(fanout, new ChannelTopic(fanout.channel()));
    Gauge.builder(MetricNames.REDIS_FANOUT_SUBSCRIBED, container, c -> c.isListening() ? 1d : 0d)
        .tag(MetricNames.TAG_FANOUT, MetricNames.FANOUT_LIVESYNC)
        .description("1 while this instance is subscribed to the live-sync fan-out channel")
        .register(meterRegistry);
    return container;
  }
}
