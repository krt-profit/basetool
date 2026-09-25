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
 * <p>This filter is <strong>UX, not the access boundary</strong>. The boundary is the backend's own
 * {@code TermsAcceptanceAccessFilter}, which refuses the API regardless of how the caller got
 * there; this one exists so a member meets a page that explains itself instead of a wall of failed
 * fragment loads. Skipping it would be a worse experience, not a security hole.
 *
 * <p><strong>The cache is short on purpose.</strong> An authenticated session lives 30 days
 * (ADR-0088), so caching "this user has accepted" for the session lifetime would mean a terms
 * change never re-prompts anyone who happened to be logged in — the one thing the whole feature
 * exists to do. The answer is therefore re-read every {@link #RECHECK_MILLIS}, which bounds both
 * the backend load and how long a stale "already accepted" can survive a wording change. The read
 * is cheap: the backend answers it from its own in-memory cache.
 *
 * <p>Runs after {@code BackendRoleSyncFilter} so a still-pending registration meets the approval
 * waiting page rather than being bounced between two gates.
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
   * Response header carrying the login URL to an AJAX caller. The established contract for a lost
   * OAuth2 token (REQ-SEC-012), written here verbatim as {@code GlobalExceptionHandler} writes it,
   * because {@code krtFetch} keys on the exact name.
   */
  static final String REAUTH_HEADER = "X-Reauthenticate";

  /** Session attribute holding the epoch millis at which the cached "accepted" was read. */
  static final String SESSION_CHECKED_AT = "krt.terms.checkedAt";

  /** Session attribute holding the cached verdict. */
  static final String SESSION_ACCEPTED = "krt.terms.accepted";

  /**
   * How long a positive verdict may be reused before the backend is asked again. Deliberately far
   * below the session lifetime — see the class comment.
   */
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
   * Answers a request whose OAuth2 token can no longer be refreshed, in the shape the caller can
   * act on.
   *
   * <p>Three transports, the same three distinctions the consent path above draws and for the same
   * reasons: an {@code EventSource} can read neither a status code nor a header, so it is handed a
   * named event on its own channel; an XHR must not be 302'd, because {@code krtFetch} sees {@code
   * res.redirected} and stalls silently; a navigation simply goes to the login.
   *
   * <p>Every value here is the one the codebase already uses for a lost token — the {@code reauth}
   * event name from {@code NotificationPageController}, the {@code X-Reauthenticate} header from
   * {@code GlobalExceptionHandler} — so no client-side listener has to learn anything new.
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
   * Whether this request is a WebSocket upgrade rather than a navigation or an XHR.
   *
   * <p>Keyed on the {@code Upgrade} header, not on the path, for two reasons. It is what actually
   * identifies the caller's idiom — the reason the answer below has to differ — and the path
   * alternative would have to be a raw-URI string test (REQ-SEC-029 keeps this filter's exemption
   * list raw on purpose), which an encoded spelling of {@code /ws/sync} would slip past and land
   * back in the redirect loop. A browser cannot set custom headers on a handshake, so this can
   * never collide with the {@code X-Requested-With} branch above.
   *
   * @param request the current request
   * @return {@code true} when the caller is opening a WebSocket
   */
  private static boolean isWebSocketUpgrade(@NotNull HttpServletRequest request) {
    return "websocket".equalsIgnoreCase(request.getHeader(HttpHeaders.UPGRADE));
  }

  /**
   * Whether this is one of {@code krtFetch}'s XHR calls rather than a browser navigation.
   *
   * <p>{@code krtFetch} sets {@code X-Requested-With} on writes and on fragment swaps alike, so the
   * one header distinguishes both from a real navigation.
   *
   * @param request the current request
   * @return {@code true} for an AJAX call
   */
  private static boolean isAjax(@NotNull HttpServletRequest request) {
    return "XMLHttpRequest".equals(request.getHeader("X-Requested-With"));
  }

  /**
   * Whether this request is an {@code EventSource} subscription rather than a navigation or an XHR.
   *
   * <p>Keyed on {@code Accept} alone: every browser stamps {@code text/event-stream} on an {@code
   * EventSource} handshake, and nothing else in this application sends it. {@code Sec-Fetch-Mode}
   * would also identify it as background traffic, but not specifically as a stream — and the
   * distinction matters here, because the answer written below is only parseable by a client that
   * actually speaks SSE.
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
   * Answers an SSE subscription with a single named event carrying one URL, then lets the response
   * complete so the stream closes.
   *
   * <p>Deliberately a {@code 200}: an {@code EventSource} surfaces any error status as an opaque
   * {@code onerror} that is indistinguishable from a dropped connection, which is precisely what
   * makes it reconnect. A well-formed event is the only way to tell the client something it can act
   * on rather than retry.
   *
   * <p>Two callers, one shape: {@link #SSE_GATE_EVENT} hands the stream to the consent page, {@link
   * #SSE_REAUTH_EVENT} hands it to the login flow. {@code notifications.js} listens for both and
   * stops reconnecting on either.
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
   * Discards this session's cached verdict, so the next request re-reads it from the backend.
   *
   * <p>Called after consent is recorded. Without it the very next request still sees the stale "not
   * accepted" — which re-checks the gate and keeps {@link BackendRoleSyncFilter} skipping a sync
   * that would now succeed, for up to {@link #RECHECK_MILLIS}. The attribute names stay private to
   * this filter; callers ask it to forget rather than reaching into its session keys.
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
   * Whether this session is known to be missing consent, from the verdict this filter cached.
   *
   * <p>Exists so {@code BackendRoleSyncFilter} can skip a backend read that cannot succeed: it runs
   * <em>before</em> this filter, and every one of its {@code /api/v1/users/me} calls is refused
   * with {@code 403 TERMS_NOT_ACCEPTED} while the gate is closed. Its failure path deliberately
   * leaves the sync stamp unset so the next request retries — which, after a wording change, is one
   * futile round trip per non-static request for every member at once.
   *
   * <p>Conservative on purpose: only a cached, still-fresh {@code false} counts. An absent or
   * expired entry answers {@code false} here, so the sync runs — this only ever skips work already
   * known to be pointless, and never suppresses a sync on a guess.
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
   * <p>Same carve-out and same reason as the backend boundary: MockMvc callers are synthetic
   * subjects with no acceptance row, so leaving the redirect armed would bounce unrelated page
   * tests. It also stops this filter's extra {@code /api/v1/terms/status} round trip from reaching
   * broadly-stubbed {@code BackendApiClient} mocks, which answer it with whatever DTO that test
   * happens to stub and fail with a ClassCastException. This filter is UX, never the boundary — the
   * backend enforces regardless — so standing it down in tests costs no coverage of the actual
   * rule, and TermsAcceptanceGateFilterTest drives it directly.
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
   * #RECHECK_MILLIS}.
   *
   * <p>A backend failure resolves to {@code true} — the user is let through. That is the deliberate
   * direction: this filter is not the boundary (the backend enforces independently), so failing
   * open here costs nothing in security, while failing closed would turn a backend hiccup into
   * "every member is stuck on the consent page and accepting does not help either", because
   * recording consent needs the same backend.
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
   * Whether the request must NOT be redirected.
   *
   * <p>Two groups. <strong>This gate's own</strong> — the consent page and its POST, or the gate
   * loops; the waiting page, which a member can be sent to while still unconsented; and the
   * imprint, the privacy policy and the public terms page, because nobody can be asked to agree to
   * something they are prevented from reading. That last one is what a careless allowlist drops,
   * and dropping it makes the gate legally self-defeating.
   *
   * <p><strong>And everything {@link PublicPaths#isGateExempt} covers</strong> — assets, public
   * documents and the authentication plumbing — shared with {@link BackendRoleSyncFilter} rather
   * than restated here. The two lists used to be separate copies of the same predicates, which is
   * how {@code /.well-known/assetlinks.json} ended up {@code permitAll} in {@code SecurityConfig}
   * and exempt in neither gate.
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
