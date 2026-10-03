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

package de.greluc.krt.profit.basetool.ingest.support;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import de.greluc.krt.profit.basetool.ingest.auth.ExchangeTokenGateFilter;
import de.greluc.krt.profit.basetool.ingest.gate.ExchangeRequestContext;
import de.greluc.krt.profit.basetool.ingest.observability.ExchangeLogContext;
import de.greluc.krt.profit.basetool.ingest.registry.ExchangeRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

/** Keys, tokens, DPoP proofs, registries and probe routes for the exchange gate tests. */
public final class ExchangeTestSupport {

  /** The origin MockMvc requests carry. */
  public static final String ORIGIN = "http://localhost";

  /** The registry client the tests act as. */
  public static final String CLIENT = "versekit";

  /** A read route without a controller yet, which needs {@code exchange.stock.read}. */
  public static final String STOCK = "/exchange/v1/me/stock";

  /** The service document, which needs {@code exchange.connect}. */
  public static final String SERVICE_DOCUMENT = "/exchange/v1";

  /** The request header telling the probe what to answer: {@code <status>:<code>}. */
  public static final String PROBE_ANSWER = "X-Probe-Answer";

  /** The request header asking the probe to answer with the exchange log fields it sees. */
  public static final String PROBE_MDC = "X-Probe-Mdc";

  /** The account check, which needs {@code exchange.connect} and has its own hourly limit. */
  public static final String ACCOUNT_CHECK = "/exchange/v1/me/account-check";

  /** A write route, which needs {@code exchange.blueprints.write}. */
  public static final String BLUEPRINT_CHANGES = "/exchange/v1/me/blueprints/changes";

  /** Not instantiable. */
  private ExchangeTestSupport() {}

  /**
   * Generates a DPoP key.
   *
   * @return a P-256 key
   * @throws Exception if generation fails
   */
  public static @NotNull ECKey newKey() throws Exception {
    return new ECKeyGenerator(Curve.P_256).generate();
  }

  /**
   * Returns a key's thumbprint.
   *
   * @param key the key
   * @return the RFC 7638 thumbprint
   * @throws Exception if hashing fails
   */
  public static @NotNull String thumbprint(@NotNull ECKey key) throws Exception {
    return key.computeThumbprint().toString();
  }

  /**
   * Builds a decoded access token of a sign-in made when it was issued.
   *
   * @param value the token value
   * @param audience the audience
   * @param thumbprint the bound key's thumbprint, or {@code null} for an unbound token
   * @param member the subject
   * @param scope the space-separated scopes
   * @param issuedAt the issue time
   * @return the token
   */
  public static @NotNull Jwt token(
      @NotNull String value,
      @NotNull String audience,
      @Nullable String thumbprint,
      @NotNull String member,
      @NotNull String scope,
      @NotNull Instant issuedAt) {
    return token(value, audience, thumbprint, member, scope, issuedAt, issuedAt);
  }

  /**
   * Builds a decoded access token.
   *
   * @param value the token value
   * @param audience the audience
   * @param thumbprint the bound key's thumbprint, or {@code null} for an unbound token
   * @param member the subject
   * @param scope the space-separated scopes
   * @param issuedAt the issue time
   * @param authTime the sign-in's time as Keycloak writes it, or {@code null} for no {@code
   *     auth_time} claim
   * @return the token
   */
  public static @NotNull Jwt token(
      @NotNull String value,
      @NotNull String audience,
      @Nullable String thumbprint,
      @NotNull String member,
      @NotNull String scope,
      @NotNull Instant issuedAt,
      @Nullable Instant authTime) {
    Jwt.Builder builder =
        Jwt.withTokenValue(value)
            .header("alg", "ES256")
            .subject(member)
            .audience(List.of(audience))
            .claim("azp", CLIENT)
            .claim("scope", scope)
            .issuedAt(issuedAt)
            .expiresAt(issuedAt.plusSeconds(300));
    if (thumbprint != null) {
      builder.claim("cnf", Map.of("jkt", thumbprint));
    }
    if (authTime != null) {
      builder.claim("auth_time", authTime.getEpochSecond());
    }
    return builder.build();
  }

