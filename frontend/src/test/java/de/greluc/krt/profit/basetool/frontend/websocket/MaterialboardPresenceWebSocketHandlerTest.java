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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit coverage for {@link MaterialboardPresenceWebSocketHandler}: a {@code changed} frame is
 * relayed to every <b>other</b> open session, only the accept-listed {@code board} section key
 * crosses, and malformed / non-{@code changed} frames are ignored (the REQ-FE-010 accept-list
 * guard).
 */
class MaterialboardPresenceWebSocketHandlerTest {

  private final MaterialboardPresenceWebSocketHandler handler =
      new MaterialboardPresenceWebSocketHandler(JsonMapper.builder().build());

  private static WebSocketSession openSession() {
    WebSocketSession session = mock(WebSocketSession.class);
    lenient().when(session.isOpen()).thenReturn(true);
    return session;
  }

  @Test
  void relaysChangedToOtherOpenSessionsOnly() throws Exception {
    WebSocketSession origin = openSession();
    WebSocketSession peer = openSession();
    WebSocketSession closed = mock(WebSocketSession.class);
    when(closed.isOpen()).thenReturn(false);
    handler.afterConnectionEstablished(origin);
    handler.afterConnectionEstablished(peer);
    handler.afterConnectionEstablished(closed);

    handler.handleTextMessage(
        origin, new TextMessage("{\"type\":\"changed\",\"sections\":[\"board\"]}"));

    verify(peer).sendMessage(new TextMessage("{\"type\":\"changed\",\"sections\":[\"board\"]}"));
    verify(origin, never()).sendMessage(any());
    verify(closed, never()).sendMessage(any());
  }

  @Test
  void dropsNonAcceptlistedSectionKeys() throws Exception {
    WebSocketSession origin = openSession();
    WebSocketSession peer = openSession();
    handler.afterConnectionEstablished(origin);
    handler.afterConnectionEstablished(peer);

    handler.handleTextMessage(
        origin, new TextMessage("{\"type\":\"changed\",\"sections\":[\"secret\"]}"));

    verify(peer, never()).sendMessage(any());
  }

  @Test
  void ignoresMalformedAndOtherFrameTypes() throws Exception {
    WebSocketSession origin = openSession();
    WebSocketSession peer = openSession();
    handler.afterConnectionEstablished(origin);
    handler.afterConnectionEstablished(peer);

    handler.handleTextMessage(origin, new TextMessage("not json"));
    handler.handleTextMessage(origin, new TextMessage("{\"type\":\"focus\"}"));

    verify(peer, never()).sendMessage(any());
  }

  @Test
  void afterConnectionClosedRemovesSession() {
    WebSocketSession session = openSession();
    handler.afterConnectionEstablished(session);
    assertThat(handler.sessionCount()).isEqualTo(1);

    handler.afterConnectionClosed(session, CloseStatus.NORMAL);

    assertThat(handler.sessionCount()).isZero();
  }
}
