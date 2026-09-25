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

package de.greluc.krt.profit.basetool.frontend.logging;

import de.greluc.krt.profit.basetool.frontend.controller.MeFrontendController;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Copies the caller's active OrgUnit selection from the session into {@link ActiveSquadronContext}
 * and the {@code orgUnitId} MDC key for each request, and clears both afterwards.
 *
 * <p>Only the OrgUnit UUID enters the MDC, never its name (REQ-OBS-004). An async dispatch of the
 * same request re-binds only the MDC value stashed by the initial dispatch, not {@link
 * ActiveSquadronContext}, so outbound call scope is unchanged.
 */
@Component
public class ActiveSquadronContextFilter extends OncePerRequestFilter implements Ordered {

  /**
   * Request attribute carrying the {@code orgUnitId} MDC value the initial dispatch bound, for the
   * async dispatch of the same request to re-bind. Namespaced by this class so no other component's
   * attribute can collide with it; set server-side only, so no client can forge it.
   */
  static final String ORG_UNIT_ID_ATTRIBUTE =
      ActiveSquadronContextFilter.class.getName() + ".orgUnitId";

  /**
   * MDC key carrying the caller's active OrgUnit pin, spelled like the backend's key so one query
   * correlates both modules.
   */
  public static final String ORG_UNIT_ID_MDC_KEY = "orgUnitId";

  /**
   * MDC value rendered when the caller has no active OrgUnit pin — anonymous traffic, a
   * single-membership member who never opened the switcher, or an admin who has not pinned a
   * Staffel. A stable literal rather than an absent key, so "unpinned" is distinguishable from "the
   * filter never ran" and the log line keeps a constant field count.
   */
  public static final String NO_ACTIVE_ORG_UNIT = "none";

  /**
   * Filter order: late enough that Spring Session's {@code SessionRepositoryFilter} (default order
   * {@code Integer.MIN_VALUE + 50}) has already wrapped the request with the Redis-backed session,
   * and that Spring Security has populated the auth context. Same precedence band as {@code
   * CorrelationIdFilter} so the active squadron is bound before any controller-emitted backend call
   * leaves the JVM.
   */
  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE - 99;
  }

  @Override
  protected void doFilterInternal(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull FilterChain chain)
      throws ServletException, IOException {
    if (isAsyncDispatch(request)) {
      rebindForAsyncDispatch(request, response, chain);
      return;
    }
    UUID active = readActiveSquadron(request);
    if (active != null) {
      ActiveSquadronContext.set(active);
    }
    String orgUnitId = active == null ? NO_ACTIVE_ORG_UNIT : active.toString();
    MDC.put(ORG_UNIT_ID_MDC_KEY, orgUnitId);
    request.setAttribute(ORG_UNIT_ID_ATTRIBUTE, orgUnitId);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(ORG_UNIT_ID_MDC_KEY);
      ActiveSquadronContext.clear();
    }
  }

  /**
   * Opts this filter into async dispatches, which {@link OncePerRequestFilter} skips by default, so
   * {@link #doFilterInternal} can re-bind the stashed {@code orgUnitId} there.
   *
   * @return always {@code false}
   */
  @Override
  protected boolean shouldNotFilterAsyncDispatch() {
    return false;
  }

  /**
   * Runs the async dispatch of a request with the {@code orgUnitId} its initial dispatch bound put
   * back into the MDC, and removes it once the chain returns so it cannot survive on the container
   * thread. Without a stashed value (the initial dispatch never passed this filter) the key stays
   * unbound rather than being derived afresh.
   *
   * @param request the request being dispatched asynchronously
   * @param response the response of the same request
   * @param chain the remaining filter chain
   * @throws ServletException if a downstream filter or the servlet fails
   * @throws IOException if writing the response fails
   */
  private static void rebindForAsyncDispatch(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull FilterChain chain)
      throws ServletException, IOException {
    if (!(request.getAttribute(ORG_UNIT_ID_ATTRIBUTE) instanceof String orgUnitId)
        || orgUnitId.isBlank()) {
      chain.doFilter(request, response);
      return;
    }
    MDC.put(ORG_UNIT_ID_MDC_KEY, orgUnitId);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(ORG_UNIT_ID_MDC_KEY);
    }
  }

  @Nullable
  private UUID readActiveSquadron(@NotNull HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    if (session == null) {
      return null;
    }
    return ActiveSquadronContext.coerce(
        session.getAttribute(MeFrontendController.ACTIVE_ORG_UNIT_SESSION_KEY));
  }
}
