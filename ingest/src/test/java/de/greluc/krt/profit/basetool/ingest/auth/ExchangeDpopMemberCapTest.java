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

import com.nimbusds.jose.jwk.ECKey;
import de.greluc.krt.profit.basetool.ingest.handoff.HandoffStagingService;
import de.greluc.krt.profit.basetool.ingest.observability.MetricNames;
import de.greluc.krt.profit.basetool.ingest.registry.ExchangeRegistryReader;
import de.greluc.krt.profit.basetool.ingest.registry.ExchangeRevocationReader;
import de.greluc.krt.profit.basetool.ingest.support.ExchangeTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Pins the refusal a client sees when its member holds too many live DPoP proofs (REQ-XCH-006):
 * {@code 429 DPOP_PROOF_LIMIT} with {@code Retry-After}, while a replayed proof stays {@code 401
 * DPOP_INVALID} and another member still passes.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "app.exchange.limits.dpop-proofs-per-member=2")
@Import(ExchangeTestSupport.ProbeRoutes.class)
class ExchangeDpopMemberCapTest {

  private static final String TOKEN = "capped-member-token";
  private static final String OTHER_TOKEN = "other-member-token";

  @Autowired private WebApplicationContext context;
  @Autowired private MeterRegistry meterRegistry;

  @MockitoBean private JwtDecoder jwtDecoder;
  @MockitoBean private HandoffStagingService handoffStagingService;
  @MockitoBean private ExchangeRegistryReader registryReader;
  @MockitoBean private ExchangeRevocationReader revocationReader;

  private MockMvc mockMvc;
  private ECKey key;
  private ECKey otherKey;

  @BeforeEach
  void setUp() throws Exception {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    key = ExchangeTestSupport.newKey();
    otherKey = ExchangeTestSupport.newKey();
    Instant issued = Instant.now().minusSeconds(5);
    String scopes = "exchange.connect exchange.stock.read";
    when(jwtDecoder.decode(TOKEN))
        .thenReturn(
            ExchangeTestSupport.token(
                TOKEN,
                "basetool-ingest",
                ExchangeTestSupport.thumbprint(key),
                UUID.randomUUID().toString(),
                scopes,
                issued));
    when(jwtDecoder.decode(OTHER_TOKEN))
        .thenReturn(
            ExchangeTestSupport.token(
                OTHER_TOKEN,
                "basetool-ingest",
                ExchangeTestSupport.thumbprint(otherKey),
                UUID.randomUUID().toString(),
                scopes,
                issued));
    when(registryReader.current())
        .thenReturn(
            ExchangeTestSupport.registry(
                true, true, Set.of("exchange.connect", "exchange.stock.read"), null));
    when(revocationReader.isDenied(any())).thenReturn(false);
  }

  @Test
  void aMemberOverTheCapGets429WithRetryAfterWhileAReplayStaysDpopInvalid() throws Exception {
    String first = ExchangeTestSupport.proof(key, TOKEN, "GET", STOCK, nonce(key, TOKEN));
    assertThat(call(TOKEN, first).getStatus()).isEqualTo(200);
    assertThat(call(TOKEN, fresh(key, TOKEN)).getStatus()).isEqualTo(200);

    MockHttpServletResponse capped = call(TOKEN, fresh(key, TOKEN));

    assertThat(capped.getStatus()).isEqualTo(429);
    assertThat(Long.parseLong(capped.getHeader(HttpHeaders.RETRY_AFTER))).isBetween(1L, 31L);
    assertThat(capped.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull();
    assertThat(capped.getHeader(ExchangeTokenGateFilter.DPOP_NONCE_HEADER)).isNotBlank();
    assertThat(capped.getContentAsString())
        .contains("\"status\":429")
        .contains("\"code\":\"DPOP_PROOF_LIMIT\"")
        .contains(
            "\"detail\":\"The member holds too many live DPoP proofs; retry after Retry-After.\"");
    assertThat(refused("dpop_proof_limit")).isEqualTo(1.0d);
    assertThat(authFailures("dpop_proof_limit")).isEqualTo(1.0d);

    MockHttpServletResponse replayed = call(TOKEN, first);

    assertThat(replayed.getStatus()).isEqualTo(401);
    assertThat(replayed.getHeader(HttpHeaders.RETRY_AFTER)).isNull();
    assertThat(replayed.getHeader(HttpHeaders.WWW_AUTHENTICATE))
        .startsWith("DPoP algs=")
        .endsWith("error=\"invalid_dpop_proof\"");
    assertThat(replayed.getContentAsString()).contains("\"code\":\"DPOP_INVALID\"");
    assertThat(call(OTHER_TOKEN, fresh(otherKey, OTHER_TOKEN)).getStatus()).isEqualTo(200);
  }

  /**
   * Reads the exchange refusal counter of a reason for tokens outside the registry's clients.
   *
   * @param reason the reason label
   * @return the count
   */
  private double refused(@NotNull String reason) {
    return meterRegistry
        .get(MetricNames.EXCHANGE_REFUSED)
        .tag(MetricNames.TAG_REASON, reason)
        .tag(MetricNames.TAG_CLIENT_ID, MetricNames.EXCHANGE_CLIENT_NONE)
        .counter()
        .count();
  }

  /**
   * Reads the exchange auth-failure counter of a reason.
   *
   * @param reason the reason label
   * @return the count
   */
  private double authFailures(@NotNull String reason) {
    return meterRegistry
        .get(MetricNames.INGEST_AUTH_FAILURES)
        .tag(MetricNames.TAG_REASON, reason)
        .tag(MetricNames.TAG_PATH_SCOPE, MetricNames.PATH_SCOPE_EXCHANGE)
        .counter()
        .count();
  }

  /**
   * Signs a new proof with the current nonce.
   *
   * @param signer the installation key
   * @param token the bound token
   * @return the proof
   * @throws Exception if signing or the nonce request fails
   */
  private @NotNull String fresh(@NotNull ECKey signer, @NotNull String token) throws Exception {
    return ExchangeTestSupport.proof(signer, token, "GET", STOCK, nonce(signer, token));
  }

  /**
   * Fetches a nonce through a challenge; a proof without a nonce takes no room in the cache.
   *
   * @param signer the installation key
   * @param token the bound token
   * @return the nonce
   * @throws Exception if the request fails
   */
  private @NotNull String nonce(@NotNull ECKey signer, @NotNull String token) throws Exception {
    return call(token, ExchangeTestSupport.proof(signer, token, "GET", STOCK, null))
        .getHeader(ExchangeTokenGateFilter.DPOP_NONCE_HEADER);
  }

  /**
   * Calls the stock route with a DPoP-bound token and a proof.
   *
   * @param token the access token
   * @param proof the proof
   * @return the response
   * @throws Exception if the request fails
   */
  private @NotNull MockHttpServletResponse call(@NotNull String token, @NotNull String proof)
      throws Exception {
    return mockMvc
        .perform(
            get(STOCK).header(HttpHeaders.AUTHORIZATION, "DPoP " + token).header("DPoP", proof))
        .andReturn()
        .getResponse();
  }
}
