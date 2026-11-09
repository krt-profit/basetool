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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.ECKey;
import de.greluc.krt.profit.basetool.ingest.handoff.HandoffStagingService;
import de.greluc.krt.profit.basetool.ingest.registry.ExchangeRegistryReader;
import de.greluc.krt.profit.basetool.ingest.registry.ExchangeRevocationReader;
import de.greluc.krt.profit.basetool.ingest.relay.ExchangeRelay;
import de.greluc.krt.profit.basetool.ingest.store.ExchangeBudget;
import de.greluc.krt.profit.basetool.ingest.store.ExchangeIdempotency;
import de.greluc.krt.profit.basetool.ingest.store.ExchangeQuotas;
import de.greluc.krt.profit.basetool.ingest.support.ExchangeTestSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * The writes on the member's own bank requests through both gates, with the backend relay mocked
 * (REQ-BXCH-004): the gateway checks the body and the request id before the relay, answers a raised
 * request with {@code 201}, passes the bank's refusals on and needs {@code exchange.bank.request}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ExchangeBankRequestWriteRouteTest {

  private static final String TOKEN = "bank-request-write-token";
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final Path EXAMPLES = Path.of("../docs/exchange/examples/v1");
  private static final String BACKEND = "/api/v1/exchange/bank/requests";
  private static final String ROUTE = "/exchange/v1/bank/requests";
  private static final String REQUEST = "33333333-4444-4555-8666-777777777777";
  private static final Set<String> SCOPES = Set.of("exchange.connect", "exchange.bank.request");

  @Autowired private WebApplicationContext context;

  @MockitoBean private JwtDecoder jwtDecoder;
  @MockitoBean private HandoffStagingService stagingService;
  @MockitoBean private ExchangeRegistryReader registryReader;
  @MockitoBean private ExchangeRevocationReader revocationReader;
  @MockitoBean private ExchangeQuotas quotas;
  @MockitoBean private ExchangeIdempotency idempotency;
  @MockitoBean private ExchangeBudget budget;
  @MockitoBean private ExchangeRelay relay;

  private MockMvc mockMvc;
  private ECKey key;

  @BeforeEach
  void setUp() throws Exception {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    key = ExchangeTestSupport.newKey();
    when(jwtDecoder.decode(TOKEN))
        .thenReturn(
            ExchangeTestSupport.token(
                TOKEN,
                "basetool-ingest",
                ExchangeTestSupport.thumbprint(key),
                UUID.randomUUID().toString(),
                String.join(" ", SCOPES),
                Instant.now().minusSeconds(30)));
    when(registryReader.current())
        .thenReturn(ExchangeTestSupport.registryWithLimits(SCOPES, "2.0.0", 120, true));
    when(revocationReader.isDenied(anyString())).thenReturn(false);
    when(quotas.countWrite(anyString(), anyString()))
        .thenReturn(new ExchangeQuotas.Counted("ingest:xch:quota:bank", 1L));
    when(idempotency.claim(anyString())).thenReturn(Optional.of("claim-token"));
    when(budget.reserve(anyString(), anyString(), anyString(), anyLong(), any())).thenReturn(true);
    when(budget.settle(
            anyString(), anyString(), anyString(), anyLong(), anyString(), anyLong(), any()))
        .thenReturn(true);
  }

  @Test
  void aRaisedRequestIsRelayedAndAnsweredWithCreated() throws Exception {
    when(relay.forward(eq(HttpMethod.POST), eq(BACKEND), any(), any(), any()))
        .thenReturn(ok(example("bank-request-write--written/valid/pending.json")));

    call(HttpMethod.POST, ROUTE, example("bank-request-write--raise/valid/withdrawal.json"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("PENDING"))
        .andExpect(jsonPath("$.amount").value(15000));
  }

  @Test
  void aCorrectionIsRelayedToItsRequest() throws Exception {
    when(relay.forward(eq(HttpMethod.PUT), eq(BACKEND + "/" + REQUEST), any(), any(), any()))
        .thenReturn(ok(example("bank-request-write--written/valid/pending.json")));

    call(
            HttpMethod.PUT,
            ROUTE + "/" + REQUEST,
            example("bank-request-write--correction/valid/new-amount.json"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(0));
  }

  @Test
  void aCancelIsRelayedToItsRequest() throws Exception {
    when(relay.forward(
            eq(HttpMethod.POST), eq(BACKEND + "/" + REQUEST + "/cancel"), any(), any(), any()))
        .thenReturn(ok(example("bank-request-write--cancelled/valid/cancelled.json")));

    call(
            HttpMethod.POST,
            ROUTE + "/" + REQUEST + "/cancel",
            example("bank-request-write--cancel/valid/cancel.json"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));
  }

  @Test
  void aBodyOutsideTheSchemaAndAMalformedIdAreRefusedBeforeTheRelay() throws Exception {
    call(
            HttpMethod.POST,
            ROUTE,
            example("bank-request-write--raise/invalid/fractional-amount.json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("SCHEMA_INVALID"));
    call(
            HttpMethod.PUT,
            ROUTE + "/not-an-id",
            example("bank-request-write--correction/valid/new-amount.json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].pointer").value("/id"));

    verify(relay, never()).forward(any(), anyString(), any(), any(), any());
  }

  @Test
  void theBanksRefusalReachesTheClientWithItsCode() throws Exception {
    when(relay.forward(eq(HttpMethod.POST), eq(BACKEND), any(), any(), any()))
        .thenReturn(
            new ExchangeRelay.Result(
                409,
                null,
                "BANK_OPEN_REQUEST_CAP",
                "The member holds the most pending booking requests a client may add to."));

    call(HttpMethod.POST, ROUTE, example("bank-request-write--raise/valid/withdrawal.json"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("BANK_OPEN_REQUEST_CAP"));
  }

  @Test
  void aTokenWithoutTheRequestScopeIsRefused() throws Exception {
    when(registryReader.current())
        .thenReturn(
            ExchangeTestSupport.registryWithLimits(Set.of("exchange.connect"), "2.0.0", 120, true));

    call(HttpMethod.POST, ROUTE, example("bank-request-write--raise/valid/withdrawal.json"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SCOPE_MISSING"));
  }

  /**
   * Calls a route with a fresh DPoP proof and, for a write, an idempotency key.
   *
   * @param method the method
   * @param path the path
   * @param body the JSON body
   * @return the result
   * @throws Exception if the request fails
   */
  private ResultActions call(@NotNull HttpMethod method, @NotNull String path, @NotNull String body)
      throws Exception {
    return ExchangeTestSupport.call(mockMvc, key, TOKEN, method, path, body, "VerseKit/2.1.0");
  }

  /**
   * Reads a published example.
   *
   * @param name the example below {@code docs/exchange/examples/v1}
   * @return its text
   * @throws Exception if it cannot be read
   */
  private static @NotNull String example(@NotNull String name) throws Exception {
    return Files.readString(EXAMPLES.resolve(name));
  }

  /**
   * A usable relay answer.
   *
   * @param json the body
   * @return the result
   */
  private static ExchangeRelay.Result ok(@NotNull String json) {
    return new ExchangeRelay.Result(200, MAPPER.readTree(json), null, null);
  }
}
