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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
 * The blueprint read route through both gates, with the backend relay mocked (REQ-XCH-013,
 * REQ-XCH-015).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ExchangeBlueprintRouteTest {

  private static final String TOKEN = "blueprint-token";
  private static final String PATH = "/exchange/v1/me/blueprints";
  private static final String BACKEND = "/api/v1/exchange/me/blueprints";
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final String PAGE =
      """
      {"items":[{"key":"arrowhead","ref":{"bt":"arrowhead","name":"Arrowhead"},"isDefault":false}],
       "removed":[{"key":"s71","removedAt":"2026-09-27T10:00:00Z",
                   "removedBy":{"channel":"client","clientId":"versekit","installationId":"%s"}}],
       "nextCursor":"f1.42","hasMore":false}
      """
          .formatted(UUID.randomUUID());

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
                "exchange.connect exchange.blueprints.read",
                Instant.now().minusSeconds(30)));
    when(registryReader.current())
        .thenReturn(
            ExchangeTestSupport.registryWithLimits(
                Set.of("exchange.connect", "exchange.blueprints.read"), "2.0.0", 120));
    when(revocationReader.isDenied(anyString())).thenReturn(false);
  }

  @Test
  void aSnapshotIsRelayedWithoutParameters() throws Exception {
    when(relay.forward(eq(HttpMethod.GET), eq(BACKEND), isNull(), any(), any()))
        .thenReturn(ok(PAGE));

    call(PATH)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].key").value("arrowhead"))
        .andExpect(jsonPath("$.removed[0].removedBy.channel").value("client"))
        .andExpect(jsonPath("$.nextCursor").value("f1.42"));
  }

  @Test
  void theCursorAndLimitReachTheBackendAndTheProofLeavesTheQueryOut() throws Exception {
    String cursor = "s1.42." + UUID.randomUUID();
    when(relay.forward(
            eq(HttpMethod.GET),
            eq(BACKEND + "?cursor=" + cursor + "&limit=2"),
            isNull(),
            any(),
            any()))
        .thenReturn(ok(PAGE));

    call(PATH + "?cursor=" + cursor + "&limit=2").andExpect(status().isOk());
  }

  @Test
  void anExpiredCursorPassesThroughFromTheBackend() throws Exception {
    when(relay.forward(any(), anyString(), any(), any(), any()))
        .thenReturn(new ExchangeRelay.Result(410, null, "CURSOR_EXPIRED", "Too old."));

    call(PATH + "?cursor=f1.1")
        .andExpect(status().isGone())
        .andExpect(jsonPath("$.code").value("CURSOR_EXPIRED"));
  }

  @Test
  void aCursorOutsideTheIssuedAlphabetExpiresWithoutARelay() throws Exception {
    call(PATH + "?cursor=%7Bx%7D")
        .andExpect(status().isGone())
        .andExpect(jsonPath("$.code").value("CURSOR_EXPIRED"));

    verify(relay, never()).forward(any(), anyString(), any(), any(), any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "1001", "-1", "abc", "99999"})
  void aLimitOutsideTheContractIsRefusedWithoutARelay(String limit) throws Exception {
    call(PATH + "?limit=" + limit)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("SCHEMA_INVALID"))
        .andExpect(jsonPath("$.errors[0].pointer").value("/limit"));

    verify(relay, never()).forward(any(), anyString(), any(), any(), any());
  }

  @Test
  void aPageThatBreaksTheContractIsARelayFailure() throws Exception {
    when(relay.forward(any(), anyString(), any(), any(), any()))
        .thenReturn(ok("{\"items\":[{\"key\":\"x\"}],\"removed\":[],\"hasMore\":false}"));

    call(PATH)
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value(ExchangeRelay.RELAY_FAILED));
  }

  @Test
  void withoutTheReadCapabilityTheRouteIsRefused() throws Exception {
    when(registryReader.current())
        .thenReturn(
            ExchangeTestSupport.registryWithLimits(Set.of("exchange.connect"), "2.0.0", 120));

    call(PATH).andExpect(status().isForbidden());

    verify(relay, never()).forward(any(), anyString(), any(), any(), any());
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
   * A usable relay answer.
   *
   * @param json the body
   * @return the result
   */
  private static ExchangeRelay.Result ok(@NotNull String json) {
    return new ExchangeRelay.Result(200, MAPPER.readTree(json), null, null);
  }
}
