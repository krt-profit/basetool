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

package de.greluc.krt.profit.basetool.ingest.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import de.greluc.krt.profit.basetool.ingest.model.dto.HandoffKind;
import de.greluc.krt.profit.basetool.ingest.service.BackendImportClient;
import de.greluc.krt.profit.basetool.ingest.service.HandoffStagingService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Exercises Spring Security's DPoP validation path (RFC 9449, REQ-INGEST-012) with a <b>real,
 * signed DPoP proof</b> — the one thing the rest of the suite does not cover.
 *
 * <p>Why this exists as its own class: {@code IngestControllerTest} proves only that adding {@code
 * dPoP(...)} to the resource-server DSL leaves the plain-bearer path intact. That is wiring
 * evidence, not protocol evidence — it never constructs a proof, so a misconfigured DPoP setup
 * would have stayed invisible until the desktop extractor shipped DPoP support and every send
 * started failing in production. Here the proof is generated, signed and presented exactly as a
 * client would.
 *
 * <p><b>What is real and what is not.</b> The {@link JwtDecoder} is mocked, so the access token's
 * signature / issuer / expiry are not verified and no Keycloak is involved — that is the resource
 * server's ordinary bearer validation and is out of scope here. Everything DPoP-specific
 * <em>is</em> real: the EC key pair, the {@code dpop+jwt} proof with its {@code htm} / {@code htu}
 * / {@code iat} / {@code jti} / {@code ath} claims, the ES256 signature over it, and the binding
 * between the proof's embedded JWK thumbprint and the access token's {@code cnf.jkt} confirmation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class DpopResourceServerTest {

  private static final String REFINERY_PATH = "/v1/refinery-extract";
  private static final String REFINERY_URL = "http://localhost" + REFINERY_PATH;

  private static final String REFINERY_BODY =
      "{\"schemaVersion\":1,\"orders\":[{\"panelType\":\"SETUP\","
          + "\"sourceImages\":[{\"name\":\"shot.png\",\"width\":1920,\"height\":1080,"
          + "\"cropMode\":\"vlm\"}],"
          + "\"goods\":[{\"rawMaterialName\":\"Iron\",\"inputQuantity\":1,\"refine\":true}]}]}";

  @Autowired private WebApplicationContext context;

  @MockitoBean private JwtDecoder jwtDecoder;
  @MockitoBean private BackendImportClient backendImportClient;
  @MockitoBean private HandoffStagingService handoffStagingService;

  private MockMvc mockMvc;
  private ECKey clientKey;

  @BeforeEach
  void setUp() throws Exception {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    clientKey = new ECKeyGenerator(Curve.P_256).keyID(UUID.randomUUID().toString()).generate();
    when(backendImportClient.forwardRefineryExtract(anyString(), any(), any()))
        .thenReturn("{\"goodsMatched\":1}");
    when(handoffStagingService.stage(anyString(), any(HandoffKind.class), anyString()))
        .thenReturn("HID-DPOP");
  }

  /**
   * Stubs the decoder so the given opaque token string decodes to a sender-constrained JWT whose
   * {@code cnf.jkt} matches {@code thumbprint}.
   *
   * @param tokenValue the raw access-token string presented by the client
   * @param thumbprint the JWK SHA-256 thumbprint the token is bound to
   */
  private void stubBoundAccessToken(String tokenValue, String thumbprint) {
    Jwt jwt =
        Jwt.withTokenValue(tokenValue)
            .header("alg", "RS256")
            .claim("sub", "sub-dpop")
            .claim("azp", "basetool-sc-extractor")
            .claim("cnf", Map.of("jkt", thumbprint))
            .issuedAt(Instant.now().minusSeconds(30))
            .expiresAt(Instant.now().plusSeconds(300))
            .build();
    when(jwtDecoder.decode(tokenValue)).thenReturn(jwt);
  }

  /**
   * Builds and signs a DPoP proof for one request, exactly as RFC 9449 §4.2 specifies.
   *
   * @param method the HTTP method the proof is bound to ({@code htm})
   * @param url the absolute request URI the proof is bound to ({@code htu})
   * @param accessToken the access token the proof is bound to via the {@code ath} hash, or {@code
   *     null} to omit that claim
   * @return the serialized, signed proof JWT
   * @throws Exception if key handling or signing fails
   */
  private String dpopProof(String method, String url, String accessToken) throws Exception {
    JWSHeader header =
        new JWSHeader.Builder(JWSAlgorithm.ES256)
            .type(new JOSEObjectType("dpop+jwt"))
            .jwk(clientKey.toPublicJWK())
            .build();
    JWTClaimsSet.Builder claims =
        new JWTClaimsSet.Builder()
            .jwtID(UUID.randomUUID().toString())
            .claim("htm", method)
            .claim("htu", url)
            .issueTime(new Date());
    if (accessToken != null) {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(accessToken.getBytes(StandardCharsets.UTF_8));
      claims.claim("ath", Base64URL.encode(digest).toString());
    }
    SignedJWT proof = new SignedJWT(header, claims.build());
    proof.sign(new ECDSASigner(clientKey));
    return proof.serialize();
  }

  @Test
  void shouldAcceptADpopBoundTokenPresentedWithAValidProof() throws Exception {
    String accessToken = "dpop-access-token";
    stubBoundAccessToken(accessToken, clientKey.toPublicJWK().computeThumbprint().toString());

    mockMvc
        .perform(
            post(REFINERY_PATH)
                .header("Authorization", "DPoP " + accessToken)
                .header("DPoP", dpopProof("POST", REFINERY_URL, accessToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(REFINERY_BODY))
        .andExpect(status().isOk());
  }

  @Test
  void shouldRejectADpopTokenWhoseProofIsSignedByADifferentKey() throws Exception {
    // The whole point of sender-constraining: a stolen token is useless without the private key.
    // The token stays bound to the ORIGINAL thumbprint while the proof is signed by a fresh key.
    String accessToken = "dpop-access-token";
    String boundThumbprint = clientKey.toPublicJWK().computeThumbprint().toString();
    stubBoundAccessToken(accessToken, boundThumbprint);
    clientKey = new ECKeyGenerator(Curve.P_256).keyID(UUID.randomUUID().toString()).generate();

    mockMvc
        .perform(
            post(REFINERY_PATH)
                .header("Authorization", "DPoP " + accessToken)
                .header("DPoP", dpopProof("POST", REFINERY_URL, accessToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(REFINERY_BODY))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void shouldRejectAProofBoundToADifferentUrl() throws Exception {
    // htu binds the proof to one endpoint, so a proof captured on one call cannot be replayed
    // against another.
    String accessToken = "dpop-access-token";
    stubBoundAccessToken(accessToken, clientKey.toPublicJWK().computeThumbprint().toString());

    mockMvc
        .perform(
            post(REFINERY_PATH)
                .header("Authorization", "DPoP " + accessToken)
                .header(
                    "DPoP", dpopProof("POST", "http://localhost/v1/blueprint-preview", accessToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(REFINERY_BODY))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void shouldRejectADpopSchemeRequestWithNoProofAtAll() throws Exception {
    String accessToken = "dpop-access-token";
    stubBoundAccessToken(accessToken, clientKey.toPublicJWK().computeThumbprint().toString());

    mockMvc
        .perform(
            post(REFINERY_PATH)
                .header("Authorization", "DPoP " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(REFINERY_BODY))
        .andExpect(status().isUnauthorized());
  }
}
