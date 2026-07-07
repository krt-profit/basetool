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

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Custom {@link AuthenticationEntryPoint} that attempts a silent Keycloak SSO re-authentication
 * before falling back to the standard login page.
 *
 * <p>When a user's Spring session expires (e.g. after a session timeout), this entry point first
 * redirects the browser to the OAuth2 authorization endpoint with {@code prompt=none}. Keycloak
 * will then transparently re-authenticate the user using its own SSO session cookie (which lives in
 * the browser independently of the Spring session). If the Keycloak SSO session is still active,
 * the user is re-authenticated without any visible login prompt. If the Keycloak SSO session has
 * also expired, Keycloak returns an {@code interaction_required} error and the user is redirected
 * to the normal login page.
 *
 * <p>Bot and scanner requests are handled upstream by {@link BotProtectionFilter} before reaching
 * this entry point, so this class only deals with genuine unauthenticated user requests.
 *
 * <p>A short-lived cookie ({@code SSO_ATTEMPTED}) is used to prevent infinite redirect loops: if a
 * silent re-auth attempt has already been made for this request cycle, the entry point falls back
 * directly to the standard OAuth2 login flow (which will show the Keycloak login page if the SSO
 * session has also expired).
 *
 * <p>Note: With a persistent Redis-backed Spring Session store, this entry point is only triggered
 * for genuinely expired sessions, not after service restarts.
 *
 * <p><b>Only top-level navigations commence the silent SSO redirect.</b> Background traffic — a
 * {@code fetch}/XHR write, the {@code EventSource} notification stream, a WebSocket handshake —
 * that arrives without an authenticated session is answered with a {@code 401} carrying the {@code
 * X-Reauthenticate} header instead (the same contract {@code GlobalExceptionHandler} emits for the
 * token-loss path). Redirecting those would be actively harmful: {@code
 * HttpSessionOAuth2AuthorizationRequestRepository} stores exactly ONE saved authorization request
 * per session, so every background call that reached {@code
 * OAuth2AuthorizationRequestRedirectFilter} would overwrite the previous one, and the user's real
 * interactive re-login would then fail with {@code authorization_request_not_found}. Answering 401
 * + {@code X-Reauthenticate} lets the JS ({@code krtFetch.maybeReauthenticate} / {@code
 * notifications.js}) perform a single controlled window redirect while leaving the saved
 * authorization-request slot untouched for the genuine navigation (REQ-SEC-012, #1137).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SsoReAuthenticationEntryPoint implements AuthenticationEntryPoint {

  static final String SSO_ATTEMPTED_COOKIE = "SSO_ATTEMPTED";
  private static final String OAUTH2_AUTHORIZATION_BASE = "/oauth2/authorization/keycloak";
  private static final String REAUTHENTICATE_HEADER = "X-Reauthenticate";

  /**
   * Serializes the small {@code REAUTH_REQUIRED} JSON body mirrored from {@code
   * GlobalExceptionHandler}.
   */
  private final ObjectMapper objectMapper;

  @Override
  public void commence(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull AuthenticationException authException)
      throws IOException {

    String uri = request.getRequestURI();

    if (isBackgroundRequest(request)) {
      // Background fetch / EventSource / WS handshake with a dead session. Do NOT redirect — that
      // would clobber the session's single saved OAuth2 authorization request and break the user's
      // real interactive re-login (#1137). Answer 401 + X-Reauthenticate so the JS does one
      // controlled window redirect, leaving the saved-request slot for the genuine navigation.
      writeReauthChallenge(request, response);
      return;
    }

    if (isSsoAlreadyAttempted(request)) {
      // Silent re-auth already tried and failed – fall back to interactive login
      log.info(
          "[SSO] Silent re-auth already attempted and failed, falling back to interactive login."
              + " URI={} | remoteAddr={}",
          uri,
          request.getRemoteAddr());
      clearSsoAttemptedCookie(response);
      response.sendRedirect(request.getContextPath() + OAUTH2_AUTHORIZATION_BASE);
      return;
    }

    log.info(
        "[SSO] No active session found, attempting silent Keycloak SSO re-authentication. URI={} |"
            + " remoteAddr={} | sessionId={}",
        uri,
        request.getRemoteAddr(),
        request.getSession(false) != null ? request.getSession(false).getId() : "none");

    // Mark that a silent SSO attempt is in progress (prevents redirect loops)
    setSsoAttemptedCookie(response);

    // Redirect to Keycloak with prompt=none for silent re-authentication.
    // Spring Security's SavedRequestAwareAuthenticationSuccessHandler will restore
    // the original request URI after successful authentication.
    String redirectUrl = request.getContextPath() + OAUTH2_AUTHORIZATION_BASE + "?prompt=none";

    log.debug("[SSO] Redirecting to silent SSO endpoint: {}", redirectUrl);
    response.sendRedirect(redirectUrl);
  }

  /**
   * Decides whether this unauthenticated request is background traffic (a {@code fetch}/XHR write,
   * the {@code EventSource} stream, a WebSocket handshake) rather than a top-level browser
   * navigation. Background requests must be answered with a 401 challenge, never a 302 into the
   * OAuth2 flow, so they cannot overwrite the session's single saved authorization request (#1137).
   *
   * <p>The primary signal is the {@code Sec-Fetch-Mode} fetch-metadata header, which every modern
   * browser stamps and which page JavaScript cannot forge: a top-level navigation is {@code
   * navigate}, while fetch / EventSource / WebSocket are {@code cors} / {@code same-origin} /
   * {@code no-cors} / {@code websocket}. When that header is absent (older clients), it falls back
   * to the {@code X-Requested-With} XHR marker and an {@code Accept} of {@code application/json} /
   * {@code text/event-stream}; a request with none of those is treated as a navigation so the
   * silent-SSO flow is preserved for real page loads and no-JS form posts.
   *
   * @param request the current unauthenticated request
   * @return {@code true} for background/AJAX traffic that should get a 401 challenge; {@code false}
   *     for a top-level navigation that should commence the silent SSO redirect
   */
  private boolean isBackgroundRequest(@NotNull HttpServletRequest request) {
    String secFetchMode = request.getHeader("Sec-Fetch-Mode");
    if (secFetchMode != null && !secFetchMode.isBlank()) {
      return !"navigate".equalsIgnoreCase(secFetchMode.trim());
    }
    if ("XMLHttpRequest".equalsIgnoreCase(request.getHeader("X-Requested-With"))) {
      return true;
    }
    String accept = request.getHeader(HttpHeaders.ACCEPT);
    if (accept != null) {
      String lower = accept.toLowerCase(Locale.ROOT);
      return lower.contains(MediaType.APPLICATION_JSON_VALUE)
          || lower.contains(MediaType.TEXT_EVENT_STREAM_VALUE);
    }
    return false;
  }

  /**
   * Writes a {@code 401} carrying the {@code X-Reauthenticate} header and the small {@code
   * REAUTH_REQUIRED} JSON body, mirroring what {@code GlobalExceptionHandler} emits for the
   * token-loss AJAX path so the shared client helpers ({@code krtFetch.maybeReauthenticate}, {@code
   * notifications.js}) can perform a single controlled window redirect. Deliberately touches
   * neither the {@code SSO_ATTEMPTED} cookie nor the OAuth2 saved-request state — the whole point
   * is to leave the authorization flow untouched for the user's genuine navigation (#1137).
   *
   * @param request the current request, used to prefix the context path onto the reauth path
   * @param response the servlet response to write the 401 challenge onto
   * @throws IOException if the response body cannot be written
   */
  private void writeReauthChallenge(
      @NotNull HttpServletRequest request, @NotNull HttpServletResponse response)
      throws IOException {
    String reauthUrl = request.getContextPath() + OAUTH2_AUTHORIZATION_BASE;
    log.info(
        "[SSO] Background request without an active session — answering 401 + X-Reauthenticate"
            + " instead of redirecting (preserves the saved OAuth2 request). URI={} |"
            + " remoteAddr={}",
        request.getRequestURI(),
        request.getRemoteAddr());
    response.setStatus(HttpStatus.UNAUTHORIZED.value());
    response.setHeader(REAUTHENTICATE_HEADER, reauthUrl);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("code", "REAUTH_REQUIRED");
    body.put("status", HttpStatus.UNAUTHORIZED.value());
    body.put("reauthenticate", Boolean.TRUE);
    body.put("location", reauthUrl);
    objectMapper.writeValue(response.getWriter(), body);
  }

  private boolean isSsoAlreadyAttempted(@NotNull HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return false;
    }
    for (Cookie cookie : cookies) {
      if (SSO_ATTEMPTED_COOKIE.equals(cookie.getName()) && "1".equals(cookie.getValue())) {
        return true;
      }
    }
    return false;
  }

  private void setSsoAttemptedCookie(@NotNull HttpServletResponse response) {
    log.debug("[SSO] Setting SSO_ATTEMPTED cookie to prevent redirect loop");
    // SameSite=Strict to match the session / XSRF cookies (security audit gap-fill); jakarta's
    // Cookie has no SameSite setter, so emit a ResponseCookie Set-Cookie header. 60s max-age — only
    // needed for the redirect cycle.
    writeSsoAttemptedCookie(response, "1", 60);
  }

  private void clearSsoAttemptedCookie(@NotNull HttpServletResponse response) {
    log.debug("[SSO] Clearing SSO_ATTEMPTED cookie");
    writeSsoAttemptedCookie(response, "", 0);
  }

  /**
   * Emits the {@code SSO_ATTEMPTED} cookie as a {@link ResponseCookie} {@code Set-Cookie} header
   * with {@code Secure}, {@code HttpOnly} and {@code SameSite=Strict} — matching the rest of the
   * app's cookie posture (the servlet {@link Cookie} API cannot set {@code SameSite}). A {@code
   * maxAge} of {@code 0} clears the cookie.
   *
   * @param response the servlet response to write the {@code Set-Cookie} header on
   * @param value the cookie value ({@code "1"} to set, {@code ""} to clear)
   * @param maxAgeSeconds the cookie max-age in seconds ({@code 0} to expire immediately)
   */
  private void writeSsoAttemptedCookie(
      @NotNull HttpServletResponse response, @NotNull String value, long maxAgeSeconds) {
    response.addHeader(
        HttpHeaders.SET_COOKIE,
        ResponseCookie.from(SSO_ATTEMPTED_COOKIE, value)
            .httpOnly(true)
            .secure(true)
            .sameSite("Strict")
            .path("/")
            .maxAge(maxAgeSeconds)
            .build()
            .toString());
  }
}
