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

package de.greluc.krt.profit.basetool.frontend.kernel.web;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendErrorMapper;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.ReauthenticationRequiredException;
import de.greluc.krt.profit.basetool.frontend.kernel.security.PublicPaths;
import de.greluc.krt.profit.basetool.frontend.kernel.security.SessionGates;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Seeds the session's active org unit from the caller's stored choice (REQ-ORG-031), once per
 * session and principal, before the request binds {@link ActiveSquadronContext}.
 *
 * <p>The session remembers whose choice it holds, so a session another principal signs in to starts
 * from that principal's choice, and a session that holds a pin but was never restored keeps it. The
 * pages of the access gates never read the choice: a gated session lands on them, they render
 * without the context, and the waiting page polls its status. An access gate's refusal (pending
 * approval, terms not accepted, no role) or a lost OAuth2 session changes nothing, so a later
 * request reads again, and the first request after the gate clears restores the choice. Any other
 * failed read leaves the session without a pin, which is all of the caller's org units, and is
 * retried after {@link #RETRY_AFTER_MILLIS}.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
@Slf4j
public class ActiveOrgUnitRestore {

  /** Session attribute naming the principal whose stored choice the session's pin reflects. */
  static final String RESTORED_FOR_SESSION_KEY = "basetool.activeOrgUnitRestoredFor";

  /** Session attribute holding the epoch millis before which a failed read is not repeated. */
  static final String RETRY_AT_SESSION_KEY = "basetool.activeOrgUnitRestoreRetryAt";

  /** How long a failed read of the stored choice waits before the next attempt. */
  static final long RETRY_AFTER_MILLIS = 60_000L;

  /** Reads the caller's stored choice from the backend. */
  @NotNull private final ActiveOrgUnitChoiceSource choiceSource;

  /** Names the pages of the session gates, which never read the choice. */
  @NotNull private final SessionGates sessionGates;

  /** The clock the retry wait is measured on. */
  @NotNull private final Clock clock;

  /**
   * Creates the restore on the system clock.
   *
   * @param choiceSource reads the caller's stored choice
   * @param sessionGates names the pages of the session gates
   */
  @Autowired
  public ActiveOrgUnitRestore(
      @NotNull ActiveOrgUnitChoiceSource choiceSource, @NotNull SessionGates sessionGates) {
    this(choiceSource, sessionGates, Clock.systemUTC());
  }

  /**
   * Seeds the session's pin from the stored choice when this session has not done so for the
   * current principal yet; does nothing for an anonymous caller, a request without a session, a
   * static asset or public document and a page of an access gate, whose response cannot depend on
   * the context.
   *
   * @param request the current request; never {@code null}
   */
  public void restoreOnce(@NotNull HttpServletRequest request) {
    String principal = currentPrincipal();
    HttpSession session = request.getSession(false);
    if (principal == null || session == null) {
      return;
    }
    Object restoredFor = session.getAttribute(RESTORED_FOR_SESSION_KEY);
    String path = PublicPaths.relativePath(request);
    if (principal.equals(restoredFor)
        || retryPending(session)
        || PublicPaths.isAssetOrPublicDocument(path)
        || sessionGates.isGatePage(path)) {
      return;
    }
    if (restoredFor == null && sessionPin(session) != null) {
      markRestored(session, principal);
      return;
    }
    Optional<UUID> stored;
    try {
      stored = choiceSource.storedChoice();
    } catch (ReauthenticationRequiredException _) {
      return;
    } catch (BackendServiceException e) {
      if (BackendErrorMapper.isExpectedAccessGateRefusal(e.getProblemCode())) {
        log.debug(
            "An access gate refused reading the stored active org unit ({}); the next request"
                + " reads it again",
            e.getProblemCode());
      } else if (!ReauthenticationRequiredException.isReauthSignal(e)) {
        log.warn("Reading the stored active org unit failed; the session starts unpinned", e);
        session.removeAttribute(ActiveSquadronContext.ACTIVE_ORG_UNIT_SESSION_KEY);
        session.setAttribute(RETRY_AT_SESSION_KEY, clock.millis() + RETRY_AFTER_MILLIS);
      }
      return;
    }
    stored.ifPresentOrElse(
        id ->
            session.setAttribute(ActiveSquadronContext.ACTIVE_ORG_UNIT_SESSION_KEY, id.toString()),
        () -> session.removeAttribute(ActiveSquadronContext.ACTIVE_ORG_UNIT_SESSION_KEY));
    markRestored(session, principal);
  }

  /**
   * Records that the session's pin reflects the given principal's choice, as a switch does.
   *
   * @param session the current session; never {@code null}
   * @param principal the authenticated principal's name; never {@code null}
   */
  public static void markRestored(@NotNull HttpSession session, @NotNull String principal) {
    session.setAttribute(RESTORED_FOR_SESSION_KEY, principal);
    session.removeAttribute(RETRY_AT_SESSION_KEY);
  }

  /**
   * Returns the name of the OAuth2-authenticated principal of the current request.
   *
   * @return the principal's name, or {@code null} for an anonymous or non-OAuth2 caller
   */
  @Nullable
  public static String currentPrincipal() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    return auth instanceof OAuth2AuthenticationToken && auth.isAuthenticated()
        ? auth.getName()
        : null;
  }

  private boolean retryPending(@NotNull HttpSession session) {
    return session.getAttribute(RETRY_AT_SESSION_KEY) instanceof Long retryAt
        && clock.millis() < retryAt;
  }

  @Nullable
  private static UUID sessionPin(@NotNull HttpSession session) {
    return ActiveSquadronContext.coerce(
        session.getAttribute(ActiveSquadronContext.ACTIVE_ORG_UNIT_SESSION_KEY));
  }
}
