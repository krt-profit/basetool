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

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.web.header.HeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

/**
 * The frontend's security-response-header policy — the per-request {@code Content-Security-Policy}
 * (nonce-gated {@code script-src}/{@code style-src} plus a Keycloak-aware {@code form-action}),
 * {@code X-Frame-Options: DENY}, {@code Referrer-Policy}, Cross-Origin-Opener/Resource-Policy,
 * HSTS, {@code Permissions-Policy} and {@code X-Content-Type-Options} — extracted verbatim from
 * {@code SecurityConfig.filterChain} (audit L-tier config de-bloat, #15) so the filter chain wires
 * this self-contained concern with a single {@link #frontend(String)} call rather than an inline
 * ~40-line lambda plus its two supporting methods and the CSP template.
 *
 * <p>Stateless and static-only; the emitted headers are byte-identical to the previous inline
 * configuration and pinned by {@code SecurityHeadersTest}.
 */
@Slf4j
public final class SecurityHeaders {

  private SecurityHeaders() {}

  // CSP migration milestone: the ~200 inline event-handler attributes (onclick="…",
  // onchange="…", onsubmit="…", oninput="…", onkeyup="…") that historically pinned us to a
  // {@code script-src-attr 'unsafe-inline'} allowance have been moved to delegated handlers
  // via the {@code data-trigger}-based dispatcher in {@code event-delegation.js} +
  // {@code common-handlers.js} and per-page {@code krtEvents.on(...)} bindings in template
  // {@code <script th:attr="nonce=${cspNonce}">} blocks. With zero inline {@code on*=}
  // attributes remaining in the templates ({@code grep} verified), the policy drops
  // {@code script-src-attr} entirely — the directive defaults to {@code 'none'} when omitted
  // (CSP3 spec, MDN <a href=
  // "https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Content-Security-Policy/script-src-attr"
  // >script-src-attr</a>), which slams the door on stored-XSS via a future template that
  // accidentally re-introduces an inline event handler. {@code <script>} elements stay
  // nonce-gated through {@code script-src} below — unchanged.
  // Audit findings M-9 + M-10:
  //   * M-10: dropped the broad {@code https:} fallback from {@code script-src}. Browsers that
  //     understand {@code 'strict-dynamic'} (Chrome 52+, Firefox 52+, Safari 15.4+) ignore the
  //     fallback anyway, but older browsers fell back to "any HTTPS origin loads scripts" — that
  //     widened the policy unnecessarily for &lt;1% of installed-base browsers. With the fallback
  //     removed the policy is uniformly strict across browsers.
  //   * M-9: added {@code form-action 'self'} (an injected {@code &lt;form action=evil&gt;} cannot
  //     POST any visible field — including the CSRF token — to a third-party origin) and {@code
  //     upgrade-insecure-requests} (auto-rewrites HTTP subresources to HTTPS so a mixed-content
  //     bug never falls back to plain HTTP). The Keycloak origin (derived per-environment from the
  //     OIDC issuer-uri) is appended to {@code form-action} at runtime in {@code
  //     cspNonceHeaderWriter}: the POST {@code /logout} form's success redirect targets Keycloak's
  //     cross-origin {@code end_session_endpoint}, and with {@code 'self'} alone Chromium blocks
  //     that redirect — the local session is cleared but the Keycloak SSO session stays alive, so
  //     the next login silently re-authenticates (the regression after logout became POST-only).
  // {@code style-src} no longer carries {@code 'unsafe-inline'} (audit finding L-3): every {@code
  // <style>} block in the templates renders with {@code th:attr="nonce=${cspNonce}"}, so an
  // injected {@code <style>} tag from a stored-XSS vector cannot be evaluated. The former {@code
  // style-src-attr 'unsafe-inline'} fallback for inline {@code style=""} attributes has been
  // dropped to {@code 'none'}: all inline style attributes were migrated out of the templates —
  // static values to the generated {@code inline-migration.css} classes, and the data-driven ones
  // (modal show/hide, opacity/colour toggles → {@code th:classappend} classes; progress-bar widths
  // → a {@code data-krtm-width} attribute applied via the CSSOM in {@code inline-style-apply.js},
  // which {@code style-src-attr} does not govern). With no {@code style=""} attribute remaining, an
  // injected one is now blocked by the browser, closing the CSS-injection residual entirely.
  private static final String CSP_TEMPLATE =
      "default-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; "
          + "form-action %2$s; upgrade-insecure-requests; "
          + "img-src 'self' data:; font-src 'self' data:; "
          + "style-src 'self' 'nonce-%1$s'; "
          + "style-src-attr 'none'; "
          + "script-src 'nonce-%1$s' 'strict-dynamic'";

