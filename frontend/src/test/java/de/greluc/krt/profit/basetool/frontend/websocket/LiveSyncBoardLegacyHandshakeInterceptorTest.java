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
import static org.mockito.Mockito.mock;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.WebSocketHandler;

/**
 * Tests for {@link LiveSyncBoardLegacyHandshakeInterceptor}: the {@code /ws/materialboerse/board}
 * handshake always passes (authentication is enforced by the security chain) and binds the fixed
 * global {@code materialboard} topic so the generic handler auto-joins that room.
 */
class LiveSyncBoardLegacyHandshakeInterceptorTest {

  private LiveSyncBoardLegacyHandshakeInterceptor interceptor;
  private WebSocketHandler handler;

  @BeforeEach
  void setUp() {
    interceptor = new LiveSyncBoardLegacyHandshakeInterceptor();
    handler = mock(WebSocketHandler.class);
  }

  @Test
  void allowsHandshake_andBindsTheFixedMaterialboardTopic() {
    MockHttpServletRequest servletRequest =
        new MockHttpServletRequest("GET", "/ws/materialboerse/board");
    ServerHttpRequest request = new ServletServerHttpRequest(servletRequest);
    ServerHttpResponse response = new ServletServerHttpResponse(new MockHttpServletResponse());
    Map<String, Object> attributes = new HashMap<>();

    boolean allowed = interceptor.beforeHandshake(request, response, handler, attributes);

    assertThat(allowed).isTrue();
    assertThat(attributes.get(LiveSyncWebSocketHandler.ATTR_TOPIC)).isEqualTo("materialboard");
    // The bound topic parses to the global materialboard room, authorized by authentication alone.
    LiveSyncTopic topic =
        LiveSyncTopic.parse((String) attributes.get(LiveSyncWebSocketHandler.ATTR_TOPIC));
    assertThat(topic).isNotNull();
    assertThat(topic.topicClass()).isEqualTo(LiveSyncTopicClass.MATERIALBOARD);
  }
}
