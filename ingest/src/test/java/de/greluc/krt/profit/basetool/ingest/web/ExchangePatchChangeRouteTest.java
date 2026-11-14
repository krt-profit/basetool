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
 * The patch change through both gates, with the backend relay mocked (REQ-PATCH-050…052): the read
 * relayed and checked against its schema, the choice checked before the relay and counted as one
 * write, the choice's refusals passed on with their codes, and both behind their own scopes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ExchangePatchChangeRouteTest {

  private static final String TOKEN = "patch-change-token";
  private static final String READ_ONLY = "patch-change-read-only-token";
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final Path EXAMPLES = Path.of("../docs/exchange/examples/v1");
  private static final String READ_ROUTE = "/exchange/v1/me/patch-change";
  private static final String CHOICE_ROUTE = READ_ROUTE + "/home-location";
  private static final String READ_BACKEND = "/api/v1/exchange/me/patch-change";
  private static final String CHOICE_BACKEND = READ_BACKEND + "/home-location";
  private static final Set<String> SCOPES =
      Set.of("exchange.connect", "exchange.patch.read", "exchange.patch.write");

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
    String member = UUID.randomUUID().toString();
    when(jwtDecoder.decode(TOKEN))
        .thenReturn(
            ExchangeTestSupport.token(
                TOKEN,
                "basetool-ingest",
                ExchangeTestSupport.thumbprint(key),
                member,
                String.join(" ", SCOPES),
                Instant.now().minusSeconds(30)));
    when(jwtDecoder.decode(READ_ONLY))
        .thenReturn(
            ExchangeTestSupport.token(
                READ_ONLY,
                "basetool-ingest",
                ExchangeTestSupport.thumbprint(key),
                member,
                "exchange.connect exchange.patch.read",
                Instant.now().minusSeconds(30)));
    when(registryReader.current())
        .thenReturn(ExchangeTestSupport.registryWithLimits(SCOPES, "2.0.0", 120, true));
    when(revocationReader.isDenied(anyString())).thenReturn(false);
    when(quotas.countWrite(anyString(), anyString()))
        .thenReturn(new ExchangeQuotas.Counted("ingest:xch:quota:patch", 1L));
    when(idempotency.claim(anyString())).thenReturn(Optional.of("claim-token"));
    when(budget.reserve(anyString(), anyString(), anyString(), anyLong(), any())).thenReturn(true);
    when(budget.settle(
            anyString(), anyString(), anyString(), anyLong(), anyString(), anyLong(), any()))
        .thenReturn(true);
  }

  @Test
  void theReadIsRelayedAndCheckedAgainstItsSchema() throws Exception {
    when(relay.forward(eq(HttpMethod.GET), eq(READ_BACKEND), isNull(), any(), any()))
        .thenReturn(ok(example("patch-change/valid/pending.json")));

    call(TOKEN, HttpMethod.GET, READ_ROUTE, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.patchChange.label").value("4.4"))
        .andExpect(jsonPath("$.patchChange.options.wipeGroups[0].group").value("CONSUMABLES"))
        .andExpect(jsonPath("$.homeLocation.state").value("PENDING"))
        .andExpect(jsonPath("$.homeLocation.preview[0].rows").value(12));
  }

  @Test
  void aReadOutsideItsContractIsARelayFailure() throws Exception {
    when(relay.forward(eq(HttpMethod.GET), eq(READ_BACKEND), isNull(), any(), any()))
        .thenReturn(
            ok(example("patch-change/valid/pending.json").replace("\"PENDING\"", "\"WAITING\"")));

    call(TOKEN, HttpMethod.GET, READ_ROUTE, null)
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value(ExchangeRelay.RELAY_FAILED));
  }

  @Test
  void theChoiceIsRelayedAndCountedAsOneWrite() throws Exception {
    when(relay.forward(eq(HttpMethod.POST), eq(CHOICE_BACKEND), any(), any(), any()))
        .thenReturn(ok(example("patch-change--homeLocationChosen/valid/chosen.json")));

    call(
            TOKEN,
            HttpMethod.POST,
            CHOICE_ROUTE,
            example("patch-change--homeLocationChoice/valid/choice.json"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.location.name").value("Lorville"))
        .andExpect(jsonPath("$.rows").value(12));

    verify(quotas).countWrite(anyString(), anyString());
  }

  @Test
  void aChoiceOutsideItsSchemaIsRefusedBeforeTheRelay() throws Exception {
    call(
            TOKEN,
            HttpMethod.POST,
            CHOICE_ROUTE,
            example("patch-change--homeLocationChoice/invalid/location-by-name.json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("SCHEMA_INVALID"));

    verify(relay, never()).forward(any(), anyString(), any(), any(), any());
  }

  @Test
  void theChoicesRefusalsReachTheClientWithTheirCodes() throws Exception {
    for (String code :
        new String[] {
          "HOME_LOCATION_NOT_ASKED", "HOME_LOCATION_ALREADY_CHOSEN", "HOME_LOCATION_NOT_OFFERED"
        }) {
      int status = "HOME_LOCATION_NOT_OFFERED".equals(code) ? 400 : 409;
      when(relay.forward(eq(HttpMethod.POST), eq(CHOICE_BACKEND), any(), any(), any()))
          .thenReturn(new ExchangeRelay.Result(status, null, code, "refused"));

      call(
              TOKEN,
              HttpMethod.POST,
              CHOICE_ROUTE,
              example("patch-change--homeLocationChoice/valid/choice.json"))
          .andExpect(status().is(status))
          .andExpect(jsonPath("$.code").value(code));
    }
  }

  @Test
  void theChoiceNeedsTheWriteScope() throws Exception {
    call(
            READ_ONLY,
            HttpMethod.POST,
            CHOICE_ROUTE,
            example("patch-change--homeLocationChoice/valid/choice.json"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SCOPE_MISSING"));

    verify(relay, never()).forward(any(), anyString(), any(), any(), any());
  }

  /**
   * Calls a route with a fresh DPoP proof and, for a write, an idempotency key.
   *
   * @param token the access token to present
   * @param method the method
   * @param path the path
   * @param body the JSON body, or {@code null} for a read
   * @return the result
   * @throws Exception if the request fails
   */
  private ResultActions call(
      @NotNull String token,
      @NotNull HttpMethod method,
      @NotNull String path,
      @Nullable String body)
      throws Exception {
    return ExchangeTestSupport.call(mockMvc, key, token, method, path, body, "VerseKit/2.1.0");
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
