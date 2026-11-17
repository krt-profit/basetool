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

import de.greluc.krt.profit.basetool.frontend.kernel.backend.CatalogueCacheEviction;
import de.greluc.krt.profit.basetool.frontend.kernel.observability.MetricNames;
import de.greluc.krt.profit.basetool.frontend.kernel.redis.SharedRedis;
import de.greluc.krt.profit.basetool.technical.redis.ResilientRedisMessageListenerContainer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Wires the cross-replica Redis pub/sub fan-out behind the live-sync relay (ADR-0094).
 *
 * <p>Active outside the {@code test} profile while {@code app.livesync.redis.enabled} is true;
 * otherwise the relay is local only. Each JVM gets a fresh instance id so it skips its own
 * looped-back publications.
 */
@Slf4j
@Configuration
@Profile("!test")
@ConditionalOnProperty(prefix = "app.livesync.redis", name = "enabled", matchIfMissing = true)
public class LiveSyncRedisConfig {

  /**
   * Builds the Redis fan-out bean, taking the handler through an {@link ObjectProvider} to break
   * the construction cycle between the two.
   *
   * @param sharedRedis the connection to the shared Redis the fan-out publishes on (ADR-0388)
   * @param handlerProvider lazy provider of the relay handler
   * @param meterRegistry registry the fan-out counters bind to
   * @param liveSyncProperties the live-sync settings supplying the channel name
   * @param catalogueCacheEviction clears source snapshots before browser refreshes
   * @return the Redis fan-out bean
   */
  @NotNull
  @Bean
  public RedisLiveSyncFanout redisLiveSyncFanout(
      SharedRedis sharedRedis,
      ObjectProvider<LiveSyncWebSocketHandler> handlerProvider,
      MeterRegistry meterRegistry,
      LiveSyncProperties liveSyncProperties,
      CatalogueCacheEviction catalogueCacheEviction) {
    String instanceId = UUID.randomUUID().toString();
    log.info(
        "Live-sync Redis fan-out enabled (channel={}, presenceChannel={}, instanceId={})",
        liveSyncProperties.redis().channel(),
        liveSyncProperties.redis().presenceChannel(),
        instanceId);
    return new RedisLiveSyncFanout(
        sharedRedis.template(),
        handlerProvider,
        meterRegistry,
        catalogueCacheEviction,
        liveSyncProperties.redis().channel(),
        liveSyncProperties.redis().presenceChannel(),
        instanceId);
  }

  /**
   * Bounded dispatch executor for consumed Redis messages. A full queue runs the dispatch on the
   * container's own thread ({@link ThreadPoolExecutor.CallerRunsPolicy}), so messages are never
   * dropped and threads never grow without bound.
   *
   * @return the bounded listener dispatch executor (shut down with the context)
   */
  @NotNull
  @Bean(destroyMethod = "shutdown")
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
   * Subscribes the Redis fan-out to both the {@code changed} relay channel (ADR-0094) and the
   * editor-presence channel (ADR-0126), dispatching on {@link #liveSyncRedisListenerExecutor()}.
   *
   * <p>The container starts unsubscribed and retries in the background when the shared Redis is
   * unreachable, so the frontend — and sign-in — starts without it (ADR-0143, ADR-0388); {@code
   * basetool_redis_fanout_subscribed{fanout="livesync"}} reports the subscription.
   *
   * @param sharedRedis the connection to the shared Redis the channels live on (ADR-0388)
   * @param fanout the Redis fan-out (also the message listener for both channels)
   * @param listenerExecutor the bounded dispatch executor, qualified by bean name
   * @param meterRegistry registry the subscription gauge binds to
   * @return the message-listener container
   */
  @NotNull
  @Bean
  public RedisMessageListenerContainer liveSyncRedisMessageListenerContainer(
      SharedRedis sharedRedis,
      RedisLiveSyncFanout fanout,
      @Qualifier("liveSyncRedisListenerExecutor") ThreadPoolTaskExecutor listenerExecutor,
      MeterRegistry meterRegistry) {
    ResilientRedisMessageListenerContainer container = new ResilientRedisMessageListenerContainer();
    container.setConnectionFactory(sharedRedis.connectionFactory());
    container.setTaskExecutor(listenerExecutor);
    container.addMessageListener(fanout, new ChannelTopic(fanout.channel()));
    container.addMessageListener(fanout, new ChannelTopic(fanout.presenceChannel()));
    Gauge.builder(MetricNames.REDIS_FANOUT_SUBSCRIBED, container, c -> c.isListening() ? 1d : 0d)
        .tag(MetricNames.TAG_FANOUT, MetricNames.FANOUT_LIVESYNC)
        .description("1 while this instance is subscribed to the shared Redis's live-sync channels")
        .register(meterRegistry);
    return container;
  }
}
