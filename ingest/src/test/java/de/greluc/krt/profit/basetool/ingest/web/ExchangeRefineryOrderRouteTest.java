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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
 * The refinery-order read and write routes through both gates, with the backend relay, the quotas,
 * the idempotency store and the budget mocked (REQ-XCH-051, REQ-XCH-052).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ExchangeRefineryOrderRouteTest {

  private static final String TOKEN = "refinery-order-token";
  private static final String CHANGES = "/exchange/v1/me/refinery-orders/changes";
  private static final String BACKEND_CHANGES = "/api/v1/exchange/me/refinery-orders/changes";
  private static final Path EXAMPLES = Path.of("../docs/exchange/examples/v1");
  private static final String RESULT =
      "{\"dryRun\":false,\"applied\":3,\"unchanged\":0,\"notApplied\":0,\"results\":[]}";
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final String PAGE =
      """
      {"items":[{"id":"%s","versions":{"gameData":2,"finances":0},"status":"IN_PROGRESS",
                 "location":{"name":"ARC-L1 Wide Forest Station"},
                 "goods":[{"id":"%s","input":{"bt":"%s","name":"Quantainium (Raw)"},
                           "inputQuantity":1200,"output":{"bt":"%s","name":"Quantainium"},
                           "outputQuantity":900,"quality":650}]}],
       "removed":[],"nextCursor":"f1.9.0","hasMore":false}
      """
          .formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

  @Autowired private WebApplicationContext context;

  @MockitoBean private JwtDecoder jwtDecoder;
  @MockitoBean private HandoffStagingService handoffStagingService;
  @MockitoBean private ExchangeRegistryReader registryReader;
  @MockitoBean private ExchangeRevocationReader revocationReader;
  @MockitoBean private ExchangeRelay relay;
  @MockitoBean private ExchangeQuotas quotas;
  @MockitoBean private ExchangeIdempotency idempotency;
  @MockitoBean private ExchangeBudget budget;

  private MockMvc mockMvc;
  private ECKey key;

  @BeforeEach
  void setUp() throws Exception {
    when(quotas.countWrite(anyString(), anyString()))
        .thenReturn(new ExchangeQuotas.Counted("ingest:xch:quota:test", 1L));
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    key = ExchangeTestSupport.newKey();
    String scopes = "exchange.connect exchange.refinery.read exchange.refinery.write";
    when(jwtDecoder.decode(TOKEN))
        .thenReturn(
            ExchangeTestSupport.token(
                TOKEN,
                "basetool-ingest",
                ExchangeTestSupport.thumbprint(key),
                UUID.randomUUID().toString(),
                scopes,
                Instant.now().minusSeconds(30)));
    grant(Set.of(scopes.split(" ")));
    when(revocationReader.isDenied(anyString())).thenReturn(false);
    when(idempotency.claim(anyString())).thenReturn(Optional.of("claim-token"));
    when(budget.reserve(anyString(), anyString(), anyString(), anyLong(), any())).thenReturn(true);
    when(budget.settle(
            anyString(), anyString(), anyString(), anyLong(), anyString(), anyLong(), any()))
        .thenReturn(true);
  }

  @Test
  void aPageIsRelayedWithItsCursorAndPassesTheContract() throws Exception {
    when(relay.forward(
            eq(HttpMethod.GET),
            eq("/api/v1/exchange/me/refinery-orders?cursor=f1.8.0&limit=10"),
            isNull(),
            any(),
            any()))
        .thenReturn(ok(PAGE));

    call("/exchange/v1/me/refinery-orders?cursor=f1.8.0&limit=10")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].versions.gameData").value(2))
        .andExpect(jsonPath("$.items[0].goods[0].quality").value(650));
  }

  @Test
  void anOrderWithoutItsSectionCountersBreaksTheContract() throws Exception {
    when(relay.forward(any(), anyString(), any(), any(), any()))
        .thenReturn(ok(PAGE.replace("\"versions\":{\"gameData\":2,\"finances\":0},", "")));

    call("/exchange/v1/me/refinery-orders")
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value(ExchangeRelay.RELAY_FAILED));
  }

  @Test
  void withoutTheReadCapabilityTheRouteIsRefused() throws Exception {
    grant(Set.of("exchange.connect", "exchange.refinery.write"));

    call("/exchange/v1/me/refinery-orders").andExpect(status().isForbidden());

    verify(relay, never()).forward(any(), anyString(), any(), any(), any());
  }

  @Test
  void aChangeSetIsRelayedWithTheWriteCapabilityAndItsAnswerPassesTheContract() throws Exception {
    when(relay.forward(eq(HttpMethod.POST), eq(BACKEND_CHANGES), any(), any(), any()))
        .thenReturn(ok(example("change-result/valid/refinery-orders.json")));

    post(example("change-set--refineryOrderChangeSet/valid/create-then-update-by-client-key.json"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.results[0].reason").value("ALREADY_EXISTS"))
        .andExpect(jsonPath("$.results[0].id").value("3f1c2b4a-6d5e-4a7b-8c9d-0e1f2a3b4c5d"))
        .andExpect(jsonPath("$.orders[1].goods[0]").value("7d8e9f0a-1b2c-4d3e-8f4a-5b6c7d8e9f0a"))
        .andExpect(jsonPath("$.orders[1].versions.gameData").value(10));

    verify(relay).forward(eq(HttpMethod.POST), eq(BACKEND_CHANGES), any(), any(), any());
  }

  @Test
  void withoutTheWriteCapabilityAChangeSetIsRefusedScopeMissing() throws Exception {
    grant(Set.of("exchange.connect", "exchange.refinery.read"));

    post(example("change-set--refineryOrderChangeSet/valid/update-finances.json"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SCOPE_MISSING"));

    verify(relay, never()).forward(any(), anyString(), any(), any(), any());
    verify(quotas, never()).countWrite(anyString(), anyString());
  }

  @Test
  void aChangeSetCountsOnceAgainstTheWriteQuotaWhateverItsOpCount() throws Exception {
    when(relay.forward(any(), anyString(), any(), any(), any())).thenReturn(ok(RESULT));

    post(example("change-set--refineryOrderChangeSet/valid/create-then-update-by-client-key.json"))
        .andExpect(status().isOk());

    verify(quotas, times(1)).countWrite(anyString(), anyString());
    verify(quotas, never()).countFurtherOps(any(), anyLong());
  }

  @Test
  void aChangeSetOutsideTheSchemaIsRefusedBeforeTheRelay() throws Exception {
    for (String invalid :
        new String[] {
          "create-without-client-key.json",
          "update-without-order.json",
          "duration-without-ends-at.json",
          "unknown-op.json"
        }) {
      post(example("change-set--refineryOrderChangeSet/invalid/" + invalid))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("SCHEMA_INVALID"));
    }

    verify(relay, never()).forward(any(), anyString(), any(), any(), any());
  }

  /**
   * Sets what the registry grants the client.
   *
   * @param capabilities the granted capabilities
   */
  private void grant(@NotNull Set<String> capabilities) {
    when(registryReader.current())
        .thenReturn(ExchangeTestSupport.registryWithLimits(capabilities, "2.0.0", 120));
  }

  /**
   * Calls the route with a fresh DPoP proof.
   *
   * @param path the path and query
   * @return the result
   * @throws Exception if the request fails
   */
  private ResultActions call(@NotNull String path) throws Exception {
    return ExchangeTestSupport.call(
        mockMvc, key, TOKEN, HttpMethod.GET, path, null, "VerseKit/2.1.0");
  }

  /**
   * Posts a change set to the write route with a fresh DPoP proof and an idempotency key.
   *
   * @param json the change set
   * @return the result
   * @throws Exception if the request fails
   */
  private ResultActions post(@NotNull String json) throws Exception {
    return ExchangeTestSupport.call(
        mockMvc, key, TOKEN, HttpMethod.POST, CHANGES, json, "VerseKit/2.1.0");
  }

  /**
   * Reads a published example.
   *
   * @param name its path below the examples folder
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
