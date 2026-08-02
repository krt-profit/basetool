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
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Servlet filter that mirrors the caller's active OrgUnit selection from the frontend's Spring
 * Session into {@link ActiveSquadronContext} and into the {@code orgUnitId} MDC key on every
 * request, and clears both on the way out.
 *
 * <p>The {@code ActiveSquadronRelayFilter} on the WebClient pipeline cannot read the session
 * directly because it runs on Netty reactor threads where {@code RequestContextHolder} is not
 * bound. A thread-local snapshot taken on the Tomcat request thread, combined with Reactor's
 * automatic context propagation (enabled by Spring Boot 4), survives the hop. The cleanup in the
 * {@code finally} block prevents bleed-through onto pooled or virtual threads.
 *
 * <p>The filter runs early in the chain (one notch after {@code CorrelationIdFilter}) so the value
 * is visible to every downstream component that issues a backend call.
 *
 * <p><b>Why the MDC key is bound here and not in {@link CorrelationIdFilter}.</b> REQ-OBS-001 wants
 * all three correlation fields ({@code correlationId}, {@code userId}, {@code orgUnitId}) on every
 * frontend log line, and the frontend used to carry only the first two — so a support question of
 * the shape "an admin pinned to Staffel A is seeing Staffel B rows" could not be answered from the
 * frontend log at all: the backend line renders {@code orgUnitId=all} both for "the admin sent no
 * header" and for "the pin was lost on the way", and the frontend line that would have told the two
 * apart had no such column. {@code CorrelationIdFilter} is ordered {@link
 * Ordered#LOWEST_PRECEDENCE} − 100 and this filter {@link Ordered#LOWEST_PRECEDENCE} − 99, i.e. the
 * correlation filter runs <em>first</em> and the pin is not resolved yet at that point — binding
 * the key there would only ever have recorded {@code null}. This filter is where the pin actually
 * becomes known, so this is where it enters the MDC.
 *
 * <p>Only the OrgUnit <b>UUID</b> is ever put into the MDC, never its name: a Staffel / SK name is
 * squadron-identifying free text and is out of bounds for a log line under REQ-OBS-004, and the
 * UUID is what correlates against the backend's identically-named field anyway.
 */
@Component
public class ActiveSquadronContextFilter extends OncePerRequestFilter implements Ordered {

  /**
   * MDC key carrying the caller's active OrgUnit pin. Matches the {@code %X{orgUnitId:-}} slot in
   * the frontend {@code logback-spring.xml} patterns and the {@code includeMdcKeyName} entry on the
   * structured JSON encoder, and is spelled identically to the backend's key so a single query
   * correlates both modules. Kept as a local constant rather than a {@code LoggingProperties} field
   * because the pattern is not operator-tunable either.
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
    UUID active = readActiveSquadron(request);
    if (active != null) {
      ActiveSquadronContext.set(active);
    }
    MDC.put(ORG_UNIT_ID_MDC_KEY, active == null ? NO_ACTIVE_ORG_UNIT : active.toString());
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(ORG_UNIT_ID_MDC_KEY);
      ActiveSquadronContext.clear();
    }
  }

  private UUID readActiveSquadron(@NotNull HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    if (session == null) {
      return null;
    }
    return ActiveSquadronContext.coerce(
        session.getAttribute(MeFrontendController.ACTIVE_ORG_UNIT_SESSION_KEY));
  }
}
