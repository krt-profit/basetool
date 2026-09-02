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

import de.greluc.krt.profit.basetool.frontend.logging.LogSafe;
import de.greluc.krt.profit.basetool.frontend.metrics.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.WebAttributes;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;

/**
 * OAuth2 login failure handler that counts every failed login into {@code
 * basetool_login_total{outcome="failure", reason=…}} before performing the usual redirect to the
 * configured failure URL ({@code /?error}).
 *
 * <p>The {@code reason} tag is derived from the exception <b>type</b> and, for an {@link
 * OAuth2AuthenticationException}, its bounded OAuth2 error <b>code</b> — never the raw, provider-
 * supplied error description (which could be arbitrary and would blow up the metric cardinality).
 * It collapses to three buckets: {@code invalid_state} (a benign authorization-response / {@code
 * state}-validation failure raised BEFORE any token exchange — the state check failed, the callback
 * was not a valid authorization response at all, or the {@code prompt=none} silent-SSO probe found
 * no live Keycloak SSO session), {@code provider_error} (a genuine post-authorization failure — a
 * bad IdP response or a failed code-to-token exchange, the failure class {@code
 * KeycloakLoginErrorSpike}'s event regex misses) and {@code other} (a non-OAuth2 authentication
 * exception). Keeping pre-token-exchange traffic OUT of {@code provider_error} is what stops it
 * false-tripping {@code FrontendLoginBroken}; see {@link #isStateError(String)}. See {@link
 * LoginSuccessMetricsHandler} for the paired success signal (#1041 item 18, REQ-OBS-011).
 *
 * <p>Each failure is also written to the log exactly once, at the level its bucket warrants (see
 * {@link #logFailure(String, AuthenticationException)}). The handler used to be metric-only, which
 * left {@code FrontendLoginBroken} — whose own description says "check the frontend logs" —
 * pointing at a log that contained nothing about the failure: {@code org.springframework.security}
 * is pinned to {@code INFO} in {@code logback-spring.xml} / {@code application.yml}, so Spring's
 * own DEBUG lines about the failed exchange are off in every environment.
 */
@Slf4j
public class LoginFailureMetricsHandler extends SimpleUrlAuthenticationFailureHandler {

  /**
   * The bounded set of OAuth2 / OIDC error codes that denote a benign authorization-response
   * outcome. Every code in here is raised at the authorization-response stage — BEFORE any
   * code-to-token exchange — so none of them can ever be the token-exchange break {@code
   * provider_error} exists to signal. See {@link #isStateError(String)} for what each group means
   * and why leaving a code out of this set false-trips {@code FrontendLoginBroken}.
   */
  private static final Set<String> BENIGN_AUTHORIZATION_RESPONSE_ERRORS =
      Set.of(
          // The callback could not be correlated to a saved authorization request (state failure).
          "authorization_request_not_found",
          "invalid_state_parameter",
          "invalid_state",
          // The callback was not a valid authorization response at all (scanner / stale bookmark).
          "invalid_request",
          // The prompt=none silent-SSO probe found no usable Keycloak SSO session (OIDC Core
          // 3.1.2.6). SsoReAuthenticationEntryPoint issues that probe by design, so these are
          // self-inflicted and expected — not a provider fault.
          "login_required",
          "interaction_required",
          "consent_required",
          "account_selection_required");

  /**
   * Character budget for the logged OAuth2 error code. The code is bounded <em>in practice</em> (it
   * comes from the IdP), but on the authorization-response path it reaches us as the {@code error}
   * query parameter of the callback, i.e. from the browser — so it is length-capped and stripped of
   * control characters before it is logged, exactly like any other request-supplied value.
   */
  private static final int MAX_LOGGED_ERROR_CODE = 64;

  /**
   * Hop limit for the root-cause walk, so a self-referential or pathologically deep cause chain
   * cannot spin the request thread.
   */
  private static final int MAX_CAUSE_DEPTH = 16;

  private final MeterRegistry meterRegistry;

  /**
   * The redirect target, kept here because the superclass's own copy is private and its {@code
   * onAuthenticationFailure} — the only thing that reads it — is what this class replaces.
   */
  private final String failureUrl;

