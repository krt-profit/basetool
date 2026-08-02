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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ssl.DefaultSslBundleRegistry;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;

/**
 * Unit tests for the opt-in audience validator (REQ-INGEST-002/-008): a token passes only when its
 * {@code aud} intersects the configured expected set.
 */
class SecurityConfigTest {

  private static final List<String> EXPECTED = List.of("basetool-backend");

  private static Jwt jwtWithAudience(List<String> audience) {
    return Jwt.withTokenValue("token")
        .header("alg", "none")
        .subject("user-1")
        .audience(audience)
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(300))
        .build();
  }

  @Test
  void shouldAcceptTokenWhoseAudienceContainsAnExpectedValue() {
    // Given
    OAuth2TokenValidator<Jwt> validator = SecurityConfig.audienceValidator(EXPECTED);
    Jwt jwt = jwtWithAudience(List.of("other", "basetool-backend"));

    // When
    OAuth2TokenValidatorResult result = validator.validate(jwt);

    // Then
    assertThat(result.hasErrors()).isFalse();
  }

  @Test
  void shouldRejectTokenWithoutAnyExpectedAudience() {
    // Given
    OAuth2TokenValidator<Jwt> validator = SecurityConfig.audienceValidator(EXPECTED);
    Jwt jwt = jwtWithAudience(List.of("basetool-frontend"));

    // When
    OAuth2TokenValidatorResult result = validator.validate(jwt);

    // Then
    assertThat(result.hasErrors()).isTrue();
  }

  @Test
  void shouldRejectTokenWithoutAnyAudienceClaim() {
    // Given: `aud` absent entirely — the disjoint check must treat that as a reject, not a pass.
    OAuth2TokenValidator<Jwt> validator = SecurityConfig.audienceValidator(EXPECTED);
    Jwt jwt =
        Jwt.withTokenValue("token")
            .header("alg", "none")
            .subject("user-1")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300))
            .build();

    // When / Then
    assertThat(validator.validate(jwt).hasErrors()).isTrue();
  }

  @Test
  void shouldBuildAnIssuerLocationDecoderWhenNoInternalJwksUriIsConfigured() throws Exception {
    // The blank-jwkSetUri branch must reproduce the auto-configuration exactly: discover the
    // issuer's OIDC configuration and derive the accepted algorithm set from the live JWKS, rather
    // than using a hand-configured key URL.
    ECKey key = new ECKeyGenerator(Curve.P_256).keyID("k1").generate();
    try (MockWebServer keycloak = new MockWebServer()) {
      keycloak.start();
      String issuer = keycloak.url("/realms/iri").toString();
      keycloak.setDispatcher(keycloakDispatcher(issuer, key));

      NimbusJwtDecoder decoder =
          SecurityConfig.buildDecoder(issuer, "", new DefaultSslBundleRegistry());

      // The discovered issuer is enforced, so a token minted for it decodes and one minted for a
      // different issuer does not.
      assertThat(decoder.decode(signedToken(key, List.of("basetool-backend"), issuer)).getSubject())
          .isEqualTo("caller-sub");
    }
  }

  @Test
  void shouldAttachNoAudienceValidatorWhenOnlyBlankAudiencesAreConfigured() throws Exception {
    // Given: the property binds blank entries — configuration noise, not a real audience. Attaching
    // a validator for them would reject every token, since a blank `aud` matches nothing.
    ECKey key = new ECKeyGenerator(Curve.P_256).keyID("k1").generate();
    try (MockWebServer keycloak = new MockWebServer()) {
      keycloak.enqueue(
          new MockResponse()
              .setHeader("Content-Type", "application/json")
              .setBody(new JWKSet(key.toPublicJWK()).toString()));
      keycloak.start();

      SecurityConfig config = new SecurityConfig();
      ReflectionTestUtils.setField(config, "expectedAudiences", List.of("  ", ""));

      // When
      JwtDecoder decoder =
          config.resourceServerJwtDecoder(
              "https://keycloak.example/realms/iri",
              keycloak.url("/certs").toString(),
              new DefaultSslBundleRegistry());

      // Then: a token whose audience matches nothing still decodes — no audience rule is active.
      assertThat(decoder.decode(signedToken(key, List.of("some-other-client"))).getSubject())
          .isEqualTo("caller-sub");
    }
  }

  @Test
  void shouldRejectAnAudienceMismatchOnceAudiencesAreConfigured() throws Exception {
    // Given a decoder built with a real expected audience and a live JWKS the token is signed
    // against, so the audience rule is the only thing that can fail.
    ECKey key = new ECKeyGenerator(Curve.P_256).keyID("k1").generate();
    try (MockWebServer keycloak = new MockWebServer()) {
      keycloak.enqueue(
          new MockResponse()
              .setHeader("Content-Type", "application/json")
              .setBody(new JWKSet(key.toPublicJWK()).toString()));
      keycloak.start();

      SecurityConfig config = new SecurityConfig();
      ReflectionTestUtils.setField(config, "expectedAudiences", EXPECTED);
      JwtDecoder decoder =
          config.resourceServerJwtDecoder(
              "https://keycloak.example/realms/iri",
              keycloak.url("/certs").toString(),
              new DefaultSslBundleRegistry());

      // When / Then
      assertThatThrownBy(() -> decoder.decode(signedToken(key, List.of("some-other-client"))))
          .isInstanceOf(JwtValidationException.class);
    }
  }

  @Test
  void corsSourceAllowsNoOriginAndNoCredentials() {
    // The gateway is called by a native desktop app; a browser origin must never be allowed, and
    // credentials must never be echoed (REQ-INGEST-002).
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/refinery-extract");
    request.addHeader(HttpHeaders.ORIGIN, "https://evil.example");

    CorsConfiguration configuration =
        new SecurityConfig().corsConfigurationSource().getCorsConfiguration(request);

    assertThat(configuration).isNotNull();
    assertThat(configuration.getAllowedOriginPatterns()).isEmpty();
    assertThat(configuration.getAllowCredentials()).isFalse();
    assertThat(configuration.checkOrigin("https://evil.example")).isNull();
    assertThat(configuration.getAllowedMethods()).containsExactlyInAnyOrder("POST", "OPTIONS");
  }

  /**
   * Stands in for a Keycloak realm: answers every OIDC discovery probe Spring Security tries for an
   * issuer location, and serves the JWKS the discovered {@code jwks_uri} points at (which {@code
   * withIssuerLocation} fetches eagerly to derive its accepted algorithm set).
   *
   * @param issuer the issuer value the discovery document must echo back
   * @param key the EC key whose public half the JWKS serves
   * @return a dispatcher serving both documents
   */
  private static Dispatcher keycloakDispatcher(String issuer, ECKey key) {
    return new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        String path = request.getPath() == null ? "" : request.getPath();
        if (path.endsWith("/protocol/openid-connect/certs")) {
          return new MockResponse()
              .setHeader("Content-Type", "application/json")
              .setBody(new JWKSet(key.toPublicJWK()).toString());
        }
        if (!path.contains("/.well-known/")) {
          return new MockResponse().setResponseCode(404);
        }
        return new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"issuer\":\""
                    + issuer
                    + "\",\"jwks_uri\":\""
                    + issuer
                    + "/protocol/openid-connect/certs\",\"subject_types_supported\":[\"public\"],"
                    + "\"response_types_supported\":[\"code\"],"
                    + "\"id_token_signing_alg_values_supported\":[\"RS256\"],"
                    + "\"authorization_endpoint\":\""
                    + issuer
                    + "/protocol/openid-connect/auth\"}");
      }
    };
  }

  /**
   * Signs a short-lived ES256 token carrying the given audience against {@code key}, issued by the
   * canonical test issuer.
   *
   * @param key the EC key whose public half the mock JWKS serves
   * @param audience the {@code aud} claim to stamp
   * @return the serialized token
   * @throws Exception if signing fails
   */
  private static String signedToken(ECKey key, List<String> audience) throws Exception {
    return signedToken(key, audience, "https://keycloak.example/realms/iri");
  }

  /**
   * Signs a short-lived ES256 token carrying the given audience and issuer against {@code key}.
   *
   * @param key the EC key whose public half the mock JWKS serves
   * @param audience the {@code aud} claim to stamp
   * @param issuer the {@code iss} claim to stamp
   * @return the serialized token
   * @throws Exception if signing fails
   */
  private static String signedToken(ECKey key, List<String> audience, String issuer)
      throws Exception {
    Instant now = Instant.now();
    SignedJWT signedJwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.ES256).keyID("k1").build(),
            new JWTClaimsSet.Builder()
                .subject("caller-sub")
                .issuer(issuer)
                .audience(audience)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(3600)))
                .build());
    signedJwt.sign(new ECDSASigner(key));
    return signedJwt.serialize();
  }
}
