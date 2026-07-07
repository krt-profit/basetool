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

import static org.junit.jupiter.api.Assertions.*;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.AuthenticationException;
import tools.jackson.databind.json.JsonMapper;

class SsoReAuthenticationEntryPointTest {

  private SsoReAuthenticationEntryPoint entryPoint;
  private AuthenticationException authException;

  @BeforeEach
  void setUp() {
    entryPoint = new SsoReAuthenticationEntryPoint(JsonMapper.builder().build());
    authException = new InsufficientAuthenticationException("Not authenticated");
  }

  @Test
  void commence_shouldRedirectToSilentSsoAndSetAttemptedCookie_whenNoAttemptCookiePresent()
      throws Exception {
    // Given
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI("/dashboard");
    MockHttpServletResponse response = new MockHttpServletResponse();

    // When
    entryPoint.commence(request, response, authException);

    // Then
    String redirectedUrl = response.getRedirectedUrl();
    assertNotNull(redirectedUrl);
    assertTrue(
        redirectedUrl.contains("/oauth2/authorization/keycloak"),
        "Should redirect to OAuth2 authorization endpoint");
    assertTrue(redirectedUrl.contains("prompt=none"), "Should include prompt=none for silent SSO");

    Cookie attemptedCookie = response.getCookie(SsoReAuthenticationEntryPoint.SSO_ATTEMPTED_COOKIE);
    assertNotNull(attemptedCookie, "SSO_ATTEMPTED cookie should be set");
    assertEquals("1", attemptedCookie.getValue());
    assertTrue(attemptedCookie.isHttpOnly(), "Cookie must be HttpOnly");
    assertTrue(attemptedCookie.getSecure(), "Cookie must be Secure");
    assertEquals(60, attemptedCookie.getMaxAge(), "Cookie should expire after 60 seconds");
    assertTrue(
        response.getHeader("Set-Cookie").contains("SameSite=Strict"),
        "SSO_ATTEMPTED cookie must carry SameSite=Strict like the session / XSRF cookies");
  }

  @Test
  void commence_shouldRedirectToLoginPageAndClearCookie_whenAttemptCookieAlreadyPresent()
      throws Exception {
    // Given
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI("/dashboard");
    Cookie existingCookie = new Cookie(SsoReAuthenticationEntryPoint.SSO_ATTEMPTED_COOKIE, "1");
    request.setCookies(existingCookie);
    MockHttpServletResponse response = new MockHttpServletResponse();

    // When
    entryPoint.commence(request, response, authException);

    // Then
    String redirectedUrl = response.getRedirectedUrl();
    assertNotNull(redirectedUrl);
    assertTrue(
        redirectedUrl.contains("/oauth2/authorization/keycloak"), "Should redirect to login page");
    assertFalse(
        redirectedUrl.contains("prompt=none"), "Should NOT include prompt=none on second attempt");

    Cookie clearedCookie = response.getCookie(SsoReAuthenticationEntryPoint.SSO_ATTEMPTED_COOKIE);
    assertNotNull(clearedCookie, "SSO_ATTEMPTED cookie should be cleared");
    assertEquals(0, clearedCookie.getMaxAge(), "Cookie should be deleted (maxAge=0)");
  }

