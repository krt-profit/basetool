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
import org.jetbrains.annotations.NotNull;

/**
 * A step a member must take before the frontend serves their session, such as accepting the Terms
 * of Use (REQ-SEC-028) or choosing a new home location (REQ-PATCH-020), ADR-0385.
 *
 * <p>UX only: the backend's session gate of the same problem code is the boundary. The domain that
 * owns the step implements it as a bean; {@link SessionGates} orders them by {@code @Order} and
 * {@link SessionGateFilter} sends a held session to the first holding gate's page.
 */
public interface SessionGate {

  /**
   * Returns the gate's page, where the member takes the step.
   *
   * @return the context-relative path of the page
   */
  @NotNull
  String pagePath();

  /**
   * Returns the response header that names the gate's page to an AJAX caller.
   *
   * @return the header name
   */
  @NotNull
  String ajaxHeader();

  /**
   * Returns the name of the one-shot event that hands an {@code EventSource} off to the page.
   *
   * @return the SSE event name
   */
  @NotNull
  String sseEvent();

  /**
   * Returns the {@code code} of the backend's refusal for this gate.
   *
   * @return the RFC 7807 problem code
   */
  @NotNull
  String problemCode();

  /**
   * Tells whether the gate holds the request's session; may ask the backend and cache the verdict
   * in the session. A backend that cannot answer lets the session through.
   *
   * @param request the current request of an authenticated member
   * @return {@code true} when the member must take the step first
   * @throws ReauthenticationRequiredException when the session can no longer reach the backend
   */
  boolean holds(@NotNull HttpServletRequest request);

  /**
   * Tells whether a fresh cached verdict already says the gate holds the session, without asking
   * the backend.
   *
   * @param request the current request
   * @return {@code true} only for a fresh cached verdict that holds the session
   */
  default boolean knownHolding(@NotNull HttpServletRequest request) {
    return false;
  }

  /**
   * Discards the session's cached verdict, so the next request asks the backend again.
   *
   * @param request the current request, whose session holds the cached verdict
   */
  void forget(@NotNull HttpServletRequest request);
}
