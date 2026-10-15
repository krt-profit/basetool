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
 * The member's org units through both gates, with the backend relay mocked: relayed and checked
 * against the published schema, and refused without the capability (REQ-XCH-048).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ExchangeOrgUnitsRouteTest {

  private static final String TOKEN = "orgunits-token";
  private static final String CONNECT_ONLY = "orgunits-connect-only-token";
  private static final String PATH = "/exchange/v1/me/org-units";
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final String UNITS =
      """
      {"items":[{"id":"%s","name":"Staffel Iridium","shorthand":"IRI","kind":"SQUADRON"},
                {"id":"%s","name":"Bereich Logistik","shorthand":"LOG","kind":"BEREICH"}]}
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
    String member = UUID.randomUUID().toString();
    when(jwtDecoder.decode(TOKEN))
        .thenReturn(
            ExchangeTestSupport.token(
                TOKEN,
                "basetool-ingest",
                ExchangeTestSupport.thumbprint(key),
                member,
                "exchange.connect exchange.orgunits.read",
                Instant.now().minusSeconds(30)));
    when(jwtDecoder.decode(CONNECT_ONLY))
        .thenReturn(
            ExchangeTestSupport.token(
                CONNECT_ONLY,
                "basetool-ingest",
                ExchangeTestSupport.thumbprint(key),
                member,
                "exchange.connect",
                Instant.now().minusSeconds(30)));
    when(registryReader.current())
        .thenReturn(
            ExchangeTestSupport.registryWithLimits(
                Set.of("exchange.connect", "exchange.orgunits.read"), "2.0.0", 120));
    when(revocationReader.isDenied(anyString())).thenReturn(false);
  }

  @Test
  void theOrgUnitsAreRelayedAndCheckedAgainstTheirSchema() throws Exception {
    when(relay.forward(
            eq(HttpMethod.GET), eq("/api/v1/exchange/me/org-units"), isNull(), any(), any()))
        .thenReturn(ok(UNITS));

    call(TOKEN)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].kind").value("SQUADRON"))
        .andExpect(jsonPath("$.items[1].shorthand").value("LOG"));
  }

  @Test
  void aMemberWithoutMembershipsGetsAnEmptyList() throws Exception {
    when(relay.forward(
            eq(HttpMethod.GET), eq("/api/v1/exchange/me/org-units"), isNull(), any(), any()))
        .thenReturn(ok("{\"items\":[]}"));

    call(TOKEN).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
  }

  @Test
  void anOrgUnitOutsideTheContractIsARelayFailure() throws Exception {
    when(relay.forward(any(), anyString(), any(), any(), any()))
        .thenReturn(ok(UNITS.replace("\"kind\":\"SQUADRON\"", "\"kind\":\"GUILD\"")));

    call(TOKEN)
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value(ExchangeRelay.RELAY_FAILED));
  }

  @Test
  void aTokenWithoutTheCapabilityIsRefusedBeforeTheRelay() throws Exception {
    call(CONNECT_ONLY)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SCOPE_MISSING"));

    verify(relay, never()).forward(any(), anyString(), any(), any(), any());
  }

  /**
   * Calls the route with a fresh DPoP proof.
   *
   * @param token the access token to present
   * @return the result
   * @throws Exception if the request fails
   */
  private ResultActions call(@NotNull String token) throws Exception {
    return ExchangeTestSupport.call(
        mockMvc, key, token, HttpMethod.GET, PATH, null, "VerseKit/2.1.0");
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
