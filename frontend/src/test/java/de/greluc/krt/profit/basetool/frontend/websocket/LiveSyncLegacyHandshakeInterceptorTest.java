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

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
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
 * Tests for {@link LiveSyncLegacyHandshakeInterceptor} (ported from {@code
 * MissionPresenceHandshakeAuthInterceptorTest}): the handshake passes and binds the implicit {@code
 * mission:{id}} topic when the backend authorizes the mission read, is refused on an explicit
 * 403/404 (the membership gate), fails open on a transient backend error, and is rejected with 400
 * for a malformed path.
 */
class LiveSyncLegacyHandshakeInterceptorTest {

  private static final String URI_TEMPLATE = "/api/v1/missions/{id}";

  private BackendApiClient backendApiClient;
  private LiveSyncLegacyHandshakeInterceptor interceptor;
  private WebSocketHandler handler;

  @BeforeEach
  void setUp() {
    backendApiClient = mock(BackendApiClient.class);
    interceptor = new LiveSyncLegacyHandshakeInterceptor(backendApiClient);
    handler = mock(WebSocketHandler.class);
  }

  @Test
  void allowsHandshake_andBindsTopic_whenBackendAuthorizesTheMissionRead() {
    UUID missionId = UUID.randomUUID();
    when(backendApiClient.get(eq(URI_TEMPLATE), anyTypeRef(), any())).thenReturn(null);
    MockHttpServletResponse servletResponse = new MockHttpServletResponse();
    Map<String, Object> attributes = new HashMap<>();

    boolean allowed = invoke(missionId.toString(), servletResponse, attributes);

    assertThat(allowed).isTrue();
    assertThat(attributes.get(LiveSyncWebSocketHandler.ATTR_TOPIC))
        .isEqualTo("mission:" + missionId);
  }

  @Test
  void refusesHandshake_whenBackendDeniesAccess() {
    UUID missionId = UUID.randomUUID();
    when(backendApiClient.get(eq(URI_TEMPLATE), anyTypeRef(), any()))
        .thenThrow(new BackendServiceException("forbidden", null, 403));
    MockHttpServletResponse servletResponse = new MockHttpServletResponse();
    Map<String, Object> attributes = new HashMap<>();

    boolean allowed = invoke(missionId.toString(), servletResponse, attributes);

    assertThat(allowed).isFalse();
    assertThat(servletResponse.getStatus()).isEqualTo(403);
    assertThat(attributes).doesNotContainKey(LiveSyncWebSocketHandler.ATTR_TOPIC);
  }

  @Test
  void refusesHandshake_whenMissionNotFound() {
    UUID missionId = UUID.randomUUID();
    when(backendApiClient.get(eq(URI_TEMPLATE), anyTypeRef(), any()))
        .thenThrow(new BackendServiceException("not found", null, 404));
    MockHttpServletResponse servletResponse = new MockHttpServletResponse();

    boolean allowed = invoke(missionId.toString(), servletResponse, new HashMap<>());

    assertThat(allowed).isFalse();
    assertThat(servletResponse.getStatus()).isEqualTo(403);
  }

  @Test
  void allowsHandshake_whenBackendIsTransientlyUnavailable() {
    UUID missionId = UUID.randomUUID();
    when(backendApiClient.get(eq(URI_TEMPLATE), anyTypeRef(), any()))
        .thenThrow(new BackendServiceException("unavailable", null, 503));
    MockHttpServletResponse servletResponse = new MockHttpServletResponse();
    Map<String, Object> attributes = new HashMap<>();

    boolean allowed = invoke(missionId.toString(), servletResponse, attributes);

    // Fail-open: a backend blip must not silently kill presence for a legitimate viewer.
    assertThat(allowed).isTrue();
    assertThat(attributes.get(LiveSyncWebSocketHandler.ATTR_TOPIC))
        .isEqualTo("mission:" + missionId);
  }

  @Test
  void rejectsHandshake_whenPathHasNoMissionId() {
    MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/ws/missions");
    ServerHttpRequest request = new ServletServerHttpRequest(servletRequest);
    MockHttpServletResponse servletResponse = new MockHttpServletResponse();
    ServerHttpResponse response = new ServletServerHttpResponse(servletResponse);

    boolean allowed = interceptor.beforeHandshake(request, response, handler, new HashMap<>());

    assertThat(allowed).isFalse();
    assertThat(servletResponse.getStatus()).isEqualTo(400);
    verify(backendApiClient, never()).get(any(String.class), anyTypeRef(), any());
  }

  /**
   * Drives {@code beforeHandshake} for a canonical presence path carrying {@code missionId}.
   *
   * @param missionId the mission id segment to embed in the upgrade path
   * @param servletResponse the response whose status the interceptor may set
   * @param attributes the future-session attribute map the interceptor may populate
   * @return the interceptor's allow/deny decision
   */
  private boolean invoke(
      String missionId, MockHttpServletResponse servletResponse, Map<String, Object> attributes) {
    MockHttpServletRequest servletRequest =
        new MockHttpServletRequest("GET", "/ws/missions/" + missionId + "/presence");
    ServerHttpRequest request = new ServletServerHttpRequest(servletRequest);
    ServerHttpResponse response = new ServletServerHttpResponse(servletResponse);
    return interceptor.beforeHandshake(request, response, handler, attributes);
  }
}
