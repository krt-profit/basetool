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

package de.greluc.krt.profit.basetool.frontend.patchchange.web;

import de.greluc.krt.profit.basetool.frontend.kernel.security.SessionGate;
import de.greluc.krt.profit.basetool.frontend.kernel.security.SessionGateRead;
import de.greluc.krt.profit.basetool.frontend.patchchange.client.PatchChangeBackendClient;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.HomeLocationStatusDto;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Routes a member who must choose a new home location after a patch change to the home-location
 * page (REQ-PATCH-020), the session gate after the consent gate.
 *
 * <p>A verdict that lets the member through is cached per session for {@link #RECHECK_MILLIS}; a
 * verdict that holds the member is never cached, so the choice frees the member on the next
 * request. A backend failure lets the member through.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@RequiredArgsConstructor
public class HomeLocationGate implements SessionGate {

  /** Where a member who must choose is sent. */
  public static final String PAGE_PATH = "/home-location";

  /** Response header carrying the home-location page's URL to an AJAX caller. */
  static final String AJAX_HEADER = "X-Home-Location-Required";

  /** Name of the one-shot SSE event that hands an {@code EventSource} off to the page. */
  static final String SSE_EVENT = "home-location-gate";

  /** Code of the backend's refusal while the member must still choose. */
  static final String PROBLEM_CODE = "HOME_LOCATION_REQUIRED";

  /** Session attribute holding the epoch millis at which the cached "may proceed" was read. */
  static final String SESSION_CHECKED_AT = "krt.homeLocation.checkedAt";

  /** How long a verdict that lets the member through may be reused. */
  static final long RECHECK_MILLIS = 60_000L;

  private final PatchChangeBackendClient patchChangeClient;

  @Override
  public @NotNull String pagePath() {
    return PAGE_PATH;
  }

  @Override
  public @NotNull String ajaxHeader() {
    return AJAX_HEADER;
  }

  @Override
  public @NotNull String sseEvent() {
    return SSE_EVENT;
  }

  @Override
  public @NotNull String problemCode() {
    return PROBLEM_CODE;
  }

  @Override
  public boolean holds(@NotNull HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    long now = System.currentTimeMillis();
    if (session != null
        && session.getAttribute(SESSION_CHECKED_AT) instanceof Long millis
        && now - millis < RECHECK_MILLIS) {
      return false;
    }
    SessionGateRead.Answer<HomeLocationStatusDto> read =
        SessionGateRead.of(patchChangeClient::myHomeLocationStatus);
    HomeLocationStatusDto status = read.body();
    if (!read.answered()) {
      return false;
    }
    boolean required = status != null && status.required();
    if (!required && session != null) {
      session.setAttribute(SESSION_CHECKED_AT, now);
    }
    return required;
  }

  @Override
  public void forget(@NotNull HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    if (session != null) {
      session.removeAttribute(SESSION_CHECKED_AT);
    }
  }
}
