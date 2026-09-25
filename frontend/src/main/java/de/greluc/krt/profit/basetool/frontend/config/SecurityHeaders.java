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
 * {@code X-Content-Type-Options}.
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

  /**
   * Builds the frontend response-header {@link Customizer} for {@link HttpSecurity#headers}.
   *
   * @param issuerUri the configured Keycloak issuer URI, from which the CSP {@code form-action}
   *     logout-redirect origin is derived
   * @return the headers customizer to hand to {@code http.headers(...)}
   */
  public static Customizer<HeadersConfigurer<HttpSecurity>> frontend(String issuerUri) {
    return headers -> {
      headers.addHeaderWriter(cspNonceHeaderWriter(issuerUri));
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
   * end-session endpoint is allowed.
   *
   * @param issuerUri the configured Keycloak issuer URI
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
