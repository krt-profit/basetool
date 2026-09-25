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
 * {@link AuthenticationEntryPoint} that tries a silent Keycloak SSO re-authentication ({@code
 * prompt=none}) before falling back to the login page.
 *
 * <p>A short-lived {@code SSO_ATTEMPTED} cookie prevents redirect loops. Only top-level navigations
 * start the redirect; background requests get a {@code 401} with the {@code X-Reauthenticate}
 * header, so they cannot overwrite the session's single saved authorization request (REQ-SEC-012).
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
      writeReauthChallenge(request, response);
      return;
    }

    if (isSsoAlreadyAttempted(request)) {
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
            + " remoteAddr={} | session={}",
        uri,
        request.getRemoteAddr(),
        SessionIdFingerprint.of(request.getSession(false)));

    setSsoAttemptedCookie(response);

    String redirectUrl = request.getContextPath() + OAUTH2_AUTHORIZATION_BASE + "?prompt=none";

    log.debug("[SSO] Redirecting to silent SSO endpoint: {}", redirectUrl);
    response.sendRedirect(redirectUrl);
  }

  /**
   * Decides whether this unauthenticated request is background traffic (fetch/XHR, {@code
   * EventSource}, WebSocket) rather than a top-level navigation.
   *
   * <p>Uses {@code Sec-Fetch-Mode}, falling back to {@code X-Requested-With} and an {@code Accept}
   * of {@code application/json} / {@code text/event-stream}; anything else counts as a navigation.
   *
   * @param request the current unauthenticated request
   * @return {@code true} for background traffic that gets a 401 challenge; {@code false} for a
   *     top-level navigation
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
   * Writes a {@code 401} with the {@code X-Reauthenticate} header and the {@code REAUTH_REQUIRED}
   * JSON body, leaving the {@code SSO_ATTEMPTED} cookie and the saved OAuth2 request untouched.
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
    writeSsoAttemptedCookie(response, "1", 60);
  }

  private void clearSsoAttemptedCookie(@NotNull HttpServletResponse response) {
    log.debug("[SSO] Clearing SSO_ATTEMPTED cookie");
    writeSsoAttemptedCookie(response, "", 0);
  }

  /**
   * Emits the {@code SSO_ATTEMPTED} cookie as a {@code Secure}, {@code HttpOnly}, {@code
   * SameSite=Strict} {@link ResponseCookie}; a {@code maxAge} of {@code 0} clears it.
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
