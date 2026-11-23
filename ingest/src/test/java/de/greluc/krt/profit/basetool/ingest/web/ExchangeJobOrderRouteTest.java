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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.ECKey;
import de.greluc.krt.profit.basetool.ingest.handoff.HandoffStagingService;
import de.greluc.krt.profit.basetool.ingest.registry.ExchangeRegistryReader;
import de.greluc.krt.profit.basetool.ingest.registry.ExchangeRevocationReader;
import de.greluc.krt.profit.basetool.ingest.relay.ExchangeRelay;
import de.greluc.krt.profit.basetool.ingest.support.ExchangeTestSupport;
import java.nio.file.Files;
import java.nio.file.Path;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The route of the member's job-order set through both gates, with the backend relay mocked
 * (REQ-OXCH-002, REQ-OXCH-003): the gateway relays the snapshot, checks the paging before the relay
 * and every answer against the schema of its kind, and the family switch closes the route.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ExchangeJobOrderRouteTest {

  private static final String TOKEN = "job-order-token";
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final Path EXAMPLES = Path.of("../docs/exchange/examples/v1");
  private static final String BACKEND = "/api/v1/exchange/job-orders";
  private static final Set<String> GRANTED = Set.of("exchange.connect", "exchange.joborders.read");

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
                "exchange.connect exchange.joborders.read",
                Instant.now().minusSeconds(30)));
    when(registryReader.current())
        .thenReturn(ExchangeTestSupport.registryWithEveryArea(GRANTED, "2.0.0", 120));
    when(revocationReader.isDenied(anyString())).thenReturn(false);
  }

  @Test
  void theSnapshotIsRelayedAndEachKindCheckedAgainstItsSchema() throws Exception {
    when(relay.forward(eq(HttpMethod.GET), eq(BACKEND + "?limit=2"), isNull(), any(), any()))
        .thenReturn(ok(example("page--jobOrderPage/valid/snapshot.json")));

    call("/exchange/v1/job-orders?limit=2")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].materials[0].open").value(7.25))
        .andExpect(jsonPath("$.items[1].items[0].open").value(3))
        .andExpect(jsonPath("$.items[2].settlementOpen").value(true))
        .andExpect(jsonPath("$.items[0].relations[1]").value("assignee"))
        .andExpect(jsonPath("$.hasMore").value(false));
  }

  @Test
  void anAuecOrderCarryingAContactIsARelayFailure() throws Exception {
    JsonNode page = MAPPER.readTree(example("page--jobOrderPage/valid/snapshot.json"));
    ((ObjectNode) page.get("items").get(2)).put("contact", "Requester#1234");
    when(relay.forward(any(), anyString(), any(), any(), any()))
        .thenReturn(new ExchangeRelay.Result(200, page, null, null));

    call("/exchange/v1/job-orders")
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value(ExchangeRelay.RELAY_FAILED));
  }

  @Test
  void anOrderOutsideTheContractIsARelayFailure() throws Exception {
    when(relay.forward(any(), anyString(), any(), any(), any()))
        .thenReturn(ok(example("page--jobOrderPage/invalid/foreign-link.json")));

    call("/exchange/v1/job-orders")
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value(ExchangeRelay.RELAY_FAILED));
  }

  @Test
  void aLimitOutsideTheRangeIsRefusedBeforeTheRelay() throws Exception {
    call("/exchange/v1/job-orders?limit=1001")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("SCHEMA_INVALID"));

    verify(relay, never()).forward(any(), anyString(), any(), any(), any());
  }

  @Test
  void theFamilySwitchClosesTheRouteBeforeTheRelay() throws Exception {
    when(registryReader.current())
        .thenReturn(ExchangeTestSupport.registryWithLimits(GRANTED, "2.0.0", 120, true));

    call("/exchange/v1/job-orders")
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Retry-After", "300"))
        .andExpect(jsonPath("$.code").value("JOB_ORDER_EXCHANGE_DISABLED"));

    verify(relay, never()).forward(any(), anyString(), any(), any(), any());
  }

  @Test
  void aTokenWithoutTheJobOrderReadScopeIsRefused() throws Exception {
    when(registryReader.current())
        .thenReturn(
            ExchangeTestSupport.registryWithEveryArea(Set.of("exchange.connect"), "2.0.0", 120));

    call("/exchange/v1/job-orders")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SCOPE_MISSING"));
  }

  /**
   * Calls the route with a fresh DPoP proof.
   *
   * @param path the path with its query
   * @return the result
   * @throws Exception if the request fails
   */
  private ResultActions call(@NotNull String path) throws Exception {
    return ExchangeTestSupport.call(
        mockMvc, key, TOKEN, HttpMethod.GET, path, null, "VerseKit/2.1.0");
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
