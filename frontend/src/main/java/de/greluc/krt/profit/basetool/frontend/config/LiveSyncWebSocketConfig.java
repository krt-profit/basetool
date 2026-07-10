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

import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.LiveSyncPresenceService;
import de.greluc.krt.profit.basetool.frontend.websocket.LiveSyncBoardLegacyHandshakeInterceptor;
import de.greluc.krt.profit.basetool.frontend.websocket.LiveSyncFanout;
import de.greluc.krt.profit.basetool.frontend.websocket.LiveSyncLegacyHandshakeInterceptor;
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
 * Wires the live-sync WebSocket endpoints (REQ-FE-015, ADR-0093).
 *
 * <p>Registers the shared {@link LiveSyncWebSocketHandler} on three paths: the two legacy
 * per-surface aliases {@code /ws/missions/{missionId}/presence} (the {@link
 * LiveSyncLegacyHandshakeInterceptor} authorizes the handshake and binds the socket to its implicit
 * {@code mission:{id}} topic) and {@code /ws/materialboerse/board} (the {@link
 * LiveSyncBoardLegacyHandshakeInterceptor} binds the fixed global {@code materialboard} topic) —
 * both keeping tabs opened before the {@code /ws/sync} rollout working for one release — and the
 * multiplexed {@code /ws/sync} (the {@link LiveSyncSyncHandshakeInterceptor} captures the OAuth2
 * token + pin for per-subscribe authorization). The subscribe-authorization probes run on a
 * dedicated bounded {@link #liveSyncSubscribeAuthExecutor()} thread pool so the WebSocket container
 * threads never block on a backend read.
 *
 * <p>The handshake is gated by an explicit {@code setAllowedOriginPatterns} list (driven by {@code
 * app.websocket.allowed-origin-patterns}) — {@code setAllowedOriginPatterns("*")} would leave the
 * door open for Cross-Site WebSocket Hijacking even though the Spring Security chain in {@code
 * SecurityConfig} already requires authentication: the browser would still ship the victim's
 * session cookie on the upgrade, and the handshake would succeed as the victim for an attacker page
 * on a third-party origin (audit finding H-7). Default falls back to the production hostname plus
 * localhost variants for dev.
 *
 * <p>The handler is constructed directly here (not component-scanned) so it is given its own plain
 * Jackson 3 {@link JsonMapper} rather than an auto-wired bean and so Spring triggers its
 * {@code @PreDestroy} on shutdown.
 */
@Configuration
@EnableWebSocket
public class LiveSyncWebSocketConfig implements WebSocketConfigurer {

  /**
   * Subscribe-authorization executor sizing: {@value} worker threads. Kept modest because each
   * probe is a single short backend read; the deploy-time reconnect storm (~600 subscribes spread
   * over the client's 1–30 s reconnect jitter) stays well within this pool plus its queue, and any
   * overflow fails the subscribe open rather than blocking (ADR-0093 capacity model).
   */
  private static final int SUBSCRIBE_AUTH_THREADS = 8;

  /**
   * Bounded queue depth for pending subscribe-authorization probes before saturation fails open.
   */
  private static final int SUBSCRIBE_AUTH_QUEUE = 500;

  private final LiveSyncPresenceService presenceService;
  private final BackendApiClient backendApiClient;
  private final MeterRegistry meterRegistry;
  private final ObjectProvider<LiveSyncFanout> fanoutProvider;
  private final LiveSyncSubscriptionAuthorizer subscriptionAuthorizer;
  private final OAuth2AuthorizedClientRepository authorizedClientRepository;
  private final List<String> allowedOriginPatterns;

  /**
   * Constructor injection of the shared presence store, the backend client used by the legacy
   * handshake gate, the Micrometer registry, the fan-out seam, the multiplexed subscribe
   * authorizer, the authorized-client store (read at the {@code /ws/sync} handshake to capture the
   * OAuth2 token) and the WebSocket origin allowlist. The fan-out is injected lazily via an {@link
   * ObjectProvider} so a Redis binding (when present) is used and the no-op fallback is created
   * only when none is registered — order-independent, no {@code @ConditionalOnMissingBean} and no
   * self-referential cycle.
   *
   * @param presenceService in-memory editor-presence store
   * @param backendApiClient client used by the legacy handshake interceptor to authorize access
   * @param meterRegistry registry the handler binds its gauges and relay counters to
   * @param fanoutProvider lazy provider of the cross-replica fan-out (Redis when enabled)
   * @param subscriptionAuthorizer authorizes a multiplexed {@code /ws/sync} subscribe
   * @param authorizedClientRepository authorized-client store read at the {@code /ws/sync}
   *     handshake
   * @param allowedOriginPatterns origin patterns accepted on the WebSocket handshake; sourced from
   *     {@code app.websocket.allowed-origin-patterns} with a production default
   */
  public LiveSyncWebSocketConfig(
      LiveSyncPresenceService presenceService,
      BackendApiClient backendApiClient,
      MeterRegistry meterRegistry,
      ObjectProvider<LiveSyncFanout> fanoutProvider,
      LiveSyncSubscriptionAuthorizer subscriptionAuthorizer,
      OAuth2AuthorizedClientRepository authorizedClientRepository,
      @Value(
              "${app.websocket.allowed-origin-patterns:https://profit-base.online,https://localhost:18081,http://localhost:18081}")
          List<String> allowedOriginPatterns) {
    this.presenceService = presenceService;
    this.backendApiClient = backendApiClient;
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
   * Builds the singleton {@link LiveSyncWebSocketHandler}. Declared as a bean so Spring triggers
   * its {@code @PreDestroy} on shutdown and so the Redis fan-out can inject it for consume-side
   * delivery. Uses the registered {@link LiveSyncFanout} if one exists (the Redis binding), else a
   * fresh {@link NoopLiveSyncFanout} (single-instance).
   *
   * @return the handler bean
   */
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
        .addHandler(handler, "/ws/missions/{missionId}/presence")
        .addInterceptors(new LiveSyncLegacyHandshakeInterceptor(backendApiClient))
        .setAllowedOriginPatterns(origins);
    registry
        .addHandler(handler, "/ws/materialboerse/board")
        .addInterceptors(new LiveSyncBoardLegacyHandshakeInterceptor())
        .setAllowedOriginPatterns(origins);
    registry
        .addHandler(handler, "/ws/sync")
        .addInterceptors(new LiveSyncSyncHandshakeInterceptor(authorizedClientRepository))
        .setAllowedOriginPatterns(origins);
  }
}
