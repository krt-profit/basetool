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

package de.greluc.krt.profit.basetool.ingest.auth;

import static de.greluc.krt.profit.basetool.ingest.support.ExchangeTestSupport.STOCK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.ECKey;
import de.greluc.krt.profit.basetool.ingest.handoff.HandoffStagingService;
import de.greluc.krt.profit.basetool.ingest.observability.MetricNames;
import de.greluc.krt.profit.basetool.ingest.registry.ExchangeRegistryReader;
import de.greluc.krt.profit.basetool.ingest.registry.ExchangeRevocationReader;
import de.greluc.krt.profit.basetool.ingest.support.ExchangeTestSupport;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.DPoPProofContext;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** The exchange token gate end to end, with real DPoP proofs (REQ-XCH-004, REQ-XCH-006). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import(ExchangeTestSupport.ProbeRoutes.class)
class ExchangeDpopGateTest {

  private static final String TOKEN = "exchange-token";
  private static final String UNBOUND_TOKEN = "unbound-token";
  private static final String FOREIGN_TOKEN = "foreign-audience-token";

  @Autowired private WebApplicationContext context;
  @Autowired private MeterRegistry meterRegistry;

  @MockitoBean private JwtDecoder jwtDecoder;
  @MockitoBean private HandoffStagingService handoffStagingService;
  @MockitoBean private ExchangeRegistryReader registryReader;
  @MockitoBean private ExchangeRevocationReader revocationReader;

  private MockMvc mockMvc;
  private ECKey key;

  @BeforeEach
  void setUp() throws Exception {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    key = ExchangeTestSupport.newKey();
    String thumbprint = ExchangeTestSupport.thumbprint(key);
    String member = UUID.randomUUID().toString();
    Instant issued = Instant.now().minusSeconds(5);
    when(jwtDecoder.decode(TOKEN))
        .thenReturn(
            ExchangeTestSupport.token(
                TOKEN,
                "basetool-ingest",
                thumbprint,
                member,
                "exchange.connect exchange.stock.read",
                issued));
    when(jwtDecoder.decode(UNBOUND_TOKEN))
        .thenReturn(
            ExchangeTestSupport.token(
                UNBOUND_TOKEN,
                "basetool-ingest",
                null,
                member,
                "exchange.connect exchange.stock.read",
                issued));
    when(jwtDecoder.decode(FOREIGN_TOKEN))
        .thenReturn(
            ExchangeTestSupport.token(
                FOREIGN_TOKEN,
                "basetool-backend",
                thumbprint,
                member,
                "exchange.connect exchange.stock.read",
                issued));
    when(registryReader.current())
        .thenReturn(
            ExchangeTestSupport.registry(
                true, true, Set.of("exchange.connect", "exchange.stock.read"), null));
    when(revocationReader.isDenied(any())).thenReturn(false);
  }

  @Test
  void aBearerTokenIsRefusedAsDpopRequired() throws Exception {
    double before = refused("dpop_required");

    mockMvc
        .perform(get(STOCK).header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, Matchers.startsWith("DPoP algs=")))
        .andExpect(jsonPath("$.code").value("DPOP_REQUIRED"));

