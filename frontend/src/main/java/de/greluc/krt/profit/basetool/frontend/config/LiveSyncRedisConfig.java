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

package de.greluc.krt.profit.basetool.frontend.config;

import de.greluc.krt.profit.basetool.frontend.websocket.LiveSyncWebSocketHandler;
import de.greluc.krt.profit.basetool.frontend.websocket.RedisLiveSyncFanout;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Wires the cross-replica Redis pub/sub fan-out behind the live-sync relay (ADR-0093).
 *
 * <p>Active only outside the {@code test} profile (which runs no Redis) and only while {@code
 * app.livesync.redis.enabled} is true (default). When absent, {@code LiveSyncWebSocketConfig} falls
 * back to the no-op fan-out and the relay is purely local (single-instance) — a Redis outage
 * degrades to exactly that, because the handler always relays locally before publishing.
 *
 * <p>The {@link RedisMessageListenerContainer} subscribes the {@link RedisLiveSyncFanout} to the
 * configured channel; Lettuce reconnects the subscription automatically after a Redis restart. The
 * instance id is a fresh per-JVM UUID so an instance skips its own looped-back publications.
 */
@Slf4j
@Configuration
@Profile("!test")
@ConditionalOnProperty(prefix = "app.livesync.redis", name = "enabled", matchIfMissing = true)
public class LiveSyncRedisConfig {

  /**
   * Builds the Redis fan-out bean. The handler is injected lazily through an {@link ObjectProvider}
   * to break the construction cycle (the handler needs the fan-out to publish; the fan-out needs
   * the handler to deliver on consume).
   *
   * @param redisTemplate the auto-configured string Redis template
   * @param handlerProvider lazy provider of the relay handler
   * @param meterRegistry registry the fan-out counters bind to
   * @param liveSyncProperties the live-sync settings supplying the channel name
   * @return the Redis fan-out bean
   */
  @Bean
  public RedisLiveSyncFanout redisLiveSyncFanout(
      StringRedisTemplate redisTemplate,
      ObjectProvider<LiveSyncWebSocketHandler> handlerProvider,
      MeterRegistry meterRegistry,
      LiveSyncProperties liveSyncProperties) {
    String instanceId = UUID.randomUUID().toString();
    log.info(
        "Live-sync Redis fan-out enabled (channel={}, instanceId={})",
        liveSyncProperties.redis().channel(),
        instanceId);
    return new RedisLiveSyncFanout(
        redisTemplate,
        handlerProvider,
        meterRegistry,
        liveSyncProperties.redis().channel(),
        instanceId);
  }

  /**
   * Bounded dispatch executor for consumed {@code changed} messages (F3). Without an explicit
   * executor a {@link RedisMessageListenerContainer} defaults to a {@code SimpleAsyncTaskExecutor}
   * — a <b>new, unbounded thread per dispatched message</b> — so a cross-replica burst could spawn
   * threads without limit (the native-thread-OOM shape). This caps concurrency and, when the small
   * queue is full, runs the dispatch on the container's own thread ({@link
   * ThreadPoolExecutor.CallerRunsPolicy}) — backpressure, never an unbounded spawn and never a
   * dropped frame (a dropped relay would leave a peer stale until the next change).
   *
   * @return the bounded listener dispatch executor (shut down with the context)
   */
  @Bean(destroyMethod = "shutdown")
  public ThreadPoolTaskExecutor liveSyncRedisListenerExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(8);
    executor.setQueueCapacity(1000);
    executor.setThreadNamePrefix("livesync-redis-");
    executor.setDaemon(true);
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    executor.initialize();
    return executor;
  }

  /**
   * Subscribes the Redis fan-out to its channel so this instance relays peer replicas' {@code
   * changed} signals to its local rooms, dispatching consumed messages on the bounded {@link
   * #liveSyncRedisListenerExecutor()} rather than the default unbounded per-message executor (F3).
   *
   * @param connectionFactory the auto-configured Redis connection factory
   * @param fanout the Redis fan-out (also the message listener)
   * @param listenerExecutor the bounded dispatch executor
   * @return the message-listener container
   */
  @Bean
  public RedisMessageListenerContainer liveSyncRedisMessageListenerContainer(
      RedisConnectionFactory connectionFactory,
      RedisLiveSyncFanout fanout,
      ThreadPoolTaskExecutor listenerExecutor) {
    RedisMessageListenerContainer container = new RedisMessageListenerContainer();
    container.setConnectionFactory(connectionFactory);
    container.setTaskExecutor(listenerExecutor);
    container.addMessageListener(fanout, new ChannelTopic(fanout.channel()));
    return container;
  }
}
