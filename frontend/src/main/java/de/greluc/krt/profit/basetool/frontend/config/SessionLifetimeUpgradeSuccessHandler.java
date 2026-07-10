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

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Duration;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

/**
 * {@link AuthenticationSuccessHandler} decorator that promotes the just-authenticated session from
 * the short <em>anonymous</em> idle timeout to the long <em>authenticated</em> idle timeout, then
 * delegates the actual post-login navigation to the wrapped handler (REQ-SEC-025, ADR-0088).
 *
 * <p><strong>Why this exists.</strong> The Redis session store is created with a deliberately short
 * default idle timeout ({@code app.session.anonymous-timeout}, see {@code RedisSessionConfig}) so
 * that the throwaway sessions minted for <em>un-authenticated</em> traffic — chiefly the one Spring
 * Security creates to hold a CSRF token when an anonymous client renders a form-bearing permit-all
 * page, plus pre-login OAuth2 {@code authorizationRequest} state — expire in minutes instead of
 * lingering for the full login window. Without that split, every anonymous probe / crawler hit
 * accreted a 30-day Redis session; production reached &gt;16 000 orphan CSRF-only sessions against
 * ~30 real principals (basetool_active_sessions runaway), on a collision course with the {@code
 * maxmemory noeviction} Redis ceiling. This handler restores the intended 30-day "stay logged in"
 * window the moment a login actually succeeds, so real users are unaffected while orphans stay
 * short-lived.
 *
 * <p>Runs after Spring Security's session-fixation {@code changeSessionId} (which preserves the
 * session's {@code maxInactiveInterval}), so it operates on the final authenticated session id. If
 * no session exists at this point (it always does after an OAuth2 login — the security context and
 * authorized client are persisted into it) the bump is skipped rather than a throwaway session
 * created.
 */
public class SessionLifetimeUpgradeSuccessHandler implements AuthenticationSuccessHandler {

  private final int authenticatedTimeoutSeconds;
  private final AuthenticationSuccessHandler delegate;

  /**
   * Wraps the real success handler with the anonymous-to-authenticated idle-timeout promotion.
   *
   * @param authenticatedTimeout the idle timeout an authenticated session should carry (the 30-day
   *     "stay logged in" window); clamped to {@link Integer#MAX_VALUE} seconds for the servlet
   *     {@link HttpSession#setMaxInactiveInterval(int)} contract
   * @param delegate the success handler that performs the actual post-login navigation
   */
  public SessionLifetimeUpgradeSuccessHandler(
      @NotNull Duration authenticatedTimeout, @NotNull AuthenticationSuccessHandler delegate) {
    this.authenticatedTimeoutSeconds =
        (int) Math.min(authenticatedTimeout.toSeconds(), Integer.MAX_VALUE);
    this.delegate = delegate;
  }

  /**
   * Promotes the current session's idle timeout to the authenticated window (if a session exists),
   * then delegates the redirect/navigation unchanged.
   *
   * @param request the current HTTP request whose (post-fixation) session is promoted
   * @param response the HTTP response the delegate may redirect
   * @param authentication the successful authentication
   * @throws IOException if the delegate fails to write the response
   * @throws ServletException if the delegate raises a servlet-layer exception
   */
  @Override
  public void onAuthenticationSuccess(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull Authentication authentication)
      throws IOException, ServletException {
    HttpSession session = request.getSession(false);
    if (session != null) {
      session.setMaxInactiveInterval(authenticatedTimeoutSeconds);
    }
    delegate.onAuthenticationSuccess(request, response, authentication);
  }
}