  /**
   * Builds the frontend response-header {@link Customizer} for {@link HttpSecurity#headers}: the
   * per-request CSP writer plus the static frame-options / referrer / cross-origin / HSTS /
   * permissions-policy / content-type-options headers, in the exact order the filter chain applied
   * them inline.
   *
   * @param issuerUri the configured Keycloak issuer URI, used to derive the allowed logout-redirect
   *     origin for the CSP {@code form-action} directive
   * @return the headers customizer to hand to {@code http.headers(...)}
   */
  public static Customizer<HeadersConfigurer<HttpSecurity>> frontend(String issuerUri) {
    return headers -> {
      headers.addHeaderWriter(cspNonceHeaderWriter(issuerUri));
      headers.frameOptions(HeadersConfigurer.FrameOptionsConfig::deny);
      headers.referrerPolicy(ref -> ref.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN));
      // M-12: Cross-Origin-Opener-Policy + Cross-Origin-Resource-Policy. Same rationale
      // as the backend — COOP isolates the browsing context group, CORP prevents
      // cross-origin embedding of frontend resources via {@code <img>} / {@code <script>}.
      headers.crossOriginOpenerPolicy(
          coop ->
              coop.policy(
                  org.springframework.security.web.header.writers
                      .CrossOriginOpenerPolicyHeaderWriter.CrossOriginOpenerPolicy.SAME_ORIGIN));
      headers.crossOriginResourcePolicy(
          corp ->
              corp.policy(
                  org.springframework.security.web.header.writers
                      .CrossOriginResourcePolicyHeaderWriter.CrossOriginResourcePolicy
                      .SAME_ORIGIN));
      // Audit finding H-9: explicit HSTS. Frontend is reached over HTTPS in production;
      // behind nginx-proxy-manager with X-Forwarded-Proto the default writer works,
      // but pinning the policy here makes the contract explicit and prod-safe even if
      // the proxy headers are misconfigured.
      headers.httpStrictTransportSecurity(
          hsts -> hsts.includeSubDomains(true).preload(true).maxAgeInSeconds(31_536_000L));
      headers.addHeaderWriter(
          new org.springframework.security.web.header.writers.StaticHeadersWriter(
              "Permissions-Policy",
              // L-3: explicit deny for every browser feature the app does not use.
              "geolocation=(), camera=(), microphone=(), fullscreen=(),"
                  + " payment=(), usb=(), serial=(), bluetooth=(), accelerometer=(),"
                  + " gyroscope=(), magnetometer=(), display-capture=(),"
                  + " clipboard-read=(), clipboard-write=(), interest-cohort=()"));
      headers.contentTypeOptions(Customizer.withDefaults());
    };
  }

  /**
   * Builds the per-request CSP header writer. The nonce is substituted per request; the {@code
   * form-action} source list is computed once here (this method runs a single time while the filter
   * chain is assembled). {@code 'self'} covers every same-origin form in the app. The Keycloak
   * origin is appended because exactly one form submits cross-origin: the POST {@code /logout},
   * whose success redirect targets Keycloak's {@code end_session_endpoint}. Without the Keycloak
   * origin in {@code form-action}, Chromium blocks that redirect — the local Spring session is
   * cleared but the Keycloak SSO session survives, so the next login silently re-authenticates
   * instead of prompting for credentials.
   *
   * @param issuerUri the configured Keycloak issuer URI, used to derive the allowed logout-redirect
   *     origin
   * @return a header writer that emits the {@code Content-Security-Policy} response header
   */
  private static HeaderWriter cspNonceHeaderWriter(String issuerUri) {
    String keycloakOrigin = keycloakOriginOf(issuerUri);
    String formAction = keycloakOrigin.isEmpty() ? "'self'" : "'self' " + keycloakOrigin;
    return (request, response) -> {
      Object nonceAttr = request.getAttribute(CspNonceFilter.REQUEST_ATTRIBUTE);
      String nonce = nonceAttr != null ? nonceAttr.toString() : "";
      response.setHeader("Content-Security-Policy", String.format(CSP_TEMPLATE, nonce, formAction));
    };
  }

  /**
   * Derives the origin ({@code scheme://host[:port]}) from the configured OIDC issuer URI, for use
   * in the CSP {@code form-action} directive. Keycloak's {@code end_session_endpoint} shares the
   * issuer's origin, so this is precisely the origin the POST-logout redirect must be allowed to
   * reach.
   *
   * @param issuerUri the configured Keycloak issuer URI; may be {@code null}, blank, or unparseable
   * @return the {@code scheme://host[:port]} origin, or an empty string if it cannot be derived (in
   *     which case {@code form-action} stays {@code 'self'}-only)
   */
  private static String keycloakOriginOf(String issuerUri) {
    if (issuerUri == null || issuerUri.isBlank()) {
      return "";
    }
    try {
      java.net.URI uri = java.net.URI.create(issuerUri.trim());
      String scheme = uri.getScheme();
      String host = uri.getHost();
      if (scheme == null || host == null) {
        return "";
      }
      return uri.getPort() == -1
          ? scheme + "://" + host
          : scheme + "://" + host + ":" + uri.getPort();
    } catch (IllegalArgumentException ex) {
      log.warn(
          "Could not derive the Keycloak origin from issuer-uri '{}' for the CSP form-action"
              + " directive; the POST /logout redirect to Keycloak may be blocked by the browser.",
          issuerUri);
      return "";
    }
  }
}
