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

import static de.greluc.krt.profit.basetool.frontend.kernel.security.ReauthenticationHandoff.isAjax;
import static de.greluc.krt.profit.basetool.frontend.kernel.security.ReauthenticationHandoff.isEventStream;
import static de.greluc.krt.profit.basetool.frontend.kernel.security.ReauthenticationHandoff.isWebSocketUpgrade;
import static de.greluc.krt.profit.basetool.frontend.kernel.security.ReauthenticationHandoff.writeOneShotEvent;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Hands a request a {@link SessionGate} holds off to the gate's page, and carries the verdict
 * across a WebSocket handshake from the servlet filter to the handshake interceptor (REQ-SEC-028,
 * REQ-PATCH-020, REQ-FE-015).
 *
 * <p>The gate lets the upgrade through and marks it, because a refused upgrade looks like a dropped
 * connection and causes endless reconnects; the socket is then closed with a terminal code that
 * carries the gate page's URL.
 */
public final class SessionGateHandoff {

  /**
   * Request attribute holding the gate page's URL on a marked handshake. Namespaced like the gates'
   * session attributes; never set for any other kind of request.
   */
  private static final String REQUEST_ATTRIBUTE = "krt.sessionGate.websocket";

  private SessionGateHandoff() {}

  /**
   * Sends a request a gate holds to the gate's page, each kind of request the way it can follow: an
   * {@code EventSource} gets a one-shot named event, an AJAX call a 403 with a header naming the
   * page, a WebSocket upgrade passes marked for a terminal close, a page load a redirect.
   *
   * @param request the held request
   * @param response its response
   * @param chain the rest of the filter chain, which only a marked WebSocket upgrade continues
   * @param gate the gate that holds the request
   * @throws IOException if writing the response fails
   * @throws ServletException if the rest of the chain fails
   */
  static void handOff(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull FilterChain chain,
      @NotNull SessionGate gate)
      throws IOException, ServletException {
    String url = pageUrl(request, gate);
    if (isEventStream(request)) {
      writeOneShotEvent(response, gate.sseEvent(), url);
      return;
    }
    if (isAjax(request)) {
      response.setStatus(HttpServletResponse.SC_FORBIDDEN);
      response.setHeader(gate.ajaxHeader(), url);
      return;
    }
    if (isWebSocketUpgrade(request)) {
      mark(request, url);
      chain.doFilter(request, response);
      return;
    }
    response.sendRedirect(url);
  }

  /**
   * Builds the URL of a gate's page the browser is sent to.
   *
   * @param request the current request, whose context path the URL starts with
   * @param gate the gate
   * @return the context path followed by the gate's page path
   */
  public static @NotNull String pageUrl(
      @NotNull HttpServletRequest request, @NotNull SessionGate gate) {
    return request.getContextPath() + gate.pagePath();
  }

  /**
   * Marks a WebSocket handshake as belonging to a user a gate holds, recording where that user must
   * be sent.
   *
   * @param request the handshake request the gate let through
   * @param gateUrl the context-relative URL of the gate's page to hand to the client
   */
  public static void mark(@NotNull HttpServletRequest request, @NotNull String gateUrl) {
    request.setAttribute(REQUEST_ATTRIBUTE, gateUrl);
  }

  /**
   * Reads back the gate page's URL a marked handshake carries.
   *
   * @param request the handshake request
   * @return the gate page's URL when a gate marked this handshake, else {@code null}
   */
  @Nullable
  public static String gateUrl(@NotNull HttpServletRequest request) {
    return request.getAttribute(REQUEST_ATTRIBUTE) instanceof String url && !url.isBlank()
        ? url
        : null;
  }
}