  /**
   * Builds the handler with the failure redirect target and the counter registry.
   *
   * @param meterRegistry the registry the {@code basetool_login_total} counter is bumped against
   * @param failureUrl the URL the user is redirected to after a failed login (e.g. {@code /?error})
   */
  public LoginFailureMetricsHandler(
      @NotNull MeterRegistry meterRegistry, @NotNull String failureUrl) {
    super(failureUrl);
    this.meterRegistry = meterRegistry;
    this.failureUrl = failureUrl;
  }

  /**
   * Counts the failed login under its mapped {@code reason}, logs it once at the level that bucket
   * warrants, then delegates to the default redirect-to-failure-URL behaviour.
   *
   * @param request the current HTTP request
   * @param response the HTTP response to redirect
   * @param exception the authentication failure that occurred
   * @throws IOException if writing the redirect fails
   * @throws ServletException if the superclass raises a servlet-layer exception
   */
  @Override
  public void onAuthenticationFailure(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull AuthenticationException exception)
      throws IOException, ServletException {
    String reason = reasonFor(exception);
    meterRegistry
        .counter(
            MetricNames.LOGIN,
            MetricNames.TAG_OUTCOME,
            MetricNames.OUTCOME_FAILURE,
            MetricNames.TAG_REASON,
            reason)
        .increment();
    logFailure(reason, exception);

    // Deliberately NOT super.onAuthenticationFailure(...): that calls the superclass's `final`
    // saveException, which parks the exception in the HTTP session. See redirectWithoutPoisoning.
    redirectWithoutPoisoningTheSession(request, response, exception);
  }

  /**
   * Performs the failure redirect without writing the exception into the session.
   *
   * <p><strong>This replaces the one line that caused the 2026-09-02 outage.</strong> {@code
   * SimpleUrlAuthenticationFailureHandler#onAuthenticationFailure} calls {@code saveException},
   * which stores the {@link AuthenticationException} under {@code SPRING_SECURITY_LAST_EXCEPTION}
   * in the session. Sessions are JSON in Redis here, and that value writes cleanly — with its
   * {@code @class} — and then <em>cannot be read back</em>: reconstruction dies on {@code
   * IllegalArgumentException: authenticationRequest cannot be null}, a field the serialized form
   * never carried. Reading a session deserializes every field, so this single attribute poisoned
   * the whole session and every later request answered HTTP 500. Every new failed login armed
   * another one, which is why clearing Redis only bought minutes.
   *
   * <p>The superclass's {@code saveException} is {@code protected final}, so it cannot be
   * overridden — the redirect is reproduced here instead, minus that one call. It is the same two
   * lines the superclass runs for the redirect case, using the same {@link
   * org.springframework.security.web.RedirectStrategy} so a configured strategy still applies.
   *
   * <p><strong>Nothing is lost.</strong> This handler redirects to its failure URL and the UI reads
   * the <em>query parameter</em> ({@code param.error} in {@code fragments/toast.html}), never the
   * session attribute. The diagnostic value is captured before this runs: the login-failure counter
   * carries the reason bucket and {@link #logFailure} writes the single log line. The attribute was
   * written, never read, and able to take the site down.
   *
   * <p>It is still set as a <em>request</em> attribute, exactly as the superclass does on its
   * forward path, so anything inspecting it within this request still finds it. A request attribute
   * is never serialized.
   *
   * @param request the current request.
   * @param response the response to redirect.
   * @param exception the failure, kept for this request only.
   * @throws IOException if writing the redirect fails.
   */
  private void redirectWithoutPoisoningTheSession(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull AuthenticationException exception)
      throws IOException {
    request.setAttribute(WebAttributes.AUTHENTICATION_EXCEPTION, exception);
    getRedirectStrategy().sendRedirect(request, response, failureUrl);
  }

