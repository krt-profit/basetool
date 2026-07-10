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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

/**
 * Unit tests for {@link SessionLifetimeUpgradeSuccessHandler}: a successful login must promote the
 * session's idle timeout from the short anonymous window to the long authenticated window
 * (REQ-SEC-025, ADR-0088) and then delegate the navigation unchanged — while never minting a
 * session when none exists.
 */
class SessionLifetimeUpgradeSuccessHandlerTest {

  private static final Duration AUTHENTICATED_TIMEOUT = Duration.ofHours(720);

  @Test
  void promotesTheSessionTimeoutToTheAuthenticatedWindowThenDelegates() throws Exception {
    // Given a request carrying an existing (short-lived, anonymous) session
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    Authentication authentication = mock(Authentication.class);
    HttpSession session = mock(HttpSession.class);
    when(request.getSession(false)).thenReturn(session);
    AuthenticationSuccessHandler delegate = mock(AuthenticationSuccessHandler.class);

    SessionLifetimeUpgradeSuccessHandler handler =
        new SessionLifetimeUpgradeSuccessHandler(AUTHENTICATED_TIMEOUT, delegate);

    // When the login succeeds
    handler.onAuthenticationSuccess(request, response, authentication);

    // Then the session gets the 30-day authenticated idle window, then navigation is delegated
    verify(session).setMaxInactiveInterval(Math.toIntExact(AUTHENTICATED_TIMEOUT.toSeconds()));
    verify(delegate).onAuthenticationSuccess(request, response, authentication);
  }

  @Test
  void skipsTheBumpWhenNoSessionExistsButStillDelegates() throws Exception {
    // Given a request without a session (getSession(false) returns null)
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    Authentication authentication = mock(Authentication.class);
    when(request.getSession(false)).thenReturn(null);
    AuthenticationSuccessHandler delegate = mock(AuthenticationSuccessHandler.class);

    SessionLifetimeUpgradeSuccessHandler handler =
        new SessionLifetimeUpgradeSuccessHandler(AUTHENTICATED_TIMEOUT, delegate);

    // When the login succeeds
    handler.onAuthenticationSuccess(request, response, authentication);

    // Then no throwaway session is created and the delegate still runs
    verify(request, never()).getSession();
    verify(delegate).onAuthenticationSuccess(request, response, authentication);
  }

  @Test
  void clampsAnOverlargeTimeoutToTheIntSecondContract() throws Exception {
    // Given an absurdly large configured window (beyond Integer.MAX_VALUE seconds)
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    Authentication authentication = mock(Authentication.class);
    HttpSession session = mock(HttpSession.class);
    when(request.getSession(false)).thenReturn(session);
    AuthenticationSuccessHandler delegate = mock(AuthenticationSuccessHandler.class);

    SessionLifetimeUpgradeSuccessHandler handler =
        new SessionLifetimeUpgradeSuccessHandler(Duration.ofDays(100_000), delegate);

    // When the login succeeds
    handler.onAuthenticationSuccess(request, response, authentication);

    // Then the second count is clamped to Integer.MAX_VALUE rather than overflowing
    verify(session).setMaxInactiveInterval(Integer.MAX_VALUE);
  }
}
