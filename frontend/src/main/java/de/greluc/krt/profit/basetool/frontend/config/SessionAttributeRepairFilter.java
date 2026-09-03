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

package de.greluc.krt.profit.basetool.frontend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.Ordered;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Removes the session attributes that could not be read, so a poisoned session is repaired on the
 * request that discovered it instead of re-dropping the same value for the rest of its life
 * (REQ-SEC-050, ADR-0157).
 *
 * <p><strong>The defect this closes (2026-09-03, the second alert).</strong> {@link
 * FaultTolerantSessionSerializer} turns an unreadable value into "absent" and {@link
 * SessionAttributeDiagnosticMapper} names it, but neither writes anything back — so the unreadable
 * bytes stay in Redis and are re-read, re-dropped and re-counted on <em>every</em> request the
 * session makes, for up to the 720-hour authenticated window (REQ-SEC-025). ADR-0154 accepted that,
 * on the reasoning that the one attribute in play would be re-written by the servlet container "on
 * the next handshake, i.e. every logged-in page, because live sync opens {@code /ws/sync}". That
 * premise is **false**: {@code krt-live-sync.js} opens the socket <em>lazily</em> — {@code
 * ensureSocket()} is reached only from {@code subscribe()}, {@code sendChanged()} and {@code
 * sendPresence()} — so a page that subscribes to no live-sync room never handshakes, while the
 * notification poll (60 s, or 300 s while SSE is healthy) keeps reading the session. Production
 * therefore kept dropping at 2-6 per minute for hours after the fix that was supposed to end it,
 * and `SessionValueDropsSustained` fired again against an application whose write path was already
 * correct.
 *
 * <p><strong>Why removing, and why here.</strong> {@code HttpSession#removeAttribute} is the same
 * public API that {@code BackendRoleSyncFilter} and {@code TermsAcceptanceGateFilter} already call
 * on every request; it goes through the ordinary delta-and-flush machinery rather than reaching
 * into the serializer, and it leaves the hash field readable-as-absent so the next read costs
 * nothing. Doing it from the deserializer instead would put a Redis write on the session
 * <em>read</em> path — the change ADR-0154 rightly called the one with the worst track record in
 * this codebase.
 *
 * <p><strong>Why this is safe even if a value is still being written unreadably.</strong> The
 * repair does not change how often a value is read, only whether the same unreadable bytes are read
 * twice. An attribute that some writer re-poisons every request would be repaired and re-poisoned
 * at an unchanged drop rate — the case {@link SessionAttributeDiagnosticMapper} flagged when it
 * deferred this decision — so the worst case is one extra write per drop, never a new failure mode.
 *
 * <p>The filter deliberately repairs on the way <em>out</em>: the session is loaded lazily, so at
 * the time the chain is entered nothing has been dropped yet.
 */
@Component
@Slf4j
public class SessionAttributeRepairFilter extends OncePerRequestFilter implements Ordered {

  /**
   * Filter order: immediately inside Spring Session's {@link SessionRepositoryFilter}, so every
   * session load that happens anywhere downstream — Spring Security's context read included — is
   * still inside this filter's {@code finally} when the repair runs. Expressed against {@link
   * SessionRepositoryFilter#DEFAULT_ORDER} rather than as a literal so it cannot silently drift to
   * the wrong side if that default ever moves.
   */
  @Override
  public int getOrder() {
    return SessionRepositoryFilter.DEFAULT_ORDER + 10;
  }

  /**
   * Runs for asynchronous dispatches too.
   *
   * <p>The notification SSE stream is an async request, and a session read on its dispatch would
   * otherwise queue a repair that no {@code finally} ever drains — leaving the name on a pooled
   * thread for the next request to apply to a different member's session. {@link
   * SessionAttributeRepairQueue#clear()} on entry makes that harmless, and filtering the async
   * dispatch makes it repairable instead of merely harmless.
   *
   * @return {@code false} — this filter must see every dispatch.
   */
  @Override
  protected boolean shouldNotFilterAsyncDispatch() {
    return false;
  }

  /**
   * Clears anything a previous request left on this thread, runs the chain, then repairs whatever
   * the session read dropped.
   *
   * @param request the current request; its session is fetched with {@code false} so the repair
   *     never creates one.
   * @param response the current response, passed through untouched.
   * @param filterChain the rest of the chain.
   * @throws ServletException propagated from the chain.
   * @throws IOException propagated from the chain.
   */
  @Override
  protected void doFilterInternal(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull FilterChain filterChain)
      throws ServletException, IOException {
    SessionAttributeRepairQueue.clear();
    try {
      filterChain.doFilter(request, response);
    } finally {
      repair(request);
    }
  }

  /**
   * Removes every attribute that was dropped during this request.
   *
   * @param request the request whose session is repaired; nothing happens when it has none.
   */
  private void repair(@NotNull HttpServletRequest request) {
    Set<String> dropped = SessionAttributeRepairQueue.drain();
    if (dropped.isEmpty()) {
      return;
    }
    HttpSession session = request.getSession(false);
    if (session == null) {
      // Invalidated during the request (a logout) or never materialised: the poisoned hash goes
      // away with the session itself, so there is nothing left to repair.
      return;
    }
    for (String attribute : dropped) {
      try {
        session.removeAttribute(attribute);
        log.debug("Repaired an unreadable session value: attribute='{}'.", attribute);
      } catch (IllegalStateException ex) {
        // The session was invalidated between the fetch above and this call. Harmless: the whole
        // hash is being deleted anyway.
        log.debug(
            "Session invalidated before attribute '{}' could be repaired; nothing to do.",
            attribute);
        return;
      }
    }
  }
}
