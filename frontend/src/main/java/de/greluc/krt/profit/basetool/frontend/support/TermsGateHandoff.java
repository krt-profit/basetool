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

package de.greluc.krt.profit.basetool.frontend.support;

import jakarta.servlet.http.HttpServletRequest;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Carries the consent gate's verdict across a WebSocket handshake from the servlet filter to the
 * handshake interceptor (REQ-SEC-028, REQ-FE-015).
 *
 * <p>The gate lets the upgrade through and marks it, because a refused upgrade looks like a dropped
 * connection and causes endless reconnects; the socket is then closed with a terminal code.
 */
public final class TermsGateHandoff {

  /**
   * Request attribute holding the consent-page URL on a marked handshake. Namespaced like the
   * gate's session attributes; never set for any other kind of request.
   */
  private static final String REQUEST_ATTRIBUTE = "krt.terms.gate.websocket";

  private TermsGateHandoff() {}

  /**
   * Marks a WebSocket handshake as belonging to a user without valid consent, recording where that
   * user must be sent.
   *
   * @param request the handshake request the gate let through
   * @param consentUrl the context-relative consent-page URL to hand to the client
   */
  public static void mark(@NotNull HttpServletRequest request, @NotNull String consentUrl) {
    request.setAttribute(REQUEST_ATTRIBUTE, consentUrl);
  }

  /**
   * Reads back the consent-page URL a marked handshake carries.
   *
   * @param request the handshake request
   * @return the consent-page URL when the gate marked this handshake, else {@code null}
   */
  @Nullable
  public static String consentUrl(@NotNull HttpServletRequest request) {
    return request.getAttribute(REQUEST_ATTRIBUTE) instanceof String url && !url.isBlank()
        ? url
        : null;
  }
}