    assertThat(refused("dpop_required") - before).isEqualTo(1.0d);
  }

  @Test
  void aProofWithoutTheNonceGetsTheNonceAndTheRetryPasses() throws Exception {
    double refusedBefore = refused("dpop_invalid");
    double challengedBefore = authFailures("use_dpop_nonce");
    MvcResult challenge =
        mockMvc
            .perform(dpop(TOKEN, proof(key, TOKEN, null)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("DPOP_INVALID"))
            .andExpect(
                header()
                    .string(
                        HttpHeaders.WWW_AUTHENTICATE,
                        Matchers.containsString("error=\"use_dpop_nonce\"")))
            .andReturn();
    String nonce = challenge.getResponse().getHeader(ExchangeTokenGateFilter.DPOP_NONCE_HEADER);
    assertThat(nonce).isNotBlank();

    mockMvc
        .perform(dpop(TOKEN, proof(key, TOKEN, nonce)))
        .andExpect(status().isOk())
        .andExpect(header().exists(ExchangeTokenGateFilter.DPOP_NONCE_HEADER));
    assertThat(authFailures("use_dpop_nonce") - challengedBefore).isEqualTo(1.0d);
    assertThat(refused("dpop_invalid") - refusedBefore)
        .as("the nonce round trip is no exchange refusal")
        .isZero();
  }

  @Test
  void aReplayedProofIsRefused() throws Exception {
    String proof = proof(key, TOKEN, nonce());
    mockMvc.perform(dpop(TOKEN, proof)).andExpect(status().isOk());
    double before = refused("dpop_invalid");

    mockMvc
        .perform(dpop(TOKEN, proof))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("DPOP_INVALID"));

    assertThat(refused("dpop_invalid") - before).isEqualTo(1.0d);
  }

  @Test
  void aProofByAnotherKeyIsRefused() throws Exception {
    ECKey other = ExchangeTestSupport.newKey();

    mockMvc
        .perform(dpop(TOKEN, proof(other, TOKEN, nonce())))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("DPOP_INVALID"));
  }

  @Test
  void anUnboundTokenIsRefusedAsDpopRequired() throws Exception {
    mockMvc
        .perform(get(STOCK).header(HttpHeaders.AUTHORIZATION, "Bearer " + UNBOUND_TOKEN))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("DPOP_REQUIRED"));
  }

  @Test
  void anUnboundTokenWithAProofIsRefusedAsDpopRequiredAndGetsTheNonce() throws Exception {
    mockMvc
        .perform(dpop(UNBOUND_TOKEN, proof(key, UNBOUND_TOKEN, nonce())))
        .andExpect(status().isUnauthorized())
        .andExpect(header().exists(ExchangeTokenGateFilter.DPOP_NONCE_HEADER))
        .andExpect(jsonPath("$.code").value("DPOP_REQUIRED"));
  }

  @Test
  void aDpopSchemeRequestWithoutAProofIsRefusedAsDpopRequired() throws Exception {
    mockMvc
        .perform(get(STOCK).header(HttpHeaders.AUTHORIZATION, "DPoP " + TOKEN))
        .andExpect(status().isUnauthorized())
        .andExpect(header().exists(ExchangeTokenGateFilter.DPOP_NONCE_HEADER))
        .andExpect(jsonPath("$.code").value("DPOP_REQUIRED"));
  }

  @Test
  void aBadProofIsRefusedWithTheNonce() throws Exception {
    mockMvc
        .perform(dpop(TOKEN, proof(ExchangeTestSupport.newKey(), TOKEN, nonce())))
        .andExpect(status().isUnauthorized())
        .andExpect(header().exists(ExchangeTokenGateFilter.DPOP_NONCE_HEADER))
        .andExpect(jsonPath("$.code").value("DPOP_INVALID"));
  }

  @Test
  void aTokenForAnotherAudienceIsRefusedWithTheAudiencePropertyBlank() throws Exception {
    double before = refused("unauthenticated");

    mockMvc
        .perform(dpop(FOREIGN_TOKEN, proof(key, FOREIGN_TOKEN, nonce())))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

    assertThat(refused("unauthenticated") - before).isEqualTo(1.0d);
  }

  @Test
  void anAnonymousExchangeRequestIsChallengedForDpop() throws Exception {
    mockMvc
        .perform(get(STOCK))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, Matchers.startsWith("DPoP")))
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
  }

  @Test
  void theContractDocumentsStayAnonymous() throws Exception {
    mockMvc
        .perform(get("/exchange/v1/openapi.json"))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist(ExchangeTokenGateFilter.DPOP_NONCE_HEADER));
  }

  @Test
  void theNonceIsRequiredOnExchangeRoutesOnly() {
    assertThat(
            ExchangeDpopProofValidation.isExchange(
                DPoPProofContext.withDPoPProof("x")
                    .method("POST")
                    .targetUri("https://ingest.example/unrouted")
                    .build()))
        .isFalse();
    assertThat(
            ExchangeDpopProofValidation.isExchange(
                DPoPProofContext.withDPoPProof("x")
                    .method("POST")
                    .targetUri("https://ingest.example/exchange/v1/catalog/resolve")
                    .build()))
        .isTrue();
  }

  /**
   * Fetches a valid nonce through a challenge.
   *
   * @return the nonce
   * @throws Exception if the request fails
   */
  private @NotNull String nonce() throws Exception {
    return mockMvc
        .perform(dpop(TOKEN, proof(key, TOKEN, null)))
        .andReturn()
        .getResponse()
        .getHeader(ExchangeTokenGateFilter.DPOP_NONCE_HEADER);
  }

  /**
   * Signs a proof for a GET of the locations.
   *
   * @param signer the signing key
   * @param token the bound token
   * @param nonce the nonce, or {@code null}
   * @return the proof
   * @throws Exception if signing fails
   */
  private static @NotNull String proof(
      @NotNull ECKey signer, @NotNull String token, @Nullable String nonce) throws Exception {
    return ExchangeTestSupport.proof(signer, token, "GET", STOCK, nonce);
  }

  /**
   * Builds a DPoP-scheme request to the locations.
   *
   * @param token the access token
   * @param proof the proof
   * @return the request
   */
  private static @NotNull MockHttpServletRequestBuilder dpop(
      @NotNull String token, @NotNull String proof) {
    return get(STOCK).header(HttpHeaders.AUTHORIZATION, "DPoP " + token).header("DPoP", proof);
  }

  /**
   * Reads the exchange refusal counter.
   *
   * @param reason the reason
   * @return the count
   */
  private double refused(@NotNull String reason) {
    return meterRegistry
        .get(MetricNames.EXCHANGE_REFUSED)
        .tag(MetricNames.TAG_REASON, reason)
        .counters()
        .stream()
        .mapToDouble(io.micrometer.core.instrument.Counter::count)
        .sum();
  }

  /**
   * Reads the exchange share of the auth-failure counter.
   *
   * @param reason the reason
   * @return the count, zero when never counted
   */
  private double authFailures(@NotNull String reason) {
    Counter counter =
        meterRegistry
            .find(MetricNames.INGEST_AUTH_FAILURES)
            .tag(MetricNames.TAG_REASON, reason)
            .tag(MetricNames.TAG_PATH_SCOPE, MetricNames.PATH_SCOPE_EXCHANGE)
            .counter();
    return counter == null ? 0.0d : counter.count();
  }
}
