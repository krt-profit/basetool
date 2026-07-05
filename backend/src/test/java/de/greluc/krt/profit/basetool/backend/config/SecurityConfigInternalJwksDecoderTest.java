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

package de.greluc.krt.profit.basetool.backend.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ssl.DefaultSslBundleRegistry;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Regression guard for REQ-SEC-024: the opt-in internal-JWKS decoder must accept every asymmetric
 * JWS algorithm the realm can be configured to sign with — not just RS256.
 *
 * <p>{@link NimbusJwtDecoder#withJwkSetUri(String)} defaults its accepted algorithm set to RS256
 * only (unlike {@code withIssuerLocation}, which derives it from the live JWKS), so {@code
 * SecurityConfig#buildDecoder} explicitly widens it to the full {@code SignatureAlgorithm} set.
 * This test serves a JWKS with an EC key and decodes an ES256 token: without the {@code
 * jwsAlgorithms(…)} widening the decode would fail with {@code BadJwtException} ("Invalid
 * algorithm"), which would 401 every token once an operator enables internal JWKS on a realm that
 * signs with PS256/ES256.
 */
class SecurityConfigInternalJwksDecoderTest {

  private MockWebServer server;
  private ECKey ecJwk;

  @BeforeEach
  void setUp() throws Exception {
    ecJwk = new ECKeyGenerator(Curve.P_256).keyID("k1").generate();
    server = new MockWebServer();
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(new JWKSet(ecJwk.toPublicJWK()).toString()));
    server.start();
  }

  @AfterEach
  void tearDown() throws Exception {
    server.shutdown();
  }

  @Test
  void internalJwksDecoder_acceptsEs256Token_notJustRs256() throws Exception {
    String jwkSetUri = server.url("/realms/iri/protocol/openid-connect/certs").toString();
    Instant now = Instant.now();
    SignedJWT signedJwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.ES256).keyID("k1").build(),
            new JWTClaimsSet.Builder()
                .subject("caller-sub")
                .issuer("https://keycloak.example/realms/iri")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(3600)))
                .build());
    signedJwt.sign(new ECDSASigner(ecJwk));
    String token = signedJwt.serialize();

    // No keycloak-trust bundle registered -> buildDecoder falls back to the default client and
    // fetches the plain-HTTP MockWebServer JWKS; the assertion is purely about the accepted alg
    // set.
    NimbusJwtDecoder decoder =
        SecurityConfig.buildDecoder(
            "https://keycloak.example/realms/iri", jwkSetUri, new DefaultSslBundleRegistry());

    Jwt decoded = assertDoesNotThrow(() -> decoder.decode(token));
    assertEquals("caller-sub", decoded.getSubject());
  }
}
