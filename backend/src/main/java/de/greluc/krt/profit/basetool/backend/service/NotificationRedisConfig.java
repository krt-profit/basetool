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
import de.greluc.krt.profit.basetool.backend.support.NotificationFanoutProperties;
import de.greluc.krt.profit.basetool.backend.support.ResilientRedisMessageListenerContainer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Wires the Redis pub/sub fan-out behind the notification SSE push (ADR-0094).
 *
 * <p>Active only while {@code app.notifications.redis-fanout.enabled} is {@code true}; otherwise
 * {@link LocalNotificationFanout} applies. Redis is kept out of the readiness group, and each
 * instance skips its own publications via a per-JVM instance id.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(
    prefix = "app.notifications.redis-fanout",
    name = "enabled",
    havingValue = "true")
public class NotificationRedisConfig {

  /**
   * Builds the Redis notification fan-out bean.
   *
   * @param notificationStreamService the local SSE emitter registry
   * @param redisTemplate the auto-configured string Redis template
   * @param meterRegistry registry the fan-out counters bind to
   * @param properties the fan-out settings supplying the channel name
   * @return the Redis notification fan-out bean
   */
  @NotNull
  @Bean
  public RedisNotificationFanout redisNotificationFanout(
      NotificationStreamService notificationStreamService,
      StringRedisTemplate redisTemplate,
      MeterRegistry meterRegistry,
      NotificationFanoutProperties properties) {
    String instanceId = UUID.randomUUID().toString();
    log.info(
        "Notification Redis fan-out enabled (channel={}, instanceId={})",
        properties.channel(),
        instanceId);
    return new RedisNotificationFanout(
        notificationStreamService, redisTemplate, meterRegistry, properties.channel(), instanceId);
  }

  /**
   * Bounded dispatch executor for consumed notification messages.
   *
   * <p>When its queue is full, the dispatch runs on the container's thread ({@link
   * ThreadPoolExecutor.CallerRunsPolicy}) instead of spawning unbounded threads.
   *
   * @return the bounded listener dispatch executor, shut down with the context
   */
  @NotNull
  @Bean(destroyMethod = "shutdown")
  public ThreadPoolTaskExecutor notificationRedisListenerExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(4);
    executor.setMaxPoolSize(16);
    executor.setQueueCapacity(2000);
    executor.setThreadNamePrefix("notify-redis-");
    executor.setDaemon(true);
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    executor.initialize();
    return executor;
  }

  /**
   * Subscribes the Redis notification fan-out to its channel, dispatching on {@link
   * #notificationRedisListenerExecutor()}.
   *
   * @param connectionFactory the auto-configured Redis connection factory
   * @param fanout the Redis fan-out, also the message listener
   * @param listenerExecutor the bounded dispatch executor, qualified by bean name
   * @param meterRegistry registry the subscription gauge binds to
   * @return the message-listener container
   */
  @NotNull
  @Bean
  public RedisMessageListenerContainer notificationRedisMessageListenerContainer(
      RedisConnectionFactory connectionFactory,
      RedisNotificationFanout fanout,
      @Qualifier("notificationRedisListenerExecutor") ThreadPoolTaskExecutor listenerExecutor,
      MeterRegistry meterRegistry) {
    ResilientRedisMessageListenerContainer container = new ResilientRedisMessageListenerContainer();
    container.setConnectionFactory(connectionFactory);
    container.setTaskExecutor(listenerExecutor);
    container.addMessageListener(fanout, new ChannelTopic(fanout.channel()));
    Gauge.builder(MetricNames.REDIS_FANOUT_SUBSCRIBED, container, c -> c.isListening() ? 1d : 0d)
        .tag(MetricNames.TAG_FANOUT, MetricNames.FANOUT_NOTIFICATIONS)
        .description("1 while this instance is subscribed to the notification fan-out channel")
        .register(meterRegistry);
    return container;
  }
}
