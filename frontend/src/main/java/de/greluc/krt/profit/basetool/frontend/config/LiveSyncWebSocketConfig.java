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
import de.greluc.krt.profit.basetool.frontend.websocket.LiveSyncFanout;
import de.greluc.krt.profit.basetool.frontend.websocket.LiveSyncLegacyHandshakeInterceptor;
import de.greluc.krt.profit.basetool.frontend.websocket.LiveSyncWebSocketHandler;
import de.greluc.krt.profit.basetool.frontend.websocket.NoopLiveSyncFanout;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the live-sync WebSocket endpoints (REQ-FE-015, ADR-0092).
 *
 * <p>Registers the shared {@link LiveSyncWebSocketHandler} on the legacy per-resource path {@code
 * /ws/missions/{missionId}/presence}; the {@link LiveSyncLegacyHandshakeInterceptor} authorizes the
 * handshake and binds the socket to its implicit {@code mission:{id}} topic. (The multiplexed
 * {@code /ws/sync} endpoint layers on in a later step.)
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

  private final LiveSyncPresenceService presenceService;
  private final BackendApiClient backendApiClient;
  private final MeterRegistry meterRegistry;
  private final ObjectProvider<LiveSyncFanout> fanoutProvider;
  private final List<String> allowedOriginPatterns;

  /**
   * Constructor injection of the shared presence store, the backend client used by the handshake
   * gate, the Micrometer registry, the fan-out seam and the WebSocket origin allowlist. The fan-out
   * is injected lazily via an {@link ObjectProvider} so a Redis binding (when present) is used and
   * the no-op fallback is created only when none is registered — order-independent, no
   * {@code @ConditionalOnMissingBean} and no self-referential cycle.
   *
   * @param presenceService in-memory editor-presence store
   * @param backendApiClient client used by the handshake interceptor to authorize resource access
   * @param meterRegistry registry the handler binds its gauges and relay counters to
   * @param fanoutProvider lazy provider of the cross-replica fan-out (Redis when enabled)
   * @param allowedOriginPatterns origin patterns accepted on the WebSocket handshake; sourced from
   *     {@code app.websocket.allowed-origin-patterns} with a production default
   */
  public LiveSyncWebSocketConfig(
      LiveSyncPresenceService presenceService,
      BackendApiClient backendApiClient,
      MeterRegistry meterRegistry,
      ObjectProvider<LiveSyncFanout> fanoutProvider,
      @Value(
              "${app.websocket.allowed-origin-patterns:https://profit-base.online,https://localhost:18081,http://localhost:18081}")
          List<String> allowedOriginPatterns) {
    this.presenceService = presenceService;
    this.backendApiClient = backendApiClient;
    this.meterRegistry = meterRegistry;
    this.fanoutProvider = fanoutProvider;
    this.allowedOriginPatterns = allowedOriginPatterns;
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
        presenceService, fanout, JsonMapper.builder().build(), meterRegistry);
  }

  /** {@inheritDoc} */
  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry
        .addHandler(liveSyncWebSocketHandler(), "/ws/missions/{missionId}/presence")
        .addInterceptors(new LiveSyncLegacyHandshakeInterceptor(backendApiClient))
        .setAllowedOriginPatterns(allowedOriginPatterns.toArray(new String[0]));
  }
}
