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

import de.greluc.krt.profit.basetool.frontend.model.dto.MissionDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Authorizes the legacy per-resource live-sync WebSocket handshakes and binds each socket to its
 * implicit topic (REQ-FE-015, ADR-0093).
 *
 * <p>The multiplexed {@code /ws/sync} socket authorizes each topic at {@code subscribe} time; the
 * legacy per-resource endpoints (today {@code /ws/missions/{id}/presence}) instead authorize at
 * handshake time and stuff the resolved canonical topic into {@link
 * LiveSyncWebSocketHandler#ATTR_TOPIC} so {@link LiveSyncWebSocketHandler} auto-binds the socket to
 * that already-authorized room. This keeps tabs opened before the {@code /ws/sync} rollout working
 * for one release.
 *
 * <p>Mirrors the mission-detail page's own authorization: it issues the same authenticated {@code
 * GET /api/v1/missions/{id}} the page does, so exactly the users who can load the page can open its
 * socket. Only an <em>explicit</em> backend 403/404 refuses the handshake; a transient failure
 * (5xx, timeout, circuit open) or any other error fails open so a backend blip never silently kills
 * presence — safe because no mission data crosses the socket and every downstream fragment re-fetch
 * re-authorizes per viewer. A path that names no known legacy topic is rejected with 400.
 */
@Slf4j
@RequiredArgsConstructor
public class LiveSyncLegacyHandshakeInterceptor implements HandshakeInterceptor {

  /** Response type for the authenticated {@code /api/v1/missions/{id}} access-probe read. */
  private static final ParameterizedTypeReference<MissionDto> MISSION_TYPE =
      new ParameterizedTypeReference<>() {};

  private final BackendApiClient backendApiClient;

  /**
   * Gates a legacy handshake: resolves the implicit topic from the upgrade URI, authorizes it, and
   * binds it to the future session's attributes.
   *
   * @param request the handshake (HTTP upgrade) request
   * @param response the handshake response; its status is set to 400/403 when refused
   * @param wsHandler the target handler (unused)
   * @param attributes the future WebSocket-session attributes; the resolved topic is stored here
   * @return {@code true} to proceed with the handshake, {@code false} to refuse it
   */
  @Override
  public boolean beforeHandshake(
      @NotNull ServerHttpRequest request,
      @NotNull ServerHttpResponse response,
      @NotNull WebSocketHandler wsHandler,
      @NotNull Map<String, Object> attributes) {
    UUID missionId = extractMissionId(request.getURI());
    if (missionId == null) {
      response.setStatusCode(HttpStatus.BAD_REQUEST);
      return false;
    }
    try {
      // Same authenticated read the mission-detail page performs; success means this user may see
      // the mission, so they may join its presence room.
      backendApiClient.get("/api/v1/missions/{id}", MISSION_TYPE, missionId);
      attributes.put(LiveSyncWebSocketHandler.ATTR_TOPIC, "mission:" + missionId);
      return true;
    } catch (BackendServiceException e) {
      int status = e.getStatusCode();
      if (status == 403 || status == 404) {
        log.debug("Live-sync handshake denied for mission {} (backend {})", missionId, status);
        response.setStatusCode(HttpStatus.FORBIDDEN);
        return false;
      }
      log.debug(
          "Live-sync handshake allowed despite transient backend status {} for mission {}",
          status,
          missionId);
      attributes.put(LiveSyncWebSocketHandler.ATTR_TOPIC, "mission:" + missionId);
      return true;
    } catch (RuntimeException e) {
      log.debug(
          "Live-sync handshake authorization check failed for mission {}; allowing (fail-open)",
          missionId,
          e);
      attributes.put(LiveSyncWebSocketHandler.ATTR_TOPIC, "mission:" + missionId);
      return true;
    }
  }

  /**
   * No-op: the gate decision is made entirely in {@link #beforeHandshake}.
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

  /**
   * Extracts the mission id from a {@code /ws/missions/{uuid}/presence} upgrade URI.
   *
   * @param uri the handshake URI (may be {@code null})
   * @return the mission id, or {@code null} if the path is not the canonical mission-presence shape
   *     or the id segment is not a UUID
   */
  @Nullable
  static UUID extractMissionId(@Nullable URI uri) {
    if (uri == null || uri.getPath() == null) {
      return null;
    }
    String[] parts = uri.getPath().split("/");
    List<String> nonEmpty = new ArrayList<>(parts.length);
    for (String p : parts) {
      if (!p.isEmpty()) {
        nonEmpty.add(p);
      }
    }
    if (nonEmpty.size() != 4
        || !"ws".equals(nonEmpty.get(0))
        || !"missions".equals(nonEmpty.get(1))
        || !"presence".equals(nonEmpty.get(3))) {
      return null;
    }
    try {
      return UUID.fromString(nonEmpty.get(2));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
