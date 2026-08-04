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

package de.greluc.krt.profit.basetool.ingest.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.DPoPAuthenticationToken;

/**
 * Behaviour of the {@code htu} target substitution (ADR-0129).
 *
 * <p>{@link #substitutesTheConfiguredOriginForTheRequestDerivedOne} is the whole point. Spring
 * compares {@code htu} with a bare {@code String.equals} against a URL Tomcat assembles from the
 * reverse proxy's forwarded headers; a proxy that omits {@code X-Forwarded-Port} leaves the
 * internal port in the server's value while the client signed the public one, and every proof
 * fails. That cannot be reproduced without a proxy, so what is pinned here is the substitution
 * itself.
 */
class PublicUriDpopAuthenticationConverterTest {

  // Opaque on purpose. This converter copies both values through without parsing either, so a
  // JWT-shaped literal buys nothing here — and a fake one trips the secret scanner's
  // generic-api-key rule, which cannot tell a synthetic fixture from a leaked credential.
  private static final String PROOF = "proof-value";
  private static final String TOKEN = "access-token-value";

  private MockHttpServletRequest dpopRequest() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/blueprint-preview");
    request.setRequestURI("/v1/blueprint-preview");
    // What Tomcat would have assembled behind a proxy that dropped X-Forwarded-Port: the INTERNAL
    // port, which is exactly the value the client cannot have signed.
    request.setScheme("http");
    request.setServerName("ingest");
    request.setServerPort(11262);
    request.addHeader("Authorization", "DPoP " + TOKEN);
    request.addHeader("DPoP", PROOF);
    return request;
  }

  /** The configured origin replaces the request-derived one, and the path is kept. */
  @Test
  void substitutesTheConfiguredOriginForTheRequestDerivedOne() {
    PublicUriDpopAuthenticationConverter converter =
        new PublicUriDpopAuthenticationConverter("https://ingest.profit-base.online");

    Authentication authentication = converter.convert(dpopRequest());

    assertThat(authentication).isInstanceOf(DPoPAuthenticationToken.class);
    DPoPAuthenticationToken token = (DPoPAuthenticationToken) authentication;
    assertThat(token.getResourceUri())
        .as("the proxy-derived :11262 must not survive into the htu comparison")
        .isEqualTo("https://ingest.profit-base.online/v1/blueprint-preview");
    assertThat(token.getAccessToken()).isEqualTo(TOKEN);
    assertThat(token.getDPoPProof()).isEqualTo(PROOF);
    assertThat(token.getMethod()).isEqualTo("POST");
  }

  /** A trailing slash in the configured origin must not produce a doubled separator. */
  @Test
  void toleratesATrailingSlashInTheConfiguredOrigin() {
    PublicUriDpopAuthenticationConverter converter =
        new PublicUriDpopAuthenticationConverter("https://ingest.profit-base.online/");

    DPoPAuthenticationToken token = (DPoPAuthenticationToken) converter.convert(dpopRequest());

    assertThat(token.getResourceUri())
        .isEqualTo("https://ingest.profit-base.online/v1/blueprint-preview");
  }

  /**
   * With nothing configured the stock behaviour is what a deployment gets.
   *
   * <p>Half-applying the override would be worse than not applying it: the operator would see the
   * class in the chain and assume the proxy no longer matters.
   */
  @Test
  void delegatesUnchangedWhenNoOriginIsConfigured() {
    PublicUriDpopAuthenticationConverter converter = new PublicUriDpopAuthenticationConverter("  ");

    DPoPAuthenticationToken token = (DPoPAuthenticationToken) converter.convert(dpopRequest());

    assertThat(token.getResourceUri()).isEqualTo("http://ingest:11262/v1/blueprint-preview");
  }

  /** A request that is not DPoP is none of this converter's business. */
  @Test
  void leavesANonDpopRequestAlone() {
    PublicUriDpopAuthenticationConverter converter =
        new PublicUriDpopAuthenticationConverter("https://ingest.profit-base.online");
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/blueprint-preview");
    request.addHeader("Authorization", "Bearer " + TOKEN);

    assertThat(converter.convert(request)).isNull();
  }
}