  /**
   * Signs a DPoP proof.
   *
   * @param signer the key that signs the proof
   * @param token the access token the proof binds
   * @param method the HTTP method
   * @param path the request path
   * @param nonce the server nonce, or {@code null} for none
   * @return the compact proof
   * @throws Exception if signing fails
   */
  public static @NotNull String proof(
      @NotNull ECKey signer,
      @NotNull String token,
      @NotNull String method,
      @NotNull String path,
      @Nullable String nonce)
      throws Exception {
    byte[] hash =
        MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
    JWTClaimsSet.Builder claims =
        new JWTClaimsSet.Builder()
            .claim("htm", method)
            .claim("htu", ORIGIN + path)
            .issueTime(Date.from(Instant.now()))
            .jwtID(UUID.randomUUID().toString())
            .claim("ath", Base64.getUrlEncoder().withoutPadding().encodeToString(hash));
    if (nonce != null) {
      claims.claim("nonce", nonce);
    }
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.ES256)
                .type(new JOSEObjectType("dpop+jwt"))
                .jwk(signer.toPublicJWK())
                .build(),
            claims.build());
    jwt.sign(new ECDSASigner(signer));
    return jwt.serialize();
  }

  /**
   * Builds a registry holding the test client.
   *
   * @param enabled the global switch
   * @param active whether the client is active
   * @param capabilities the granted capabilities
   * @param minVersion the minimum client version, or {@code null}
   * @return the registry
   */
  public static @NotNull ExchangeRegistry registry(
      boolean enabled,
      boolean active,
      @NotNull Set<String> capabilities,
      @Nullable String minVersion) {
    return new ExchangeRegistry(
        1L,
        enabled,
        Map.of(
            CLIENT,
            new ExchangeRegistry.Client("VerseKit", active, capabilities, minVersion, null, null)));
  }

  /**
   * Builds a registry holding the test client, active and switched on, with limit overrides.
   *
   * @param capabilities the granted capabilities
   * @param minVersion the minimum client version, or {@code null}
   * @param requestsPerMinute the per-minute limit override
   * @return the registry
   */
  public static @NotNull ExchangeRegistry registryWithLimits(
      @NotNull Set<String> capabilities, @Nullable String minVersion, int requestsPerMinute) {
    return new ExchangeRegistry(
        1L,
        true,
        Map.of(
            CLIENT,
            new ExchangeRegistry.Client(
                "VerseKit", true, capabilities, minVersion, requestsPerMinute, null)));
  }

  /**
   * Sends one DPoP-bound request: a first proof without a nonce fetches the nonce, the second one
   * carries it.
   *
   * @param mockMvc the client
   * @param key the DPoP key
   * @param token the access token
   * @param method the method
   * @param path the path, with a query string the proofs leave out of {@code htu}
   * @param json the JSON body, or {@code null}
   * @param userAgent the {@code User-Agent}, or {@code null}
   * @return the second request's result
   * @throws Exception if a request fails
   */
  public static @NotNull ResultActions call(
      @NotNull MockMvc mockMvc,
      @NotNull ECKey key,
      @NotNull String token,
      @NotNull HttpMethod method,
      @NotNull String path,
      @Nullable String json,
      @Nullable String userAgent)
      throws Exception {
    int query = path.indexOf('?');
    String target = query < 0 ? path : path.substring(0, query);
    String nonce =
        mockMvc
            .perform(
                MockMvcRequestBuilders.request(method, path)
                    .header(HttpHeaders.AUTHORIZATION, "DPoP " + token)
                    .header("DPoP", proof(key, token, method.name(), target, null)))
            .andReturn()
            .getResponse()
            .getHeader(ExchangeTokenGateFilter.DPOP_NONCE_HEADER);
    MockHttpServletRequestBuilder request =
        MockMvcRequestBuilders.request(method, path)
            .header(HttpHeaders.AUTHORIZATION, "DPoP " + token)
            .header("DPoP", proof(key, token, method.name(), target, nonce));
    if (userAgent != null) {
      request.header(HttpHeaders.USER_AGENT, userAgent);
    }
    if (method == HttpMethod.POST) {
      request.header("Idempotency-Key", "test-" + UUID.randomUUID());
    }
    if (json != null) {
      request.contentType(MediaType.APPLICATION_JSON).content(json);
    }
    return mockMvc.perform(request);
  }

  /**
   * Test-only answers on real exchange routes, registered as functions so no other context sees
   * them.
   */
  @TestConfiguration
  public static class ProbeRoutes {

    /**
     * Answers the probed routes with the gate's context.
     *
     * @return the routes
     */
    @Bean
    RouterFunction<ServerResponse> exchangeProbes() {
      return RouterFunctions.route()
          .GET(STOCK, ProbeRoutes::context)
          .POST(BLUEPRINT_CHANGES, ProbeRoutes::context)
          .build();
    }

    /**
     * Answers with the admitted client and capabilities.
     *
     * @param request the request
     * @return {@code clientId capability capability…}, or {@code none}
     */
    private static ServerResponse context(ServerRequest request) {
      String answer = request.headers().firstHeader(PROBE_ANSWER);
      if (answer != null) {
        int colon = answer.indexOf(':');
        return ServerResponse.status(Integer.parseInt(answer.substring(0, colon)))
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body("{\"code\":\"" + answer.substring(colon + 1) + "\"}");
      }
      if (request.headers().firstHeader(PROBE_MDC) != null) {
        return ServerResponse.ok()
            .body(
                MDC.get(ExchangeLogContext.CLIENT_KEY)
                    + " | "
                    + MDC.get(ExchangeLogContext.ROUTE_KEY));
      }
      ExchangeRequestContext context = ExchangeRequestContext.of(request.servletRequest());
      if (context == null) {
        return ServerResponse.ok().body("none");
      }
      return ServerResponse.ok()
          .body(context.clientId() + " " + String.join(" ", new TreeSet<>(context.capabilities())));
    }
  }
}
