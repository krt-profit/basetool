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
import de.greluc.krt.profit.basetool.frontend.service.MissionPresenceService;
import de.greluc.krt.profit.basetool.frontend.websocket.MissionPresenceHandshakeAuthInterceptor;
import de.greluc.krt.profit.basetool.frontend.websocket.MissionPresenceWebSocketHandler;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the mission-detail presence WebSocket endpoint.
 *
 * <p>Exposes {@code /ws/missions/{missionId}/presence} as a native Spring WebSocket endpoint. The
 * mission id is path-templated so a single handler instance serves every mission; per-mission
 * session sets live inside the handler.
 *
 * <p>The WebSocket handshake is gated by an explicit {@code setAllowedOriginPatterns} list (driven
 * by {@code app.websocket.allowed-origin-patterns}) — {@code setAllowedOriginPatterns("*")} would
 * leave the door open for Cross-Site WebSocket Hijacking even though the Spring Security chain in
 * {@link SecurityConfig} already requires authentication: the browser would still ship the victim's
 * session cookie on the upgrade request, and the handshake would succeed as the victim for an
 * attacker page on a third-party origin. Default falls back to the production hostname ({@code
 * https://profit-base.online}) plus localhost variants for dev (audit finding H-7).
 *
 * <p>The handler is constructed directly here (not component-scanned), so it is given its own plain
 * Jackson 3 {@link JsonMapper} rather than an auto-wired bean. The presence wire format is a
 * minimal hand-built {@code {type, sections}} tree, so no extra modules are needed.
 *
 * <p>Beyond the origin allowlist and Spring Security's authentication requirement, the handshake is
 * gated by {@link MissionPresenceHandshakeAuthInterceptor} on actual mission access, so an
 * authenticated user cannot join a presence room for a mission they may not see (ADR-0031).
 */
@Configuration
@EnableWebSocket
public class MissionPresenceWebSocketConfig implements WebSocketConfigurer {

  private final MissionPresenceService presenceService;
  private final BackendApiClient backendApiClient;
  private final MeterRegistry meterRegistry;
  private final List<String> allowedOriginPatterns;

  /**
   * Constructor injection of the shared presence store, the backend client used by the handshake
   * authorization gate, the Micrometer registry for the handler's push-channel metrics, and the
   * WebSocket origin allowlist.
   *
   * @param presenceService in-memory presence store
   * @param backendApiClient client used by the handshake interceptor to authorize mission access
   * @param meterRegistry registry the handler binds its session gauge and relay counters to
   * @param allowedOriginPatterns origin patterns accepted on the WebSocket handshake; sourced from
   *     {@code app.websocket.allowed-origin-patterns} with a production default
   */
  public MissionPresenceWebSocketConfig(
      MissionPresenceService presenceService,
      BackendApiClient backendApiClient,
      MeterRegistry meterRegistry,
      @Value(
              "${app.websocket.allowed-origin-patterns:https://profit-base.online,https://localhost:18081,http://localhost:18081}")
          List<String> allowedOriginPatterns) {
    this.presenceService = presenceService;
    this.backendApiClient = backendApiClient;
    this.meterRegistry = meterRegistry;
    this.allowedOriginPatterns = allowedOriginPatterns;
  }

  /**
   * Builds the singleton {@link MissionPresenceWebSocketHandler}. Declared as a bean so Spring
   * triggers its {@code @PreDestroy} on shutdown.
   *
   * @return the handler bean
   */
  @Bean
  public MissionPresenceWebSocketHandler missionPresenceWebSocketHandler() {
    return new MissionPresenceWebSocketHandler(
        presenceService, JsonMapper.builder().build(), meterRegistry);
  }

  /** {@inheritDoc} */
  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry
        .addHandler(missionPresenceWebSocketHandler(), "/ws/missions/{missionId}/presence")
        .addInterceptors(new MissionPresenceHandshakeAuthInterceptor(backendApiClient))
        .setAllowedOriginPatterns(allowedOriginPatterns.toArray(new String[0]));
  }
}
