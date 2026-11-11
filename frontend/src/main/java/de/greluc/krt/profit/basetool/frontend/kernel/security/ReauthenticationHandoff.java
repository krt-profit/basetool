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

package de.greluc.krt.profit.basetool.frontend.kernel.security;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.ReauthenticationRequiredException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

/** Writes the browser's session-gate handoff before a request reaches MVC (REQ-SEC-012). */
final class ReauthenticationHandoff {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String REAUTH_HEADER = "X-Reauthenticate";

  private ReauthenticationHandoff() {}

  /**
   * Sends one login event, a background challenge, or a navigation redirect.
   *
   * @param request the request whose OAuth2 client needs a fresh login
   * @param response the response carrying the handoff
   * @throws IOException when the response cannot be written
   */
  static void send(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response)
      throws IOException {
    String path = request.getContextPath() + ReauthenticationRequiredException.REAUTH_PATH;
    if (isEventStream(request)) {
      writeOneShotEvent(response, "reauth", path);
    } else if (isBackgroundRequest(request)) {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setHeader(REAUTH_HEADER, path);
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      JSON.writeValue(
          response.getWriter(),
          Map.of(
              "code",
              "REAUTH_REQUIRED",
              "status",
              HttpServletResponse.SC_UNAUTHORIZED,
              "reauthenticate",
              true,
              "location",
              path));
    } else {
      response.sendRedirect(path);
    }
  }

  /**
   * Identifies an XHR's explicit background marker.
   *
   * @param request the request to inspect
   * @return whether the browser marked an AJAX request
   */
  static boolean isAjax(@NotNull HttpServletRequest request) {
    return "XMLHttpRequest".equals(request.getHeader("X-Requested-With"));
  }

  /**
   * Identifies a subscription by its accepted event-stream media type.
   *
   * @param request the request to inspect
   * @return whether the client subscribes to SSE
   */
  static boolean isEventStream(@NotNull HttpServletRequest request) {
    String accept = request.getHeader(HttpHeaders.ACCEPT);
    return accept != null
        && accept.toLowerCase(Locale.ROOT).contains(MediaType.TEXT_EVENT_STREAM_VALUE);
  }

  /**
   * Identifies a WebSocket handshake by its upgrade header.
   *
   * @param request the request to inspect
   * @return whether the request upgrades to a WebSocket
   */
  static boolean isWebSocketUpgrade(@NotNull HttpServletRequest request) {
    return "websocket".equalsIgnoreCase(request.getHeader(HttpHeaders.UPGRADE));
  }

  /**
   * Writes one complete SSE navigation event without a reconnecting error response.
   *
   * @param response the response carrying the event
   * @param eventName the browser's event listener name
   * @param path the context-relative navigation target
   * @throws IOException when the response cannot be written
   */
  static void writeOneShotEvent(
      @NotNull HttpServletResponse response, @NotNull String eventName, @NotNull String path)
      throws IOException {
    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
    PrintWriter writer = response.getWriter();
    writer.write("event: " + eventName + "\ndata: " + path + "\n\n");
    writer.flush();
  }

  private static boolean isBackgroundRequest(@NotNull HttpServletRequest request) {
    String mode = request.getHeader("Sec-Fetch-Mode");
    if (mode != null && !mode.isBlank()) {
      return !"navigate".equalsIgnoreCase(mode.trim());
    }
    String accept = request.getHeader(HttpHeaders.ACCEPT);
    return isAjax(request)
        || isWebSocketUpgrade(request)
        || (accept != null
            && accept.toLowerCase(Locale.ROOT).contains(MediaType.APPLICATION_JSON_VALUE));
  }
}
