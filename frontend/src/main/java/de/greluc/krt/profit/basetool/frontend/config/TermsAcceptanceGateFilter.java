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
    if (isTestProfile() || isExempt(request) || !isAuthenticated() || hasAccepted(request)) {
      filterChain.doFilter(request, response);
      return;
    }
    String consentUrl = request.getContextPath() + CONSENT_PATH;
    if (isEventStream(request)) {
      // An EventSource can read neither a status code nor a response header, so the X-Terms-
      // Acceptance-Required contract below is invisible to it and a redirect is actively harmful:
      // the stream receives the consent page as text/html, fails to parse it, and notifications.js
      // reconnects on its own jittered timer — indefinitely. Measured in production on 2026-08-03:
      // 491 stream attempts and 483 consent-page loads in ten minutes, from open tabs alone.
      //
      // Exempting the path would only move the loop one hop: the relay would then reach the backend
      // boundary, take the 403 there, and error just the same. So answer ON the channel, which is
      // the one thing the client can act on — a single `terms-gate` event carrying the consent URL,
      // then close. This mirrors the `reauth` handoff the stream already implements for a lost
      // OAuth2 token (REQ-SEC-012, REQ-NOTIF-010).
      log.debug("Consent missing; handing the SSE stream off to the consent page");
      writeTermsGateEvent(response, consentUrl);
      return;
    }
    if (isAjax(request)) {
      // A 302 is wrong for an XHR and fails SILENTLY, which is the worst of both worlds: krtFetch's
      // swap sees `res.redirected` and bails with a dev-only warning, so the section simply stops
      // updating; a write follows the redirect, gets the consent page as 200 text/html, and shows a
      // generic error toast. Neither tells the user what happened. This is the case of a tab that
      // was already open when a new wording deployed — i.e. exactly when the feature first does
      // anything at all.
      //
      // So mirror the contract the codebase already has for a lost OAuth2 token (REQ-SEC-012): a
      // status plus a header the client acts on, and let krtFetch navigate. Sending the target in
      // the header rather than hardcoding it client-side keeps the context path correct.
      log.debug("Consent missing; signalling the gate to the AJAX caller");
      response.setStatus(HttpServletResponse.SC_FORBIDDEN);
      response.setHeader(TERMS_GATE_HEADER, consentUrl);
      return;
    }
    if (isWebSocketUpgrade(request)) {
      // A WebSocket handshake has exactly one useful answer, and it is not the usual one here.
      // A refused upgrade — 302, 403, anything but 101 — reaches the browser's WebSocket
      // object as `close` with code 1006 and no reason, which is byte-for-byte what a dropped
      // connection looks like; the client cannot tell "you must consent" from "the wifi blinked",
      // so it does the only correct thing for the latter and reconnects. krt-live-sync.js does that
      // on full-jitter backoff capped at 30 s, for as long as any topic is registered, and consent
      // can never be given from a background socket: the loop has no exit. That is the same defect
      // the SSE stream had before REQ-SEC-028's channel handoff, and the same fix does not transfer
      // — at handshake time there is no channel yet to write an event on.
      //
      // So let the upgrade complete and refuse the socket where a close CODE exists: the handshake
      // is marked here, LiveSyncSyncHandshakeInterceptor copies the mark onto the session, and
      // LiveSyncWebSocketHandler closes it immediately with 4003 carrying this URL — the shape the
      // per-user socket cap's 4029 already established. Marking rather than exempting is what keeps
      // the verdict in one place: the gate's own 60 s-bounded read decides, and the relay only
      // relays it.
      log.debug("Consent missing; marking the WebSocket handshake for a terminal refusal");
      TermsGateHandoff.mark(request, consentUrl);
      filterChain.doFilter(request, response);
      return;
    }
    log.debug("Consent missing; routing {} to the consent page", request.getRequestURI());
    response.sendRedirect(consentUrl);
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
  private static boolean isAjax(HttpServletRequest request) {
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
   * Answers an SSE subscription with a single {@code terms-gate} event naming the consent page,
   * then lets the response complete so the stream closes.
   *
   * <p>Deliberately a {@code 200}: an {@code EventSource} surfaces any error status as an opaque
   * {@code onerror} that is indistinguishable from a dropped connection, which is precisely what
   * makes it reconnect. A well-formed event is the only way to tell the client something it can act
   * on rather than retry.
   *
   * @param response the response to write the event into
   * @param consentUrl the context-relative consent-page path the client should navigate to
   * @throws IOException if writing the event fails
   */
  private static void writeTermsGateEvent(
      @NotNull HttpServletResponse response, @NotNull String consentUrl) throws IOException {
    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    // Proxies that buffer would hold the event until the stream closes anyway, but an explicit
    // no-cache keeps an intermediary from serving this one-shot answer to a later subscription.
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
    PrintWriter writer = response.getWriter();
    writer.write("event: " + SSE_GATE_EVENT + "\ndata: " + consentUrl + "\n\n");
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
  private boolean hasAccepted(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    long now = System.currentTimeMillis();
    if (session != null && Boolean.TRUE.equals(session.getAttribute(SESSION_ACCEPTED))) {
      Object checkedAt = session.getAttribute(SESSION_CHECKED_AT);
      if (checkedAt instanceof Long millis && now - millis < RECHECK_MILLIS) {
        return true;
      }
    }
    // A fresh negative is honoured too, not just a fresh positive. Storing the verdict but reading
    // back only one side made every gated request a blocking backend round trip: during the
    // 2026-08-03 rollout 491 stream attempts produced 491 reads of /api/v1/terms/status, and the
    // consent-page renders they triggered produced 483 more. The cache is bounded by the same
    // RECHECK_MILLIS as the positive one, and recording consent calls clearCachedVerdict, so a user
    // who accepts is never held behind a stale "no".
    if (consentKnownMissing(request)) {
      return false;
    }
    boolean accepted;
    try {
      TermsStatusDto status = backendApiClient.get(TERMS_STATUS_URI, TermsStatusDto.class);
      accepted = status == null || status.accepted();
    } catch (BackendServiceException e) {
      // Already logged at the BackendApiClient boundary (REQ-OBS-001).
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
   * <p>Three groups, each for its own reason. The consent page and its POST, or the gate loops.
   * Logout and the OAuth endpoints, or a user who declines cannot leave. And the imprint, the
   * privacy policy and the public terms page, because nobody can be asked to agree to something
   * they are prevented from reading — that last group is the one a careless allowlist drops, and
   * dropping it makes the gate legally self-defeating.
   *
   * @param request the current request
   * @return {@code true} when the request is exempt from the consent redirect
   */
  private static boolean isExempt(HttpServletRequest request) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    return path.equals(CONSENT_PATH)
        || path.equals("/terms")
        || path.equals("/privacy")
        || path.equals("/impressum")
        || path.startsWith("/pending-approval")
        || path.startsWith("/logout")
        || path.startsWith("/oauth2")
        || path.startsWith("/login")
        || path.startsWith("/error")
        || path.startsWith("/actuator")
        || isStaticAsset(path);
  }

  /**
   * Whether the path targets a static asset, which the filter skips entirely so a page load does
   * not turn each of its CSS/JS/font requests into a candidate backend read.
   *
   * @param path the context-relative request path
   * @return {@code true} for static-asset paths
   */
  private static boolean isStaticAsset(String path) {
    return path.startsWith("/css/")
        || path.startsWith("/js/")
        || path.startsWith("/images/")
        || path.startsWith("/logos/")
        || path.startsWith("/fonts/")
        || path.equals("/favicon.ico")
        || path.endsWith(".map");
  }
}
