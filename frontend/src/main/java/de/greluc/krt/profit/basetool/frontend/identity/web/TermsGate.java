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

package de.greluc.krt.profit.basetool.frontend.identity.web;

import de.greluc.krt.profit.basetool.frontend.identity.client.IdentityBackendClient;
import de.greluc.krt.profit.basetool.frontend.identity.model.TermsStatusDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.security.SessionGate;
import de.greluc.krt.profit.basetool.frontend.kernel.security.SessionGateRead;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Routes a member who has not accepted the Terms of Use to the consent page (REQ-SEC-028), the
 * first session gate.
 *
 * <p>The verdict is cached per session for {@link #RECHECK_MILLIS}, whichever it is; recording
 * consent discards it. A backend failure lets the member through.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class TermsGate implements SessionGate {

  /** Where a member without consent is sent. */
  public static final String CONSENT_PATH = "/terms/accept";

  /**
   * Response header carrying the consent page's URL to an AJAX caller, mirroring the {@code
   * X-Reauthenticate} contract for a lost OAuth2 token (REQ-SEC-012).
   */
  static final String AJAX_HEADER = "X-Terms-Acceptance-Required";

  /** Name of the one-shot SSE event that hands an {@code EventSource} off to the consent page. */
  static final String SSE_EVENT = "terms-gate";

  /** Session attribute holding the epoch millis at which the cached verdict was read. */
  static final String SESSION_CHECKED_AT = "krt.terms.checkedAt";

  /** Session attribute holding the cached verdict. */
  static final String SESSION_ACCEPTED = "krt.terms.accepted";

  /** How long a cached verdict may be reused before the backend is asked again. */
  static final long RECHECK_MILLIS = 60_000L;

  private final IdentityBackendClient identityClient;

  @Override
  public @NotNull String pagePath() {
    return CONSENT_PATH;
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
    return BackendServiceException.CODE_TERMS_NOT_ACCEPTED;
  }

  @Override
  public boolean holds(@NotNull HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    long now = System.currentTimeMillis();
    if (session != null
        && Boolean.TRUE.equals(session.getAttribute(SESSION_ACCEPTED))
        && isFresh(session, now)) {
      return false;
    }
    if (knownHolding(request)) {
      return true;
    }
    SessionGateRead.Answer<TermsStatusDto> read = SessionGateRead.of(identityClient::termsStatus);
    if (!read.answered()) {
      return false;
    }
    TermsStatusDto status = read.body();
    boolean accepted = status == null || status.accepted();
    if (session != null) {
      session.setAttribute(SESSION_ACCEPTED, accepted);
      session.setAttribute(SESSION_CHECKED_AT, now);
    }
    return !accepted;
  }

  /**
   * Tells whether a fresh cached verdict says the session lacks consent, letting the role sync skip
   * a backend read the consent gate would refuse.
   *
   * @param request the current request
   * @return {@code true} when a fresh cached verdict says consent is missing
   */
  @Override
  public boolean knownHolding(@NotNull HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    return session != null
        && Boolean.FALSE.equals(session.getAttribute(SESSION_ACCEPTED))
        && isFresh(session, System.currentTimeMillis());
  }

  @Override
  public void forget(@NotNull HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    if (session != null) {
      session.removeAttribute(SESSION_ACCEPTED);
      session.removeAttribute(SESSION_CHECKED_AT);
    }
  }

  /**
   * Tells whether the session's cached verdict is younger than {@link #RECHECK_MILLIS}.
   *
   * @param session the session holding the verdict
   * @param now the current epoch millis
   * @return {@code true} for a fresh verdict
   */
  private static boolean isFresh(@NotNull HttpSession session, long now) {
    return session.getAttribute(SESSION_CHECKED_AT) instanceof Long millis
        && now - millis < RECHECK_MILLIS;
  }
}
