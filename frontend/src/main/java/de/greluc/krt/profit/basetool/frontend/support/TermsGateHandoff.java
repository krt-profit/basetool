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
 * Carries the consent gate's verdict across a WebSocket handshake, from the servlet filter that
 * decided it to the handshake interceptor that acts on it (REQ-SEC-028, REQ-FE-015).
 *
 * <p><strong>Why a handoff rather than a refusal.</strong> A WebSocket upgrade answered with
 * anything but {@code 101} — a {@code 302} to the consent page included — reaches the browser as
 * {@code close} with code {@code 1006} and no reason, indistinguishable from a dropped connection.
 * The client therefore reconnects, and consent can never be given from a background socket, so the
 * loop has no exit. The gate consequently lets the upgrade through and marks it; the socket is
 * refused with a terminal close code once one exists.
 *
 * <p><strong>Why it lives in {@code support}.</strong> {@code config} already depends on {@code
 * websocket} (the endpoint registration), so letting {@code websocket} read the attribute name off
 * the filter would close a package cycle. A leaf both may depend on is the same shape ADR-0047
 * forced on the backend's {@code support.TermsConsentCheck}, and it keeps a single owner for the
 * attribute name — a literal duplicated on the two sides would drift into a socket that is never
 * refused and a loop nobody notices.
 */
public final class TermsGateHandoff {

  /**
   * Request attribute holding the consent-page URL on a marked handshake. Namespaced like the
   * gate's session attributes; never set for any other kind of request.
   */
  private static final String REQUEST_ATTRIBUTE = "krt.terms.gate.websocket";

  private TermsGateHandoff() {
    // utility holder
  }

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
