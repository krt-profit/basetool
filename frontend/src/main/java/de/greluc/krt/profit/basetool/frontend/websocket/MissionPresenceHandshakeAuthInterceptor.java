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
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Authorizes the mission-presence WebSocket handshake against actual mission access.
 *
 * <p>The Spring Security chain only proves the upgrade request is <em>authenticated</em>; it does
 * not prove the user may see the mission the socket targets. Without this interceptor any
 * authenticated user could open {@code /ws/missions/{anyId}/presence} and — via the {@code changed}
 * relay — drive re-fetches in that mission's other viewers (bounded amplification,
 * REQ-SEC/ADR-0031). This gate closes that by mirroring the mission-detail page's own
 * authorization: it issues the same authenticated {@code GET /api/v1/missions/{id}} the page does,
 * so exactly the users who can load the page can open its socket — no legitimate viewer is ever
 * rejected.
 *
 * <p><b>Fail-open on transient errors.</b> Only an <em>explicit</em> backend denial (HTTP 403/404)
 * closes the handshake. A transient failure (5xx, timeout, circuit open) or any other error allows
 * the socket so a backend blip never silently kills presence for a legitimate viewer — safe because
 * no mission data crosses the socket and every downstream fragment re-fetch re-authorizes per
 * viewer, so allowing the socket can never leak data. A malformed path (no parseable mission id) is
 * rejected with 400.
 */
@Slf4j
@RequiredArgsConstructor
public class MissionPresenceHandshakeAuthInterceptor implements HandshakeInterceptor {

  /** Response type for the authenticated {@code /api/v1/missions/{id}} access-probe read. */
  private static final ParameterizedTypeReference<MissionDto> MISSION_TYPE =
      new ParameterizedTypeReference<>() {};

  private final BackendApiClient backendApiClient;

  /**
   * Gates the handshake: parses the mission id from the upgrade URI and authorizes it against the
   * backend before the socket is established.
   *
   * @param request the handshake (HTTP upgrade) request
   * @param response the handshake response; its status is set to 400/403 when the handshake is
   *     refused
   * @param wsHandler the target handler (unused)
   * @param attributes the future WebSocket-session attributes (unused)
   * @return {@code true} to proceed with the handshake, {@code false} to refuse it
   */
  @Override
  public boolean beforeHandshake(
      @NotNull ServerHttpRequest request,
      @NotNull ServerHttpResponse response,
      @NotNull WebSocketHandler wsHandler,
      @NotNull Map<String, Object> attributes) {
    UUID missionId = MissionPresenceWebSocketHandler.extractMissionId(request.getURI());
    if (missionId == null) {
      response.setStatusCode(HttpStatus.BAD_REQUEST);
      return false;
    }
    try {
      // Same authenticated read the mission-detail page performs; success means this user may see
      // the mission, so they may join its presence room.
      backendApiClient.get("/api/v1/missions/{id}", MISSION_TYPE, missionId);
      return true;
    } catch (BackendServiceException e) {
      int status = e.getStatusCode();
      if (status == 403 || status == 404) {
        log.debug("Presence handshake denied for mission {} (backend {})", missionId, status);
        response.setStatusCode(HttpStatus.FORBIDDEN);
        return false;
      }
      log.debug(
          "Presence handshake allowed despite transient backend status {} for mission {}",
          status,
          missionId);
      return true;
    } catch (RuntimeException e) {
      log.debug(
          "Presence handshake authorization check failed for mission {}; allowing (fail-open)",
          missionId,
          e);
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
}
