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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
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

  @Test
  void brokenPeerIsEvictedFromRegistry() throws Exception {
    WebSocketSession origin = openSession();
    WebSocketSession brokenPeer = openSession();
    doThrow(new IOException("write failed")).when(brokenPeer).sendMessage(any());
    handler.afterConnectionEstablished(origin);
    handler.afterConnectionEstablished(brokenPeer);
    assertThat(handler.sessionCount()).isEqualTo(2);

    handler.handleTextMessage(
        origin, new TextMessage("{\"type\":\"changed\",\"sections\":[\"board\"]}"));

    // relay() catch block (IOException -> sessions.remove): a peer that fails its write is dropped
    // from the registry, so a half-broken session cannot linger forever inflating sessionCount()
    // (the board session gauge) and paying a failing send on every subsequent board change.
    verify(brokenPeer).sendMessage(any());
    assertThat(handler.sessionCount()).isEqualTo(1);
    // The surviving session is the origin: removing it empties the registry, proving the broken
    // peer (not the origin) was the one evicted.
    handler.afterConnectionClosed(origin, CloseStatus.NORMAL);
    assertThat(handler.sessionCount()).isZero();
  }

  @Test
  void materialboardAcceptlistMatchesClientSectionKey() throws Exception {
    Set<String> clientKeys = clientBroadcastSectionKeys();

    // REQ-FE-010 three-mirror-point parity: the board channel is a single stable key. If
    // materialboerse.js renames it (e.g. to "boardList") without updating the relay accept-list,
    // every board change frame is silently dropped and peers stop live-updating, undetected.
    assertThat(clientKeys).containsExactlyInAnyOrder("board");

    // Cross-check the server side behaviourally rather than re-stating its private accept-list:
    // every key the client actually broadcasts must survive the relay and reach a peer verbatim.
    for (String key : clientKeys) {
      String frame = "{\"type\":\"changed\",\"sections\":[\"" + key + "\"]}";
      MaterialboardPresenceWebSocketHandler relay =
          new MaterialboardPresenceWebSocketHandler(JsonMapper.builder().build());
      WebSocketSession origin = openSession();
      WebSocketSession peer = openSession();
      relay.afterConnectionEstablished(origin);
      relay.afterConnectionEstablished(peer);

      relay.handleTextMessage(origin, new TextMessage(frame));

      verify(peer).sendMessage(new TextMessage(frame));
    }
  }

  /**
   * Extracts the section keys the board client actually broadcasts, by reading {@code
   * static/js/materialboerse.js} and pulling every string literal passed to a {@code
   * sendChanged([...])} call.
   *
   * @return the distinct section keys the client hands to the presence relay.
   * @throws IOException if the JS module cannot be read from the classpath.
   */
  private static Set<String> clientBroadcastSectionKeys() throws IOException {
    String js = materialboerseModuleSource();
    Set<String> keys = new HashSet<>();
    Matcher call = Pattern.compile("sendChanged\\(\\s*\\[([^\\]]*)\\]").matcher(js);
    while (call.find()) {
      Matcher literal = Pattern.compile("['\"]([^'\"]+)['\"]").matcher(call.group(1));
      while (literal.find()) {
        keys.add(literal.group(1));
      }
    }
    return keys;
  }

  /**
   * Reads the Materialbörse board page module ({@code static/js/materialboerse.js}) from the
   * classpath so the client-side broadcast keys can be cross-checked against the relay accept-list.
   *
   * @return the raw UTF-8 source of the JS module.
   * @throws IOException if the module cannot be read from the classpath.
   */
  private static String materialboerseModuleSource() throws IOException {
    try (var in = new ClassPathResource("static/js/materialboerse.js").getInputStream()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
