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
import static org.mockito.ArgumentMatchers.startsWith;
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
import org.jetbrains.annotations.Nullable;
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
 * The create options and the create of an aUEC order through both gates, with the backend relay
 * mocked (REQ-OXCH-004, REQ-OXCH-005): the gateway checks the body before the relay, relays the
 * client's key on the create only, answers a created order with {@code 201}, passes the one pair
 * refusal on with its fixed detail and needs {@code exchange.joborders.write}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ExchangeJobOrderWriteRouteTest {

  private static final String TOKEN = "job-order-write-token";
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final Path EXAMPLES = Path.of("../docs/exchange/examples/v1");
  private static final String BACKEND = "/api/v1/exchange/job-orders";
  private static final String ROUTE = "/exchange/v1/job-orders";
  private static final Set<String> SCOPES = Set.of("exchange.connect", "exchange.joborders.write");

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
        .thenReturn(ExchangeTestSupport.registryWithEveryArea(SCOPES, "2.0.0", 120));
    when(revocationReader.isDenied(anyString())).thenReturn(false);
    when(quotas.countWrite(anyString(), anyString()))
        .thenReturn(new ExchangeQuotas.Counted("ingest:xch:quota:job-order", 1L));
    when(idempotency.claim(anyString())).thenReturn(Optional.of("claim-token"));
    when(budget.reserve(anyString(), anyString(), anyString(), anyLong(), any())).thenReturn(true);
    when(budget.settle(
            anyString(), anyString(), anyString(), anyLong(), anyString(), anyLong(), any()))
        .thenReturn(true);
  }

  @Test
  void aCreateRelaysTheClientsKeyAndIsAnsweredWithCreated() throws Exception {
    when(relay.forward(eq(HttpMethod.POST), eq(BACKEND), any(), any(), any(), anyString()))
        .thenReturn(ok(example("job-order-write--created/valid/open.json")));

    call(HttpMethod.POST, ROUTE, example("job-order-write--create/valid/with-comment.json"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("OPEN"))
        .andExpect(jsonPath("$.target").value(250000))
        .andExpect(jsonPath("$.versions.lifecycle").value(0));

    verify(relay)
        .forward(eq(HttpMethod.POST), eq(BACKEND), any(), any(), any(), startsWith("test-"));
  }

  @Test
  void aBodyOutsideTheSchemaIsRefusedBeforeTheRelay() throws Exception {
    call(HttpMethod.POST, ROUTE, example("job-order-write--create/invalid/fractional-target.json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("SCHEMA_INVALID"));
    call(
            HttpMethod.POST,
            ROUTE,
            example("job-order-write--create/invalid/without-responsible-unit.json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("SCHEMA_INVALID"));

    verify(relay, never()).forward(any(), anyString(), any(), any(), any(), any());
  }

  @Test
  void aPairThatIsNotOfferedReachesTheClientWithItsCode() throws Exception {
    String detail =
        "The requesting and responsible unit are not a pair the create options offer; read them"
            + " again and let the member choose; nothing was created.";
    when(relay.forward(eq(HttpMethod.POST), eq(BACKEND), any(), any(), any(), anyString()))
        .thenReturn(new ExchangeRelay.Result(400, null, "JOB_ORDER_UNITS_NOT_OFFERED", detail));

    call(HttpMethod.POST, ROUTE, example("job-order-write--create/valid/with-comment.json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("JOB_ORDER_UNITS_NOT_OFFERED"))
        .andExpect(jsonPath("$.detail").value(detail));
  }

  @Test
  void theCreateOptionsAreRelayedWithoutAKeyAndCheckedAgainstTheirSchema() throws Exception {
    when(relay.forward(eq(HttpMethod.GET), eq(BACKEND + "/create-options"), isNull(), any(), any()))
        .thenReturn(ok(example("job-order-create-options/valid/logistician.json")));

    call(HttpMethod.GET, ROUTE + "/create-options", null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requestingUnits[0].shorthand").value("ALP"))
        .andExpect(jsonPath("$.writesEnabled").value(true));
  }

  @Test
  void createOptionsOutsideTheirSchemaAreARelayFailure() throws Exception {
    when(relay.forward(any(), anyString(), any(), any(), any()))
        .thenReturn(ok(example("job-order-create-options/invalid/without-writes-enabled.json")));

    call(HttpMethod.GET, ROUTE + "/create-options", null)
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value(ExchangeRelay.RELAY_FAILED));
  }

  @Test
  void theReadScopeAloneOpensNeitherTheCreateNorItsOptions() throws Exception {
    Set<String> read = Set.of("exchange.connect", "exchange.joborders.read");
    when(registryReader.current())
        .thenReturn(ExchangeTestSupport.registryWithEveryArea(read, "2.0.0", 120));

    call(HttpMethod.POST, ROUTE, example("job-order-write--create/valid/with-comment.json"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SCOPE_MISSING"));
    call(HttpMethod.GET, ROUTE + "/create-options", null)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SCOPE_MISSING"));
  }

  /**
   * Calls a route with a fresh DPoP proof and, for a write, an idempotency key.
   *
   * @param method the method
   * @param path the path
   * @param body the JSON body, or {@code null} for a read
   * @return the result
   * @throws Exception if the request fails
   */
  private ResultActions call(
      @NotNull HttpMethod method, @NotNull String path, @Nullable String body) throws Exception {
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
