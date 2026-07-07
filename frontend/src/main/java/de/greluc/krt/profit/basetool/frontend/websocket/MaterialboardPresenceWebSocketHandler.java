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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Materialbörse board live-sync relay (REQ-MARKET-010, ADR-0081 D4). One global room: every
 * authenticated viewer of {@code /materialboerse} joins {@code /ws/materialboerse/board}. When a
 * member's release / deactivate / interest write succeeds, its client sends a {@code
 * {"type":"changed","sections":["board"]}} frame; this relay fans that frame out to <b>every
 * other</b> connected client, which then re-pulls its own authorization-checked board fragment.
 *
 * <p>This is the middle of the REQ-FE-010 three-mirror-point contract: the acting client's
 * broadcast (materialboerse.js) ↔ this relay's {@link #BROADCASTABLE_SECTIONS} accept-list ↔ the
 * receiving client's apply map (materialboerse.js). Only the opaque section key {@code "board"}
 * crosses the socket — never offer data, an interessent identity, or an item location. Any section
 * key not in the accept-list is silently dropped, so a client cannot make peers pull an arbitrary
 * URL.
 *
 * <p>Deliberately far simpler than the mission presence relay: the board is a single shared surface
 * with no per-room scoping and no editor-presence counts, so there is no presence store, no
 * snapshot frame and no per-mission session map — just change propagation.
 */
@Slf4j
public class MaterialboardPresenceWebSocketHandler extends TextWebSocketHandler {

  /** The only section key that may cross the socket (REQ-FE-010 accept-list). */
  private static final Set<String> BROADCASTABLE_SECTIONS = Set.of("board");

  /** Upper bound on section keys accepted per frame (a single-key board channel needs very few). */
  private static final int MAX_CHANGED_SECTIONS = 4;

  private final JsonMapper objectMapper;

  /** Live board sessions across the whole org (one global room). */
  private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

  /**
   * Constructs the handler with a plain Jackson mapper for the minimal {@code {type, sections}}
   * wire format.
   *
   * @param objectMapper the JSON mapper used to read inbound frames and serialise relayed frames.
   */
  public MaterialboardPresenceWebSocketHandler(@NotNull JsonMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /**
   * Registers a newly connected board viewer.
   *
   * @param session the opened session.
   */
  @Override
  public void afterConnectionEstablished(@NotNull WebSocketSession session) {
    sessions.add(session);
  }

  /**
   * Unregisters a disconnected board viewer.
   *
   * @param session the closed session.
   * @param status the close reason (unused).
   */
  @Override
  public void afterConnectionClosed(
      @NotNull WebSocketSession session, @NotNull CloseStatus status) {
    sessions.remove(session);
  }

  /**
   * Relays a client's {@code "changed"} frame to every other board client, after filtering the
   * section keys against the accept-list. Any other frame type or a malformed payload is ignored.
   *
   * @param session the sending session.
   * @param message the inbound text frame.
   */
  @Override
  protected void handleTextMessage(
      @NotNull WebSocketSession session, @NotNull TextMessage message) {
    JsonNode node;
    try {
      node = objectMapper.readTree(message.getPayload());
    } catch (JacksonException e) {
      log.debug("Discarding malformed board presence message", e);
      return;
    }
    JsonNode typeNode = node.get("type");
    if (typeNode == null || !typeNode.isString() || !"changed".equals(typeNode.asString())) {
      return;
    }
    List<String> sections = acceptedSections(node.get("sections"));
    if (sections.isEmpty()) {
      return;
    }
    relay(session, sections);
  }

  /**
   * Filters an inbound {@code sections} array down to the accept-listed, de-duplicated, capped
   * keys.
   *
   * @param sectionsNode the inbound {@code sections} array node, possibly {@code null}.
   * @return the accepted section keys, never {@code null}.
   */
  private List<String> acceptedSections(JsonNode sectionsNode) {
    List<String> sections = new ArrayList<>();
    if (sectionsNode == null || !sectionsNode.isArray()) {
      return sections;
    }
    for (JsonNode element : sectionsNode) {
      if (sections.size() >= MAX_CHANGED_SECTIONS) {
        break;
      }
      if (element != null && element.isString()) {
        String key = element.asString();
        if (BROADCASTABLE_SECTIONS.contains(key) && !sections.contains(key)) {
          sections.add(key);
        }
      }
    }
    return sections;
  }

  /**
   * Serialises the accepted section keys into a {@code {"type":"changed","sections":[…]}} frame and
   * sends it to every open session except the origin.
   *
   * @param origin the sending session, excluded from the fan-out.
   * @param sections the accepted section keys.
   */
  private void relay(@NotNull WebSocketSession origin, @NotNull List<String> sections) {
    String payload;
    try {
      ObjectNode root = objectMapper.createObjectNode();
      root.put("type", "changed");
      ArrayNode sectionsArray = root.putArray("sections");
      for (String key : sections) {
        sectionsArray.add(key);
      }
      payload = objectMapper.writeValueAsString(root);
    } catch (JacksonException e) {
      log.warn("Failed to serialise Materialbörse board change relay", e);
      return;
    }
    TextMessage message = new TextMessage(payload);
    for (WebSocketSession session : List.copyOf(sessions)) {
      if (session == origin || !session.isOpen()) {
        continue;
      }
      try {
        synchronized (session) {
          session.sendMessage(message);
        }
      } catch (IOException e) {
        log.debug("Failed to relay board change to a session; dropping it", e);
        sessions.remove(session);
      }
    }
  }

  /**
   * The number of live board sessions — exposed for the config's session gauge and for testing.
   *
   * @return the current session count.
   */
  public int sessionCount() {
    return sessions.size();
  }
}
