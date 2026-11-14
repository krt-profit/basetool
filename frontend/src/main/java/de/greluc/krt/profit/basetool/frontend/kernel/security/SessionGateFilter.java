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

import static de.greluc.krt.profit.basetool.frontend.kernel.security.ReauthenticationHandoff.isWebSocketUpgrade;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.ReauthenticationRequiredException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.env.Environment;
import org.springframework.http.server.PathContainer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Sends a session a {@link SessionGate} holds to the first holding gate's page (REQ-SEC-028,
 * REQ-PATCH-020, ADR-0385), each kind of request the way {@link SessionGateHandoff} describes.
 *
 * <p>UX only; the backend's session gates are the boundary. Runs after {@code
 * BackendRoleSyncFilter}. Assets, public documents, the authentication plumbing, the legal pages,
 * the approval waiting page and the pages of the gates up to the one asked are never held. An
 * anonymous visitor and the {@code test} profile pass.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionGateFilter extends OncePerRequestFilter {

  private final SessionGates gates;
  private final Environment environment;

  @Override
  protected void doFilterInternal(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull FilterChain filterChain)
      throws ServletException, IOException {
    String path = PublicPaths.relativePath(request);
    if (PublicPaths.isGateExempt(path)
        || environment.matchesProfiles("test")
        || !isAuthenticated()) {
      filterChain.doFilter(request, response);
      return;
    }
    PathContainer parsed = PathContainer.parsePath(path);
    List<SessionGate> all = gates.all();
    for (int i = 0; i < all.size(); i++) {
      SessionGate gate = all.get(i);
      if (gates.isOpenTo(i, parsed)) {
        continue;
      }
      boolean holds;
      try {
        holds = gate.holds(request);
      } catch (ReauthenticationRequiredException _) {
        if (isWebSocketUpgrade(request)) {
          filterChain.doFilter(request, response);
          return;
        }
        ReauthenticationHandoff.send(request, response);
        return;
      }
      if (holds) {
        log.debug("Session gate {} holds {}", gate.problemCode(), request.getRequestURI());
        SessionGateHandoff.handOff(request, response, filterChain, gate);
        return;
      }
    }
    filterChain.doFilter(request, response);
  }

  /**
   * Whether an authenticated, non-anonymous member is behind the request; an anonymous visitor has
   * no account a gate could hold.
   *
   * @return {@code true} for an authenticated principal
   */
  private static boolean isAuthenticated() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    return auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal());
  }
}
