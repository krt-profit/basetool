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
import org.jetbrains.annotations.NotNull;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.web.header.HeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

/**
 * The frontend's security response headers: the per-request nonce-gated {@code
 * Content-Security-Policy} with a Keycloak-aware {@code form-action}, {@code X-Frame-Options:
 * DENY}, {@code Referrer-Policy}, cross-origin policies, HSTS, {@code Permissions-Policy} and
 * {@code X-Content-Type-Options}, plus the Trusted Types directives in the mode {@link
 * TrustedTypesMode} selects.
 *
 * <p>Stateless; pinned by {@code SecurityHeadersTest}.
 */
@Slf4j
public final class SecurityHeaders {

  private SecurityHeaders() {}

  private static final String CSP_TEMPLATE =
      "default-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; "
          + "form-action %2$s; upgrade-insecure-requests; "
          + "img-src 'self' data:; font-src 'self' data:; "
          + "style-src 'self' 'nonce-%1$s'; "
          + "style-src-attr 'none'; "
          + "script-src 'nonce-%1$s' 'strict-dynamic'";

  /** The report-only policy header that carries the Trusted Types directives in report mode. */
  static final String REPORT_ONLY_HEADER = "Content-Security-Policy-Report-Only";

  /**
   * The Trusted Types directives (ADR-0239): every DOM script sink takes a policy value, and only
   * the two named policies of {@code krt-html.js} and {@code krt-fetch.js} may be created, so a
   * {@code default} policy is a violation too.
   */
  static final String TRUSTED_TYPES_DIRECTIVES =
      "require-trusted-types-for 'script'; trusted-types krt-html krt-fragment";

  /**
   * Builds the frontend response-header {@link Customizer} for {@link HttpSecurity#headers}.
   *
   * @param issuerUri the configured Keycloak issuer URI, from which the CSP {@code form-action}
   *     logout-redirect origin is derived
   * @param trustedTypes whether the Trusted Types directives are reported or enforced
   * @return the headers customizer to hand to {@code http.headers(...)}
   */
  public static Customizer<HeadersConfigurer<HttpSecurity>> frontend(
      String issuerUri, @NotNull TrustedTypesMode trustedTypes) {
    return headers -> {
      headers.addHeaderWriter(cspNonceHeaderWriter(issuerUri, trustedTypes));
      headers.frameOptions(HeadersConfigurer.FrameOptionsConfig::deny);
      headers.referrerPolicy(ref -> ref.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN));
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
      headers.httpStrictTransportSecurity(
          hsts -> hsts.includeSubDomains(true).preload(true).maxAgeInSeconds(31_536_000L));
      headers.addHeaderWriter(
          new org.springframework.security.web.header.writers.StaticHeadersWriter(
              "Permissions-Policy",
              "geolocation=(), camera=(), microphone=(), fullscreen=(),"
                  + " payment=(), usb=(), serial=(), bluetooth=(), accelerometer=(),"
                  + " gyroscope=(), magnetometer=(), display-capture=(),"
                  + " clipboard-read=(), clipboard-write=(), interest-cohort=()"));
      headers.contentTypeOptions(Customizer.withDefaults());
    };
  }

  /**
   * Builds the per-request CSP header writer. The nonce is substituted per request; {@code
   * form-action} is {@code 'self'} plus the Keycloak origin, so the POST-logout redirect to the
   * end-session endpoint is allowed. The Trusted Types directives join the enforced policy in
   * {@link TrustedTypesMode#ENFORCE} and form a report-only policy of their own in {@link
   * TrustedTypesMode#REPORT}.
   *
   * @param issuerUri the configured Keycloak issuer URI
   * @param trustedTypes whether the Trusted Types directives are reported or enforced
   * @return a header writer that emits the {@code Content-Security-Policy} response header and, in
   *     report mode, the {@code Content-Security-Policy-Report-Only} header
   */
  static HeaderWriter cspNonceHeaderWriter(
      String issuerUri, @NotNull TrustedTypesMode trustedTypes) {
    String keycloakOrigin = keycloakOriginOf(issuerUri);
    String formAction = keycloakOrigin.isEmpty() ? "'self'" : "'self' " + keycloakOrigin;
    boolean enforce = trustedTypes == TrustedTypesMode.ENFORCE;
    return (request, response) -> {
      Object nonceAttr = request.getAttribute(CspNonceFilter.REQUEST_ATTRIBUTE);
      String nonce = nonceAttr != null ? nonceAttr.toString() : "";
      String policy = String.format(CSP_TEMPLATE, nonce, formAction);
      if (enforce) {
        response.setHeader("Content-Security-Policy", policy + "; " + TRUSTED_TYPES_DIRECTIVES);
      } else {
        response.setHeader("Content-Security-Policy", policy);
        response.setHeader(REPORT_ONLY_HEADER, TRUSTED_TYPES_DIRECTIVES);
      }
    };
  }

  /**
   * Derives the origin ({@code scheme://host[:port]}) of the OIDC issuer URI, which Keycloak's
   * {@code end_session_endpoint} shares.
   *
   * @param issuerUri the configured Keycloak issuer URI; may be {@code null}, blank, or unparseable
   * @return the origin, or an empty string if it cannot be derived
   */
  @NotNull
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
