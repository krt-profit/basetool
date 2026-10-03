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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import de.greluc.krt.profit.basetool.ingest.support.ExchangeTestSupport;
import java.time.Instant;
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
 * The stock and ship read routes through both gates, with the backend relay mocked (REQ-XCH-013,
 * REQ-XCH-016, REQ-XCH-017).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ExchangeStockAndShipRouteTest {

  private static final String TOKEN = "stock-ship-token";
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final String STOCK_PAGE =
      """
      {"items":[{"key":"m:1|l:2|q:500|s:0","material":{"bt":"%s","name":"Titanium"},
                 "materialKind":{"type":"RAW","commodity":true},
                 "location":{"name":"Area18","uex":{"kind":"CITY","id":3}},
                 "quality":500,"stolen":false,"quantity":{"amount":3.25,"unit":"SCU"}}],
       "removed":[],"nextCursor":"f1.9.0","hasMore":false}
      """
          .formatted(UUID.randomUUID());
  private static final String SHIP_PAGE =
      """
      {"items":[{"shipId":"%s","version":0,"shipType":{"bt":"%s","name":"Cutlass Black"},
                 "insurance":{"kind":"LTI"},"fitted":false}],
       "removed":[],"nextCursor":"f1.9.0","hasMore":false}
      """
          .formatted(UUID.randomUUID(), UUID.randomUUID());

  @Autowired private WebApplicationContext context;

  @MockitoBean private JwtDecoder jwtDecoder;
  @MockitoBean private HandoffStagingService handoffStagingService;
  @MockitoBean private ExchangeRegistryReader registryReader;
  @MockitoBean private ExchangeRevocationReader revocationReader;
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
                "exchange.connect exchange.stock.read exchange.hangar.read",
                Instant.now().minusSeconds(30)));
    grant(Set.of("exchange.connect", "exchange.stock.read", "exchange.hangar.read"));
    when(revocationReader.isDenied(anyString())).thenReturn(false);
  }

  @Test
  void aStockPageIsRelayedWithItsCursor() throws Exception {
    when(relay.forward(
            eq(HttpMethod.GET),
            eq("/api/v1/exchange/me/stock?cursor=f1.8.0&limit=10"),
            isNull(),
            any(),
            any()))
        .thenReturn(ok(STOCK_PAGE));

    call("/exchange/v1/me/stock?cursor=f1.8.0&limit=10")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].quantity.amount").value(3.25))
        .andExpect(jsonPath("$.items[0].materialKind.type").value("RAW"));
  }

  @Test
  void anUnnamedShipPassesTheContract() throws Exception {
    when(relay.forward(eq(HttpMethod.GET), eq("/api/v1/exchange/me/ships"), isNull(), any(), any()))
        .thenReturn(ok(SHIP_PAGE));

    call("/exchange/v1/me/ships")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].insurance.kind").value("LTI"))
        .andExpect(jsonPath("$.items[0].name").doesNotExist());
  }

  @Test
  void aShipWithoutVersionBreaksTheContract() throws Exception {
    when(relay.forward(any(), anyString(), any(), any(), any()))
        .thenReturn(ok(SHIP_PAGE.replace("\"version\":0,", "")));

    call("/exchange/v1/me/ships")
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value(ExchangeRelay.RELAY_FAILED));
  }

  @Test
  void eachRouteNeedsItsOwnCapability() throws Exception {
    grant(Set.of("exchange.connect", "exchange.stock.read"));

    call("/exchange/v1/me/ships").andExpect(status().isForbidden());

    verify(relay, never()).forward(any(), anyString(), any(), any(), any());
  }

  @Test
  void aStockLimitOutsideTheContractIsRefused() throws Exception {
    call("/exchange/v1/me/stock?limit=0")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].pointer").value("/limit"));
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
   * Calls a route with a fresh DPoP proof.
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
   * A usable relay answer.
   *
   * @param json the body
   * @return the result
   */
  private static ExchangeRelay.Result ok(@NotNull String json) {
    return new ExchangeRelay.Result(200, MAPPER.readTree(json), null, null);
  }
}
