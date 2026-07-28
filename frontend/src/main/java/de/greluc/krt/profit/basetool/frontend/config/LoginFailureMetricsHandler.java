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

import de.greluc.krt.profit.basetool.frontend.metrics.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
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
 */
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

  private final MeterRegistry meterRegistry;

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
  }

  /**
   * Counts the failed login under its mapped {@code reason}, then delegates to the default
   * redirect-to-failure-URL behaviour.
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
    meterRegistry
        .counter(
            MetricNames.LOGIN,
            MetricNames.TAG_OUTCOME,
            MetricNames.OUTCOME_FAILURE,
            MetricNames.TAG_REASON,
            reasonFor(exception))
        .increment();
    super.onAuthenticationFailure(request, response, exception);
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
