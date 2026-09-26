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
 * Removes the session attributes that could not be read, on the same request that discovered them
 * (REQ-SEC-050, ADR-0157).
 *
 * <p>Removal uses {@code HttpSession#removeAttribute} after the chain has run, since the session is
 * loaded lazily.
 */
@Component
@Slf4j
public class SessionAttributeRepairFilter extends OncePerRequestFilter implements Ordered {

  /**
   * Filter order: immediately inside {@link SessionRepositoryFilter}, relative to {@link
   * SessionRepositoryFilter#DEFAULT_ORDER}, so every downstream session load happens before the
   * repair.
   */
  @Override
  public int getOrder() {
    return SessionRepositoryFilter.DEFAULT_ORDER + 10;
  }

  /**
   * Runs for asynchronous dispatches too, so a session read on the SSE stream's dispatch is
   * repaired.
   *
   * @return {@code false}
   */
  @Override
  protected boolean shouldNotFilterAsyncDispatch() {
    return false;
  }

  /**
   * Clears anything a previous request left on this thread, runs the chain, then repairs whatever
   * the session read dropped.
   *
   * @param request the current request; its session is fetched without creating one
   * @param response the current response, passed through untouched
   * @param filterChain the rest of the chain
   * @throws ServletException propagated from the chain
   * @throws IOException propagated from the chain
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
      return;
    }
    for (String attribute : dropped) {
      try {
        session.removeAttribute(attribute);
        log.debug("Repaired an unreadable session value: attribute='{}'.", attribute);
      } catch (IllegalStateException ex) {
        log.debug(
            "Session invalidated before attribute '{}' could be repaired; nothing to do.",
            attribute);
        return;
      }
    }
  }
}
