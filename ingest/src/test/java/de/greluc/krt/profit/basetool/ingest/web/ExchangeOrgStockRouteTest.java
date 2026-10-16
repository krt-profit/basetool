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
import java.util.Locale;
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
 * The org stock through both gates, with the backend relay mocked: relayed with its page parameters
 * and checked against the published schema, and refused without the capability (REQ-XCH-042).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ExchangeOrgStockRouteTest {

  private static final String TOKEN = "orgstock-token";
  private static final String STOCK_ONLY = "orgstock-stock-only-token";
  private static final String PATH = "/exchange/v1/org-stock";
  private static final String BACKEND = "/api/v1/exchange/org-stock";
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final UUID UNIT = UUID.randomUUID();
  private static final String PAGE =
      """
      {"items":[{"key":"u:%1$s|m:x","unit":{"id":"%1$s","name":"Staffel Iridium"},
                 "material":{"bt":"%2$s","name":"Laranite"},"location":{"name":"Area18"},
                 "quality":500,"stolen":false,"quantity":{"amount":12.5,"unit":"SCU"},
                 "earmarked":{"jobOrders":{"amount":2,"unit":"SCU"},
                              "missions":{"amount":0,"unit":"SCU"}}}],
       "removed":[],
       "units":[{"id":"%1$s","name":"Staffel Iridium","shorthand":"IRI","kind":"SQUADRON"}],
       "nextCursor":null,"hasMore":false}
      """
          .formatted(UNIT, UUID.randomUUID());

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
    String member = UUID.randomUUID().toString();
    when(jwtDecoder.decode(TOKEN))
        .thenReturn(
            ExchangeTestSupport.token(
                TOKEN,
                "basetool-ingest",
                ExchangeTestSupport.thumbprint(key),
                member,
                "exchange.connect exchange.orgstock.read exchange.orgstock.write",
                Instant.now().minusSeconds(30)));
    when(jwtDecoder.decode(STOCK_ONLY))
        .thenReturn(
            ExchangeTestSupport.token(
                STOCK_ONLY,
                "basetool-ingest",
                ExchangeTestSupport.thumbprint(key),
                member,
                "exchange.connect exchange.stock.read",
                Instant.now().minusSeconds(30)));
    when(registryReader.current())
        .thenReturn(
            ExchangeTestSupport.registryWithLimits(
                Set.of(
                    "exchange.connect",
                    "exchange.stock.read",
                    "exchange.orgstock.read",
                    "exchange.orgstock.write"),
                "2.0.0",
                120));
    when(revocationReader.isDenied(anyString())).thenReturn(false);
  }

  @Test
  void theSnapshotIsRelayedAndCheckedAgainstItsSchema() throws Exception {
    when(relay.forward(eq(HttpMethod.GET), eq(BACKEND), isNull(), any(), any()))
        .thenReturn(ok(PAGE));

    call(TOKEN, PATH)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].unit.id").value(UNIT.toString()))
        .andExpect(jsonPath("$.items[0].earmarked.jobOrders.amount").value(2))
        .andExpect(jsonPath("$.units[0].kind").value("SQUADRON"));
  }

  @Test
  void theCursorAndTheLimitArePassedOn() throws Exception {
    String cursor = "s1.10.0." + UUID.randomUUID();
    when(relay.forward(
            eq(HttpMethod.GET),
            eq(BACKEND + "?cursor=" + cursor + "&limit=2"),
            isNull(),
            any(),
            any()))
        .thenReturn(ok(PAGE));

    call(TOKEN, PATH + "?cursor=" + cursor + "&limit=2").andExpect(status().isOk());
  }

  @Test
  void aPageWithoutItsUnitsIsARelayFailure() throws Exception {
    when(relay.forward(any(), anyString(), any(), any(), any()))
        .thenReturn(ok(PAGE.replaceFirst("\"units\":\\[[^\\]]*\\],", "")));

    call(TOKEN, PATH)
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value(ExchangeRelay.RELAY_FAILED));
  }

  @Test
  void theRosterIsRelayedForTheNamedUnitAndCheckedAgainstItsSchema() throws Exception {
    when(relay.forward(
            eq(HttpMethod.GET), eq(BACKEND + "/members?unit=" + UNIT), isNull(), any(), any()))
        .thenReturn(
            ok(
                """
                {"unit":{"id":"%s","name":"Staffel Iridium"},
                 "items":[{"ref":"r1","name":"Cpt. Holder"}]}
                """
                    .formatted(UNIT)));

    call(TOKEN, PATH + "/members?unit=" + UNIT.toString().toUpperCase(Locale.ROOT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].ref").value("r1"));
  }

  @Test
  void aRosterWithoutAValidUnitIsRefusedBeforeTheRelay() throws Exception {
    call(TOKEN, PATH + "/members")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("SCHEMA_INVALID"))
        .andExpect(jsonPath("$.errors[0].pointer").value("/unit"));
    call(TOKEN, PATH + "/members?unit=..%2Fme%2Fstock")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("SCHEMA_INVALID"));

    verify(relay, never()).forward(any(), anyString(), any(), any(), any());
  }

  @Test
  void aRosterAnswerWithoutItsUnitIsARelayFailure() throws Exception {
    when(relay.forward(any(), anyString(), any(), any(), any())).thenReturn(ok("{\"items\":[]}"));

    call(TOKEN, PATH + "/members?unit=" + UNIT)
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value(ExchangeRelay.RELAY_FAILED));
  }

  @Test
  void aTokenWithoutTheCapabilityIsRefusedBeforeTheRelay() throws Exception {
    call(STOCK_ONLY, PATH)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SCOPE_MISSING"));
    call(STOCK_ONLY, PATH + "/members?unit=" + UNIT)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SCOPE_MISSING"));

    verify(relay, never()).forward(any(), anyString(), any(), any(), any());
  }

  /**
   * Calls a route with a fresh DPoP proof.
   *
   * @param token the access token to present
   * @param target the path with its query
   * @return the result
   * @throws Exception if the request fails
   */
  private ResultActions call(@NotNull String token, @NotNull String target) throws Exception {
    return ExchangeTestSupport.call(
        mockMvc, key, token, HttpMethod.GET, target, null, "VerseKit/2.1.0");
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