  @Test
  void commence_shouldHandleNullCookies_whenNoCookiesPresent() throws Exception {
    // Given
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI("/missions");
    // No cookies set – getCookies() returns null
    MockHttpServletResponse response = new MockHttpServletResponse();

    // When / Then – should not throw
    assertDoesNotThrow(() -> entryPoint.commence(request, response, authException));
    String redirectedUrl = response.getRedirectedUrl();
    assertNotNull(redirectedUrl);
    assertTrue(redirectedUrl.contains("prompt=none"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/dashboard",
        "/missions",
        "/missions/some-id",
        "/orders",
        "/order/",
        "/operations",
        "/hangar",
        "/profile",
        "/settings",
        "/inventory"
      })
  void commence_shouldTriggerSilentSso_whenLegitimateAppPathRequested(String appUri)
      throws Exception {
    // Given
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI(appUri);
    MockHttpServletResponse response = new MockHttpServletResponse();

    // When
    entryPoint.commence(request, response, authException);

    // Then
    String redirectedUrl = response.getRedirectedUrl();
    assertNotNull(redirectedUrl, "Legitimate app path should trigger SSO redirect. URI=" + appUri);
    assertTrue(
        redirectedUrl.contains("prompt=none"),
        "Legitimate app path should use silent SSO. URI=" + appUri);
    assertEquals(302, response.getStatus(), "Status should be 302 (redirect). URI=" + appUri);
  }

  @Test
  void commence_shouldNotSetSsoAttemptedCookie_whenCookieAlreadyPresent() throws Exception {
    // Given
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI("/orders");
    Cookie existingCookie = new Cookie(SsoReAuthenticationEntryPoint.SSO_ATTEMPTED_COOKIE, "1");
    request.setCookies(existingCookie);
    MockHttpServletResponse response = new MockHttpServletResponse();

    // When
    entryPoint.commence(request, response, authException);

    // Then – cookie should be cleared (maxAge=0), not re-set to 60
    Cookie cookie = response.getCookie(SsoReAuthenticationEntryPoint.SSO_ATTEMPTED_COOKIE);
    assertNotNull(cookie);
    assertEquals(0, cookie.getMaxAge(), "Cookie should be cleared, not re-set");
  }

  // --- #1137: background requests get a 401 challenge, never a saved-request-clobbering redirect
  // ---

  @Test
  void commence_shouldReturn401WithReauthHeader_forBackgroundFetch() throws Exception {
    // Given – a fetch/XHR write: Sec-Fetch-Mode is not "navigate"
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI("/notifications/unread-count");
    request.addHeader("Sec-Fetch-Mode", "cors");
    MockHttpServletResponse response = new MockHttpServletResponse();

    // When
    entryPoint.commence(request, response, authException);

    // Then – 401 + X-Reauthenticate, NO redirect and NO SSO cookie / saved-request mutation
    assertEquals(401, response.getStatus(), "Background fetch must get a 401, not a 302 redirect");
    assertNull(response.getRedirectedUrl(), "Background fetch must not be redirected");
    assertEquals(
        "/oauth2/authorization/keycloak",
        response.getHeader("X-Reauthenticate"),
        "X-Reauthenticate must carry the reauth path the JS helper redirects to");
    assertNull(
        response.getCookie(SsoReAuthenticationEntryPoint.SSO_ATTEMPTED_COOKIE),
        "Background 401 must not touch the SSO_ATTEMPTED cookie / OAuth2 saved-request state");
    assertTrue(
        response.getContentAsString().contains("REAUTH_REQUIRED"),
        "Body should mirror GlobalExceptionHandler's REAUTH_REQUIRED payload");
  }

  @Test
  void commence_shouldReturn401_forEventStreamAcceptWithoutSecFetchMode() throws Exception {
    // Given – an EventSource-style request on an older client (no Sec-Fetch-Mode metadata)
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI("/notifications/stream");
    request.addHeader("Accept", "text/event-stream");
    MockHttpServletResponse response = new MockHttpServletResponse();

    // When
    entryPoint.commence(request, response, authException);

    // Then
    assertEquals(401, response.getStatus());
    assertEquals("/oauth2/authorization/keycloak", response.getHeader("X-Reauthenticate"));
    assertNull(response.getRedirectedUrl());
  }

  @Test
  void commence_shouldReturn401_forXmlHttpRequestMarker() throws Exception {
    // Given – legacy XHR marker, no fetch metadata
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI("/missions/some-id/units/slim");
    request.addHeader("X-Requested-With", "XMLHttpRequest");
    MockHttpServletResponse response = new MockHttpServletResponse();

    // When
    entryPoint.commence(request, response, authException);

    // Then
    assertEquals(401, response.getStatus());
    assertEquals("/oauth2/authorization/keycloak", response.getHeader("X-Reauthenticate"));
    assertNull(response.getRedirectedUrl());
  }

  @Test
  void commence_shouldStillRedirect_forTopLevelNavigation() throws Exception {
    // Given – a genuine top-level navigation stamps Sec-Fetch-Mode: navigate
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI("/missions/some-id");
    request.addHeader("Sec-Fetch-Mode", "navigate");
    request.addHeader("Accept", "text/html,application/xhtml+xml");
    MockHttpServletResponse response = new MockHttpServletResponse();

    // When
    entryPoint.commence(request, response, authException);

    // Then – the silent-SSO redirect flow is preserved for real navigations
    assertEquals(302, response.getStatus());
    String redirectedUrl = response.getRedirectedUrl();
    assertNotNull(redirectedUrl);
    assertTrue(redirectedUrl.contains("prompt=none"), "Navigation should still use silent SSO");
    assertNull(
        response.getHeader("X-Reauthenticate"), "Navigation must not emit the AJAX challenge");
    assertNotNull(
        response.getCookie(SsoReAuthenticationEntryPoint.SSO_ATTEMPTED_COOKIE),
        "Navigation still sets the SSO_ATTEMPTED loop-guard cookie");
  }
}
