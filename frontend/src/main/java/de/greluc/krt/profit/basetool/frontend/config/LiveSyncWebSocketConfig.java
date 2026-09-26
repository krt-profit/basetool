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

import de.greluc.krt.profit.basetool.frontend.service.LiveSyncPresenceService;
import de.greluc.krt.profit.basetool.frontend.websocket.LiveSyncFanout;
import de.greluc.krt.profit.basetool.frontend.websocket.LiveSyncSubscriptionAuthorizer;
import de.greluc.krt.profit.basetool.frontend.websocket.LiveSyncSyncHandshakeInterceptor;
import de.greluc.krt.profit.basetool.frontend.websocket.LiveSyncWebSocketHandler;
import de.greluc.krt.profit.basetool.frontend.websocket.NoopLiveSyncFanout;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the live-sync WebSocket endpoint (REQ-FE-015, ADR-0094).
 *
 * <p>Registers {@link LiveSyncWebSocketHandler} on {@code /ws/sync}, with {@link
 * LiveSyncSyncHandshakeInterceptor} capturing the OAuth2 token and pin for per-subscribe
 * authorization, which runs on {@link #liveSyncSubscribeAuthExecutor()}. The handshake accepts only
 * the origins in {@code app.websocket.allowed-origin-patterns}, to prevent cross-site WebSocket
 * hijacking.
 */
@Configuration
@EnableWebSocket
public class LiveSyncWebSocketConfig implements WebSocketConfigurer {

  /**
   * Subscribe-authorization executor size: {@value} worker threads, sized so a reconnect storm does
   * not saturate the pool (ADR-0094).
   */
  private static final int SUBSCRIBE_AUTH_THREADS = 16;

  /** Bounded queue depth for pending subscribe-authorization probes. */
  private static final int SUBSCRIBE_AUTH_QUEUE = 2000;

  private final LiveSyncPresenceService presenceService;
  private final MeterRegistry meterRegistry;
  private final ObjectProvider<LiveSyncFanout> fanoutProvider;
  private final LiveSyncSubscriptionAuthorizer subscriptionAuthorizer;
  private final OAuth2AuthorizedClientRepository authorizedClientRepository;
  private final List<String> allowedOriginPatterns;

  /**
   * Creates the configuration; the fan-out is resolved lazily so a Redis binding is used when
   * present and the no-op fallback otherwise.
   *
   * @param presenceService in-memory editor-presence store
   * @param meterRegistry registry the handler binds its gauges and relay counters to
   * @param fanoutProvider lazy provider of the cross-replica fan-out (Redis when enabled)
   * @param subscriptionAuthorizer authorizes a multiplexed {@code /ws/sync} subscribe
   * @param authorizedClientRepository authorized-client store read at the {@code /ws/sync}
   *     handshake
   * @param allowedOriginPatterns origin patterns accepted on the WebSocket handshake, from {@code
   *     app.websocket.allowed-origin-patterns}
   */
  public LiveSyncWebSocketConfig(
      LiveSyncPresenceService presenceService,
      MeterRegistry meterRegistry,
      ObjectProvider<LiveSyncFanout> fanoutProvider,
      LiveSyncSubscriptionAuthorizer subscriptionAuthorizer,
      OAuth2AuthorizedClientRepository authorizedClientRepository,
      @Value(
              "${app.websocket.allowed-origin-patterns:https://profit-base.online,https://localhost:18081,http://localhost:18081}")
          List<String> allowedOriginPatterns) {
    this.presenceService = presenceService;
    this.meterRegistry = meterRegistry;
    this.fanoutProvider = fanoutProvider;
    this.subscriptionAuthorizer = subscriptionAuthorizer;
    this.authorizedClientRepository = authorizedClientRepository;
    this.allowedOriginPatterns = allowedOriginPatterns;
  }

  /**
   * Bounded thread pool that runs {@code /ws/sync} subscribe-authorization probes off the WebSocket
   * container threads. An {@code AbortPolicy} makes a full queue throw {@link
   * java.util.concurrent.RejectedExecutionException} so the handler fails that subscribe open (and
   * counts it) rather than blocking. Shut down on context close.
   *
   * @return the subscribe-authorization executor
   */
  @NotNull
  @Bean(destroyMethod = "shutdownNow")
  public ExecutorService liveSyncSubscribeAuthExecutor() {
    return new ThreadPoolExecutor(
        SUBSCRIBE_AUTH_THREADS,
        SUBSCRIBE_AUTH_THREADS,
        60L,
        TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(SUBSCRIBE_AUTH_QUEUE),
        runnable -> {
          Thread thread = new Thread(runnable, "livesync-subauth");
          thread.setDaemon(true);
          return thread;
        },
        new ThreadPoolExecutor.AbortPolicy());
  }

  /**
   * Builds the singleton {@link LiveSyncWebSocketHandler} with the registered {@link
   * LiveSyncFanout}, or a fresh {@link NoopLiveSyncFanout} when none exists.
   *
   * @return the handler bean
   */
  @NotNull
  @Bean
  public LiveSyncWebSocketHandler liveSyncWebSocketHandler() {
    LiveSyncFanout fanout = fanoutProvider.getIfAvailable(NoopLiveSyncFanout::new);
    return new LiveSyncWebSocketHandler(
        presenceService,
        fanout,
        JsonMapper.builder().build(),
        meterRegistry,
        subscriptionAuthorizer,
        liveSyncSubscribeAuthExecutor());
  }

  /** {@inheritDoc} */
  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    LiveSyncWebSocketHandler handler = liveSyncWebSocketHandler();
    String[] origins = allowedOriginPatterns.toArray(new String[0]);
    registry
        .addHandler(handler, "/ws/sync")
        .addInterceptors(new LiveSyncSyncHandshakeInterceptor(authorizedClientRepository))
        .setAllowedOriginPatterns(origins);
  }
}