  /**
   * Writes the single log line for this failure: WARN for {@link
   * MetricNames#LOGIN_REASON_PROVIDER_ERROR} (the bucket {@code FrontendLoginBroken} fires on — a
   * failed code-to-token exchange, a JWKS/IdP fault, an explicit refusal), DEBUG for {@link
   * MetricNames#LOGIN_REASON_INVALID_STATE} and {@link MetricNames#LOGIN_REASON_OTHER}. The benign
   * buckets are attacker- and scanner-driven by construction — every bare hit on {@code
   * /login/oauth2/code/*} and every {@code prompt=none} probe without a Keycloak SSO cookie lands
   * there — so anything above DEBUG would be a log-flood vector (REQ-OBS-001).
   *
   * <p><b>Abuse envelope of the WARN.</b> {@code provider_error} is not purely operator-triggered:
   * {@code access_denied} is deliberately mapped into it (see {@link #isStateError(String)}), and
   * that code is <em>mintable</em> by anyone with a browser through a two-request loop — start an
   * authorization request, then replay the callback with {@code error=access_denied} and the
   * matching {@code state}. A determined caller can therefore drive this WARN at request rate. That
   * is accepted rather than fixed here for one reason: it is the <em>same</em> envelope the
   * existing {@code basetool_login_total{reason="provider_error"}} counter and the {@code
   * FrontendLoginBroken} alert already carry, so the log line adds no new exposure — it only makes
   * an alert that already fires on this traffic explainable. A reader triaging a WARN burst must
   * know that a flood of them with {@code oauth2ErrorCode=access_denied} is consistent with abuse
   * and not, by itself, evidence that login is broken. If that envelope is ever tightened, tighten
   * the metric and the alert with it, not just this line.
   *
   * <p>Deliberately absent from the message: {@code OAuth2Error.getDescription()}
   * (provider-supplied free text and therefore a log-injection surface — it never reaches a logger,
   * sanitised or not), the {@code code} and {@code state} request parameters, any token, and the
   * principal. The only two payload fields are the length-capped, control-character-stripped OAuth2
   * error code and the root cause's class simple name, which is what tells a TLS/DNS/connect fault
   * apart from a real {@code invalid_grant}.
   *
   * @param reason the bucket {@link #reasonFor(AuthenticationException)} mapped this failure to
   * @param exception the authentication failure being reported
   */
  private static void logFailure(
      @NotNull String reason, @NotNull AuthenticationException exception) {
    String errorCode = errorCodeOf(exception);
    String rootCause = rootCauseType(exception);
    if (MetricNames.LOGIN_REASON_PROVIDER_ERROR.equals(reason)) {
      log.warn(
          "OAuth2 login failed: reason={}, oauth2ErrorCode={}, rootCause={}",
          reason,
          errorCode,
          rootCause);
      return;
    }
    log.debug(
        "OAuth2 login failed: reason={}, oauth2ErrorCode={}, rootCause={}",
        reason,
        errorCode,
        rootCause);
  }

  /**
   * Renders the OAuth2 error code of the failure for the log line, or {@link LogSafe#NONE} when the
   * failure is not an {@link OAuth2AuthenticationException} or carries no {@link
   * org.springframework.security.oauth2.core.OAuth2Error}. Never touches the error description.
   *
   * @param exception the authentication failure being reported
   * @return the sanitised, length-capped error code, or {@code none}
   */
  @NotNull
  private static String errorCodeOf(@NotNull AuthenticationException exception) {
    if (exception instanceof OAuth2AuthenticationException oauth2 && oauth2.getError() != null) {
      return LogSafe.text(oauth2.getError().getErrorCode(), MAX_LOGGED_ERROR_CODE);
    }
    return LogSafe.NONE;
  }

  /**
   * Walks the cause chain to its end and returns that throwable's class simple name — the field
   * that separates "Keycloak answered with an error" from "we never reached Keycloak" (a {@code
   * ConnectException} / {@code SSLHandshakeException} / {@code PrematureCloseException} at the
   * bottom of the chain). The walk is hop-bounded and stops on a self-reference, so a cyclic chain
   * cannot hang the request thread. Only the type name is used; the message is never logged,
   * because it can carry the provider's free-text description.
   *
   * @param exception the authentication failure being reported
   * @return the simple class name of the deepest cause, or of {@code exception} itself when it has
   *     none
   */
  @NotNull
  private static String rootCauseType(@NotNull Throwable exception) {
    Throwable current = exception;
    for (int hop = 0; hop < MAX_CAUSE_DEPTH; hop++) {
      Throwable cause = current.getCause();
      if (cause == null || cause == current) {
        break;
      }
      current = cause;
    }
    return current.getClass().getSimpleName();
  }

