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

import de.greluc.krt.profit.basetool.frontend.websocket.MaterialboardPresenceWebSocketHandler;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the Materialbörse board live-sync WebSocket endpoint (REQ-MARKET-010, ADR-0082 D4).
 *
 * <p>Exposes {@code /ws/materialboerse/board} as a native Spring WebSocket endpoint — one global
 * room served by a single {@link MaterialboardPresenceWebSocketHandler}. The WebSocket machinery is
 * enabled once by {@link MissionPresenceWebSocketConfig}'s {@code @EnableWebSocket}; this class
 * only contributes an additional {@link WebSocketConfigurer}, so it must <b>not</b> repeat the
 * annotation.
 *
 * <p>The handshake is pinned to the same explicit {@code app.websocket.allowed-origin-patterns}
 * allowlist as the mission endpoint (never {@code "*"}) to prevent Cross-Site WebSocket Hijacking,
 * and {@code SecurityConfig} additionally requires the joining session to be authenticated. Because
 * only the opaque {@code "board"} section key ever crosses the socket and each peer re-pulls its
 * own {@code KRT_MEMBER}-gated board fragment, no board data can leak through the channel itself.
 */
@Configuration
public class MaterialboardPresenceWebSocketConfig implements WebSocketConfigurer {

  private final List<String> allowedOriginPatterns;

  /**
   * Constructor injection of the WebSocket origin allowlist.
   *
   * @param allowedOriginPatterns origin patterns accepted on the WebSocket handshake; sourced from
   *     {@code app.websocket.allowed-origin-patterns} with a production default.
   */
  public MaterialboardPresenceWebSocketConfig(
      @Value(
              "${app.websocket.allowed-origin-patterns:https://profit-base.online,https://localhost:18081,http://localhost:18081}")
          List<String> allowedOriginPatterns) {
    this.allowedOriginPatterns = allowedOriginPatterns;
  }

  /**
   * Builds the singleton board relay handler.
   *
   * @return the handler bean.
   */
  @Bean
  public MaterialboardPresenceWebSocketHandler materialboardPresenceWebSocketHandler() {
    return new MaterialboardPresenceWebSocketHandler(JsonMapper.builder().build());
  }

  /** {@inheritDoc} */
  @Override
  public void registerWebSocketHandlers(
      @org.jetbrains.annotations.NotNull WebSocketHandlerRegistry registry) {
    registry
        .addHandler(materialboardPresenceWebSocketHandler(), "/ws/materialboerse/board")
        .setAllowedOriginPatterns(allowedOriginPatterns.toArray(new String[0]));
  }
}
