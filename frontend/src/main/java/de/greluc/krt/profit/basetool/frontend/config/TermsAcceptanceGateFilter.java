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

import de.greluc.krt.profit.basetool.frontend.exception.ReauthenticationRequiredException;
import de.greluc.krt.profit.basetool.frontend.model.dto.TermsStatusDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.support.TermsGateHandoff;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Routes a user who has not accepted the Terms of Use to the consent page (REQ-SEC-028).
 *
 * <p>This is UX only; the backend's {@code TermsAcceptanceAccessFilter} is the boundary. The
 * verdict is cached per session for {@link #RECHECK_MILLIS}. Runs after {@code
 * BackendRoleSyncFilter}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TermsAcceptanceGateFilter extends OncePerRequestFilter {

  /** Where a user without consent is sent. */
  public static final String CONSENT_PATH = "/terms/accept";

  /**
   * Response header carrying the consent-page URL to an AJAX caller, mirroring the {@code
   * X-Reauthenticate} contract for a lost OAuth2 token (REQ-SEC-012). {@code krtFetch} navigates
   * the browser when it sees this, instead of stalling a swap or toasting a generic write error.
   */
  static final String TERMS_GATE_HEADER = "X-Terms-Acceptance-Required";

  /**
   * Name of the one-shot SSE event that hands an {@code EventSource} off to the consent page,
   * carrying that page's URL as its data. The sibling of the stream's existing {@code reauth}
   * event; {@code notifications.js} listens for both and stops reconnecting on either.
   */
  public static final String SSE_GATE_EVENT = "terms-gate";

  /**
   * Name of the one-shot SSE event that hands an {@code EventSource} off to the login flow. The
   * same name {@code NotificationPageController} writes when the stream itself loses its token, so
   * {@code notifications.js} needs no new listener.
   */
  static final String SSE_REAUTH_EVENT = "reauth";

  /**
   * Response header carrying the login URL to an AJAX caller, identical to the one {@code
   * GlobalExceptionHandler} writes (REQ-SEC-012).
   */
  static final String REAUTH_HEADER = "X-Reauthenticate";

  /** Session attribute holding the epoch millis at which the cached "accepted" was read. */
  static final String SESSION_CHECKED_AT = "krt.terms.checkedAt";

  /** Session attribute holding the cached verdict. */
  static final String SESSION_ACCEPTED = "krt.terms.accepted";

  /** How long a positive verdict may be reused before the backend is asked again. */
  static final long RECHECK_MILLIS = 60_000L;

  /** Backend endpoint reporting whether the caller has accepted the wording in force. */
  private static final String TERMS_STATUS_URI = "/api/v1/terms/status";

  private final BackendApiClient backendApiClient;
  private final org.springframework.core.env.Environment environment;

  @Override
  protected void doFilterInternal(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull FilterChain filterChain)
      throws ServletException, IOException {
    if (isExempt(request)) {
      filterChain.doFilter(request, response);
      return;
    }
    boolean mayProceed;
    try {
      mayProceed = isTestProfile() || !isAuthenticated() || hasAccepted(request);
    } catch (ReauthenticationRequiredException e) {
      if (isWebSocketUpgrade(request)) {
        filterChain.doFilter(request, response);
        return;
      }
      sendReauthentication(request, response);
      return;
    }
    if (mayProceed) {
      filterChain.doFilter(request, response);
      return;
    }
    String consentUrl = request.getContextPath() + CONSENT_PATH;
    if (isEventStream(request)) {
      log.debug("Consent missing; handing the SSE stream off to the consent page");
      writeOneShotEvent(response, SSE_GATE_EVENT, consentUrl);
      return;
    }
    if (isAjax(request)) {
      log.debug("Consent missing; signalling the gate to the AJAX caller");
      response.setStatus(HttpServletResponse.SC_FORBIDDEN);
      response.setHeader(TERMS_GATE_HEADER, consentUrl);
      return;
    }
    if (isWebSocketUpgrade(request)) {
      log.debug("Consent missing; marking the WebSocket handshake for a terminal refusal");
      TermsGateHandoff.mark(request, consentUrl);
      filterChain.doFilter(request, response);
      return;
    }
    log.debug("Consent missing; routing {} to the consent page", request.getRequestURI());
    response.sendRedirect(consentUrl);
  }

  /**
   * Answers a request whose OAuth2 token can no longer be refreshed: a {@code reauth} event for an
   * {@code EventSource}, a {@code 401} with {@code X-Reauthenticate} for an XHR, a redirect to the
   * login for a navigation.
   *
   * @param request the request whose session can no longer produce a token; never {@code null}
   * @param response the response to write the handoff onto; never {@code null}
   * @throws IOException if the response cannot be written
   */
  private static void sendReauthentication(
      @NotNull HttpServletRequest request, @NotNull HttpServletResponse response)
      throws IOException {
    String reauthUrl = request.getContextPath() + ReauthenticationRequiredException.REAUTH_PATH;
    if (isEventStream(request)) {
      log.debug("Token unrefreshable; handing the SSE stream off to the login flow");
      writeOneShotEvent(response, SSE_REAUTH_EVENT, reauthUrl);
      return;
    }
    if (isAjax(request)) {
      log.debug("Token unrefreshable; signalling re-authentication to the AJAX caller");
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setHeader(REAUTH_HEADER, reauthUrl);
      return;
    }
    log.warn(
        "Token unrefreshable for {} {}; redirecting to the login flow.",
        request.getMethod(),
        request.getRequestURI());
    response.sendRedirect(reauthUrl);
  }

  /**
   * Whether this request is a WebSocket upgrade, determined by the {@code Upgrade} header.
   *
   * @param request the current request
   * @return {@code true} when the caller is opening a WebSocket
   */
  private static boolean isWebSocketUpgrade(@NotNull HttpServletRequest request) {
    return "websocket".equalsIgnoreCase(request.getHeader(HttpHeaders.UPGRADE));
  }

  /**
   * Whether this is a {@code krtFetch} XHR call, marked by {@code X-Requested-With}, rather than a
   * browser navigation.
   *
   * @param request the current request
   * @return {@code true} for an AJAX call
   */
  private static boolean isAjax(@NotNull HttpServletRequest request) {
    return "XMLHttpRequest".equals(request.getHeader("X-Requested-With"));
  }

  /**
   * Whether this request is an {@code EventSource} subscription, determined by an {@code Accept} of
   * {@code text/event-stream}.
   *
   * @param request the current request
   * @return {@code true} when the caller subscribes to an SSE stream
   */
  private static boolean isEventStream(@NotNull HttpServletRequest request) {
    String accept = request.getHeader(HttpHeaders.ACCEPT);
    return accept != null
        && accept.toLowerCase(Locale.ROOT).contains(MediaType.TEXT_EVENT_STREAM_VALUE);
  }

  /**
   * Answers an SSE subscription with a single named event carrying one URL and a {@code 200}, then
   * completes the response, so the client navigates instead of reconnecting.
   *
   * @param response the response to write the event into
   * @param eventName the SSE event name the client listens for
   * @param url the context-relative path the client should navigate to
   * @throws IOException if writing the event fails
   */
  private static void writeOneShotEvent(
      @NotNull HttpServletResponse response, @NotNull String eventName, @NotNull String url)
      throws IOException {
    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
    PrintWriter writer = response.getWriter();
    writer.write("event: " + eventName + "\ndata: " + url + "\n\n");
    writer.flush();
  }

  /**
   * Discards this session's cached verdict so the next request re-reads it from the backend; called
   * after consent is recorded.
   *
   * @param request the current request, whose session holds the cached verdict
   */
  public static void clearCachedVerdict(@NotNull HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    if (session != null) {
      session.removeAttribute(SESSION_ACCEPTED);
      session.removeAttribute(SESSION_CHECKED_AT);
    }
  }

  /**
   * Whether a fresh cached verdict says this session lacks consent, letting {@link
   * BackendRoleSyncFilter} skip a backend read that would be refused. An absent or expired verdict
   * answers {@code false}.
   *
   * @param request the current request
   * @return {@code true} when a fresh cached verdict says consent is missing
   */
  static boolean consentKnownMissing(@NotNull HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    if (session == null || !Boolean.FALSE.equals(session.getAttribute(SESSION_ACCEPTED))) {
      return false;
    }
    Object checkedAt = session.getAttribute(SESSION_CHECKED_AT);
    return checkedAt instanceof Long millis && System.currentTimeMillis() - millis < RECHECK_MILLIS;
  }

  /**
   * Whether the {@code test} profile is active, in which case this filter stands down entirely.
   *
   * @return {@code true} when the {@code test} profile is active
   */
  private boolean isTestProfile() {
    for (String profile : environment.getActiveProfiles()) {
      if ("test".equals(profile)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Reports whether the current session may proceed, consulting the backend at most once per {@link
   * #RECHECK_MILLIS}. A backend failure lets the user through.
   *
   * @param request the current request, whose session carries the cached verdict
   * @return {@code true} when the user may proceed
   */
  private boolean hasAccepted(@NotNull HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    long now = System.currentTimeMillis();
    if (session != null && Boolean.TRUE.equals(session.getAttribute(SESSION_ACCEPTED))) {
      Object checkedAt = session.getAttribute(SESSION_CHECKED_AT);
      if (checkedAt instanceof Long millis && now - millis < RECHECK_MILLIS) {
        return true;
      }
    }
    if (consentKnownMissing(request)) {
      return false;
    }
    boolean accepted;
    try {
      TermsStatusDto status = backendApiClient.get(TERMS_STATUS_URI, TermsStatusDto.class);
      accepted = status == null || status.accepted();
    } catch (BackendServiceException e) {
      log.debug("Consent status unreadable; letting the request through (backend still enforces).");
      return true;
    }
    if (session != null) {
      session.setAttribute(SESSION_ACCEPTED, accepted);
      session.setAttribute(SESSION_CHECKED_AT, now);
    }
    return accepted;
  }

  /**
   * Whether a JWT/OAuth2-authenticated user is behind this request. An anonymous visitor has no
   * account to record consent against and must keep reaching the public pages.
   *
   * @return {@code true} for an authenticated, non-anonymous principal
   */
  private static boolean isAuthenticated() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    return auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal());
  }

  /**
   * Whether the request is exempt from the consent redirect: the consent and waiting pages, the
   * imprint, privacy policy and public terms page, and everything {@link PublicPaths#isGateExempt}
   * covers.
   *
   * @param request the current request
   * @return {@code true} when the request is exempt from the consent redirect
   */
  private static boolean isExempt(HttpServletRequest request) {
    String path = PublicPaths.relativePath(request);
    return path.equals(CONSENT_PATH)
        || path.startsWith("/pending-approval")
        || PublicPaths.isGateExempt(path);
  }
}