  /**
   * Maps an authentication exception to one of the three bounded failure reasons. Only the OAuth2
   * error <b>code</b> is inspected (a bounded value), never the free-text description.
   *
   * @param exception the authentication failure
   * @return {@link MetricNames#LOGIN_REASON_INVALID_STATE}, {@link
   *     MetricNames#LOGIN_REASON_PROVIDER_ERROR} or {@link MetricNames#LOGIN_REASON_OTHER}
   */
  static String reasonFor(@NotNull AuthenticationException exception) {
    if (exception instanceof OAuth2AuthenticationException oauth2) {
      String code = oauth2.getError() != null ? oauth2.getError().getErrorCode() : null;
      return isStateError(code)
          ? MetricNames.LOGIN_REASON_INVALID_STATE
          : MetricNames.LOGIN_REASON_PROVIDER_ERROR;
    }
    return MetricNames.LOGIN_REASON_OTHER;
  }

  /**
   * Recognises the OAuth2 error codes Spring Security raises at the authorization-response / {@code
   * state}-validation stage — BEFORE any code-to-token exchange — so they are benign and must never
   * inflate {@code provider_error}. Two shapes land here:
   *
   * <ul>
   *   <li>the saved authorization request could not be correlated to the callback (the state check
   *       failed / the request was lost): {@code authorization_request_not_found}, {@code
   *       invalid_state_parameter}, {@code invalid_state};
   *   <li>the callback was not a valid authorization response at all: {@code invalid_request}.
   *       {@code OAuth2LoginAuthenticationFilter} throws this from {@code attemptAuthentication}
   *       when {@code isAuthorizationResponse(params)} fails (a bare or partial hit to the
   *       path-only-matched {@code /login/oauth2/code/*} callback — no {@code code}/{@code state}),
   *       i.e. exactly the traffic a scanner/probe or a stale bookmark generates. It is raised
   *       before the authorization-request lookup and before the {@code AuthenticationManager}
   *       runs, so it can NEVER be a token-exchange failure. Leaving it out of this set let those
   *       malformed-callback hits fall through to {@code provider_error} and, off-peak with no
   *       fresh successes, trip {@code FrontendLoginBroken} with nothing actually broken;
   *   <li>the {@code prompt=none} silent-SSO probe found no usable Keycloak SSO session: {@code
   *       login_required}, {@code interaction_required}, {@code consent_required}, {@code
   *       account_selection_required} (the OIDC Core 3.1.2.6 {@code prompt=none} error set). {@link
   *       SsoReAuthenticationEntryPoint} sends every unauthenticated top-level navigation through
   *       that probe by design, and Keycloak answers {@code login_required} whenever the browser
   *       carries no live SSO cookie — so this is the single most frequent login failure the app
   *       generates about itself. Keycloak returns it as an <em>authorization-response</em> error
   *       (the callback arrives with {@code error=…&state=…} and no {@code code}), which {@code
   *       OAuth2LoginAuthenticationProvider} rethrows as an {@link OAuth2AuthenticationException}
   *       before any token request is made — so, like {@code invalid_request}, it can never be a
   *       token-exchange failure. This is the 2026-07-28 fix: {@code login_required} used to fall
   *       through to {@code provider_error}, so a scanner walking unauthenticated paths ({@code
   *       /blog/}, {@code /wp/}, {@code /old/}, …) minted one {@code provider_error} per probe and
   *       tripped {@code FrontendLoginBroken} overnight with login perfectly healthy.
   * </ul>
   *
   * <p>Anything else — {@code invalid_grant}, {@code server_error}, {@code invalid_token_response},
   * … — is a genuine post-authorization token/IdP failure and correctly maps to {@code
   * provider_error}, the class the {@code FrontendLoginBroken} alert exists to catch. {@code
   * access_denied} is deliberately NOT folded in: it is also an authorization-response error, but
   * it means an explicit refusal (a user declining consent, or a client/IdP policy rejecting the
   * request) rather than routine "no session yet" noise, and it is rare enough that surfacing it is
   * worth more than the alert quiet.
   *
   * @param code the OAuth2 error code, or {@code null}
   * @return {@code true} when the code denotes a benign authorization-response / state failure
   */
  private static boolean isStateError(@Nullable String code) {
    return code != null && BENIGN_AUTHORIZATION_RESPONSE_ERRORS.contains(code);
  }
}
