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

package de.greluc.krt.profit.basetool.frontend.websocket;

import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Binds the legacy Materialbörse board WebSocket handshake ({@code /ws/materialboerse/board}) to
 * its implicit global {@code materialboard} topic (REQ-FE-015, ADR-0094).
 *
 * <p>Since the board migrated to the multiplexed {@code /ws/sync} relay, new page loads subscribe
 * to the {@code materialboard} room over {@code /ws/sync}; this interceptor keeps the pre-rollout
 * {@code /ws/materialboerse/board} socket working for one release so a tab opened before the deploy
 * does not silently lose its board sync. It simply stamps {@link
 * LiveSyncWebSocketHandler#ATTR_TOPIC} with the fixed {@code materialboard} topic so {@link
 * LiveSyncWebSocketHandler} auto-binds the socket to that already-authenticated global room — both
 * the legacy and the {@code /ws/sync} sockets share one {@code materialboard} room keyed by that
 * canonical string, so a change from either reaches the other.
 *
 * <p>Unlike the per-resource {@link LiveSyncLegacyHandshakeInterceptor}, the board room needs no
 * per-user backend probe: {@code SecurityConfig} already gates {@code /ws/materialboerse/**} to an
 * authenticated principal, and the room is authorized by authentication alone (the {@link
 * LiveSyncTopicClass#MATERIALBOARD} class carries no probe path or role gate). Only the opaque
 * {@code board} section key ever crosses the socket and every peer re-pulls its own {@code
 * KRT_MEMBER}-gated fragment, so binding the topic here leaks nothing.
 */
public class LiveSyncBoardLegacyHandshakeInterceptor implements HandshakeInterceptor {

  /** The fixed global topic every {@code /ws/materialboerse/board} socket is bound to. */
  static final String BOARD_TOPIC = "materialboard";

  /**
   * Binds the future WebSocket session to the fixed {@code materialboard} topic. Authentication is
   * already enforced by the security chain, so the handshake is always allowed here.
   *
   * @param request the handshake (HTTP upgrade) request (unused; the path is fixed by registration)
   * @param response the handshake response (unused; never refused here)
   * @param wsHandler the target handler (unused)
   * @param attributes the future WebSocket-session attributes; the fixed topic is stored here
   * @return {@code true} always — the security chain, not this interceptor, gates access
   */
  @Override
  public boolean beforeHandshake(
      @NotNull ServerHttpRequest request,
      @NotNull ServerHttpResponse response,
      @NotNull WebSocketHandler wsHandler,
      @NotNull Map<String, Object> attributes) {
    attributes.put(LiveSyncWebSocketHandler.ATTR_TOPIC, BOARD_TOPIC);
    return true;
  }

  /**
   * No-op: the binding decision is made entirely in {@link #beforeHandshake}.
   *
   * @param request the handshake request (unused)
   * @param response the handshake response (unused)
   * @param wsHandler the target handler (unused)
   * @param exception any handshake failure (unused)
   */
  @Override
  public void afterHandshake(
      @NotNull ServerHttpRequest request,
      @NotNull ServerHttpResponse response,
      @NotNull WebSocketHandler wsHandler,
      Exception exception) {
    // intentionally empty
  }
}
