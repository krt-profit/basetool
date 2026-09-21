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
 * Debug filter that logs detailed information about the Spring Session / Redis Session lifecycle.
 *
 * <p>This filter is intentionally verbose at DEBUG level to help diagnose issues with the
 * Redis-backed Spring Session store, such as sessions not surviving frontend restarts, unexpected
 * session creation, or authentication loss after restart.
 *
 * <p>The filter logs session ids and {@code Authentication#getName()} (the Keycloak username). Both
 * are PII / secret material — audit finding M-15 restricts the filter to the {@code dev} and {@code
 * test} Spring profiles so a misconfigured production log level cannot accidentally emit session
 * ids to disk / log shipper. Enable in dev/test via:
 *
 * <pre>
 * logging:
 *   level:
 *     de.greluc.krt.profit.basetool.frontend.config.SessionDebugFilter: DEBUG
 * </pre>
 */
@Component
@org.springframework.context.annotation.Profile({"dev", "test"})
@Slf4j
public class SessionDebugFilter extends OncePerRequestFilter {

  /**
   * Reads the Authentication from the Spring Security context stored in the given session. This is
   * necessary in POST-filter checks because Spring Security clears the ThreadLocal SecurityContext
   * after processing a request (especially on 302 redirects), so {@link
   * SecurityContextHolder#getContext()} returns null/anonymous at that point. Reading directly from
   * the session attribute gives the correct persisted authentication state.
   */
  @Contract("null -> null")
  @Nullable
  private Authentication getAuthFromSession(@Nullable HttpSession session) {
    if (session == null) {
      return null;
    }
    // Spring Security stores the context under this well-known attribute name
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

    // --- PRE-filter: log session state BEFORE Spring Security processes the request ---
    HttpSession sessionBefore = request.getSession(false);
    // PRE: read from ThreadLocal — Spring Security has loaded it from the session at this point
    Authentication authBefore = SecurityContextHolder.getContext().getAuthentication();

    if (sessionBefore != null) {
      log.debug(
          "[SESSION] PRE  {} {} | sessionId={} | isNew={} | creationTime={} | lastAccessed={} |"
              + " maxInactive={}s | authenticated={}",
          method,
          uri,
          sessionBefore.getId(),
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

    // --- Execute filter chain ---
    filterChain.doFilter(request, response);

    // --- POST-filter: log session state AFTER Spring Security processed the request ---
    HttpSession sessionAfter = request.getSession(false);
    // POST: Spring Security has already cleared the ThreadLocal after processing (especially on
    // redirects).
    // Read the authentication from the session attribute directly to get the true persisted state.
    Authentication authAfter = getAuthFromSession(sessionAfter);

    if (sessionAfter != null) {
      boolean sessionCreatedDuringRequest = sessionBefore == null || sessionBefore.isNew();
      log.debug(
          "[SESSION] POST {} {} | sessionId={} | sessionCreatedNow={} | authenticated={}",
          method,
          uri,
          sessionAfter.getId(),
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
      // Warn if authentication was present before but lost from session after the request
      boolean hadAuthBefore = authBefore != null && authBefore.isAuthenticated();
      boolean hasAuthAfter = authAfter != null && authAfter.isAuthenticated();
      if (hadAuthBefore && !hasAuthAfter) {
        log.warn(
            "[SESSION] AUTHENTICATION LOST during {} {} | sessionId={}",
            method,
            uri,
            sessionAfter.getId());
      }
    } else if (sessionBefore != null) {
      log.debug("[SESSION] POST {} {} | SESSION INVALIDATED during request", method, uri);
    } else {
      log.debug("[SESSION] POST {} {} | NO SESSION (none created)", method, uri);
    }
  }
}
