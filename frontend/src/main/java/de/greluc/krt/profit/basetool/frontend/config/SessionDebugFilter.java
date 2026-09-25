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

import de.greluc.krt.profit.basetool.frontend.support.SessionIdFingerprint;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Debug filter that logs the Spring Session / Redis session lifecycle at DEBUG level, active only
 * in the {@code dev} and {@code test} profiles because it logs the username.
 *
 * <p>Sessions are identified by a {@link SessionIdFingerprint}, never by the raw session id.
 */
@Component
@org.springframework.context.annotation.Profile({"dev", "test"})
@Slf4j
public class SessionDebugFilter extends OncePerRequestFilter {

  /**
   * Reads the Authentication from the security context stored in the given session, which stays
   * accurate after Spring Security has cleared the thread-local context.
   */
  @Contract("null -> null")
  @Nullable
  private Authentication getAuthFromSession(@Nullable HttpSession session) {
    if (session == null) {
      return null;
    }
    Object ctx = session.getAttribute("SPRING_SECURITY_CONTEXT");
    if (ctx instanceof SecurityContext secCtx) {
      return secCtx.getAuthentication();
    }
    return null;
  }

  @Override
  protected void doFilterInternal(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull FilterChain filterChain)
      throws ServletException, IOException {

    if (!log.isDebugEnabled()) {
      filterChain.doFilter(request, response);
      return;
    }

    String uri = request.getRequestURI();
    String method = request.getMethod();

    HttpSession sessionBefore = request.getSession(false);
    Authentication authBefore = SecurityContextHolder.getContext().getAuthentication();

    if (sessionBefore != null) {
      log.debug(
          "[SESSION] PRE  {} {} | sessionId={} | isNew={} | creationTime={} | lastAccessed={} |"
              + " maxInactive={}s | authenticated={}",
          method,
          uri,
          SessionIdFingerprint.of(sessionBefore),
          sessionBefore.isNew(),
          sessionBefore.getCreationTime(),
          sessionBefore.getLastAccessedTime(),
          sessionBefore.getMaxInactiveInterval(),
          authBefore != null && authBefore.isAuthenticated());
      if (authBefore != null && authBefore.isAuthenticated()) {
        log.debug(
            "[SESSION] PRE  {} {} | principal={} | authType={}",
            method,
            uri,
            authBefore.getName(),
            authBefore.getClass().getSimpleName());
      }
    } else {
      log.debug(
          "[SESSION] PRE  {} {} | NO SESSION FOUND | authenticated={}",
          method,
          uri,
          authBefore != null && authBefore.isAuthenticated());
    }

    filterChain.doFilter(request, response);

    HttpSession sessionAfter = request.getSession(false);
    Authentication authAfter = getAuthFromSession(sessionAfter);

    if (sessionAfter != null) {
      boolean sessionCreatedDuringRequest = sessionBefore == null || sessionBefore.isNew();
      log.debug(
          "[SESSION] POST {} {} | sessionId={} | sessionCreatedNow={} | authenticated={}",
          method,
          uri,
          SessionIdFingerprint.of(sessionAfter),
          sessionCreatedDuringRequest,
          authAfter != null && authAfter.isAuthenticated());
      if (authAfter != null && authAfter.isAuthenticated()) {
        log.debug(
            "[SESSION] POST {} {} | principal={} | authType={}",
            method,
            uri,
            authAfter.getName(),
            authAfter.getClass().getSimpleName());
      }
      boolean hadAuthBefore = authBefore != null && authBefore.isAuthenticated();
      boolean hasAuthAfter = authAfter != null && authAfter.isAuthenticated();
      if (hadAuthBefore && !hasAuthAfter) {
        log.warn(
            "[SESSION] AUTHENTICATION LOST during {} {} | sessionId={}",
            method,
            uri,
            SessionIdFingerprint.of(sessionAfter));
      }
    } else if (sessionBefore != null) {
      log.debug("[SESSION] POST {} {} | SESSION INVALIDATED during request", method, uri);
    } else {
      log.debug("[SESSION] POST {} {} | NO SESSION (none created)", method, uri);
    }
  }
}
