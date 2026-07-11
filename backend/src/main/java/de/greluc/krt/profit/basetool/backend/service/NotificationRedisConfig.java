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

import de.greluc.krt.profit.basetool.backend.support.NotificationFanoutProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;
import lombok.extern.slf4j.Slf4j;
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
 * Wires the cross-replica Redis pub/sub fan-out behind the notification SSE push (ADR-0093),
 * discharging the ADR-0016 single-instance follow-up.
 *
 * <p>Lives in the {@code service} package (not {@code config}) on purpose: its {@code @Bean}
 * methods reference {@code service} types ({@link RedisNotificationFanout}, {@link
 * NotificationStreamService}), and a {@code @Configuration} in {@code config} doing so would form a
 * {@code config → service → config} package cycle (the ArchUnit no-cycles invariant). Keeping the
 * wiring here makes those references intra-package.
 *
 * <p>Active only while {@code app.notifications.redis-fanout.enabled} is {@code true} (default
 * off). When off, {@link LocalNotificationFanout} delivers only to this instance's emitters — the
 * previous behaviour. The backend gains its first Redis dependency here; the Redis health indicator
 * is disabled ({@code management.health.redis.enabled=false}) and Redis is deliberately NOT added
 * to the readiness group, so an optional fan-out's Redis blip can never flip the container
 * unhealthy (the ADR-0084 lesson).
 *
 * <p>The {@link RedisMessageListenerContainer} subscribes the {@link RedisNotificationFanout} to
 * the channel; Lettuce reconnects the subscription automatically after a Redis restart. The
 * instance id is a fresh per-JVM UUID so an instance skips its own looped-back publications.
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
   * Bounded dispatch executor for consumed notification messages (F3). Without an explicit executor
   * a {@link RedisMessageListenerContainer} defaults to a {@code SimpleAsyncTaskExecutor} — a
   * <b>new, unbounded thread per dispatched message</b>. On this path the consume then does a
   * blocking {@code SseEmitter.send()} to each local subscriber, so a cross-replica burst to
   * slow/half-open SSE clients would otherwise spawn unbounded threads that each block until the
   * write drains — exactly the native-thread-OOM shape (pid cap) seen in the July incident. This
   * caps concurrency; when the queue is full the dispatch runs on the container's own thread
   * ({@link ThreadPoolExecutor.CallerRunsPolicy}) — backpressure rather than an unbounded spawn. A
   * dropped notification would only delay a badge until the REQ-NOTIF-006 polling fallback corrects
   * it, but backpressure avoids even that. Sized with generous headroom (F2/#1243) so the blocking
   * per-subscriber sends keep up at ≥200 concurrent users without falling back to container-thread
   * backpressure that would delay live notifications, while the bound still caps the pool far below
   * the native-thread-OOM shape.
   *
   * @return the bounded listener dispatch executor (shut down with the context)
   */
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
   * Subscribes the Redis notification fan-out to its channel so this instance delivers peer
   * replicas' signals to its local emitters, dispatching consumed messages on the bounded {@link
   * #notificationRedisListenerExecutor()} rather than the default unbounded per-message executor
   * (F3).
   *
   * @param connectionFactory the auto-configured Redis connection factory
   * @param fanout the Redis fan-out (also the message listener)
   * @param listenerExecutor the bounded dispatch executor — {@code @Qualifier}'d by bean name
   *     because the context holds several {@link ThreadPoolTaskExecutor} beans (the async
   *     uex/scWiki/import/notification/mail executors) and the parameter name alone cannot
   *     disambiguate them, so an un-qualified inject fails to start the context once the fan-out is
   *     enabled (the crash the disabled-by-default unit test never exercised)
   * @return the message-listener container
   */
  @Bean
  public RedisMessageListenerContainer notificationRedisMessageListenerContainer(
      RedisConnectionFactory connectionFactory,
      RedisNotificationFanout fanout,
      @Qualifier("notificationRedisListenerExecutor") ThreadPoolTaskExecutor listenerExecutor) {
    RedisMessageListenerContainer container = new RedisMessageListenerContainer();
    container.setConnectionFactory(connectionFactory);
    container.setTaskExecutor(listenerExecutor);
    container.addMessageListener(fanout, new ChannelTopic(fanout.channel()));
    return container;
  }
}
