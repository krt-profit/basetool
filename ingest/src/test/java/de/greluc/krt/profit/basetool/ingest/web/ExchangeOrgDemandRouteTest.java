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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.TreeSet;
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

/**
 * The org demand route through both gates, with the backend relay mocked, and the schema that keeps
 * it anonymous (REQ-XCH-018).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ExchangeOrgDemandRouteTest {

  private static final String TOKEN = "demand-token";
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final Path SCHEMAS = Path.of("src/main/resources/exchange/v1/schemas");
  private static final String DEMAND =
      """
      {"materials":[{"material":{"bt":"%s","name":"Quantanium"},"rawRefs":[],"minQuality":650,
                     "openQuantity":{"amount":12.5,"unit":"SCU"},"source":"material-order"}],
       "items":[{"item":{"bt":"%s","name":"Arrowhead"},
                 "openQuantity":{"amount":5,"unit":"PIECE"},"craftableByMe":false}],
       "updatedAt":"2026-09-27T12:00:00Z"}
      """
          .formatted(UUID.randomUUID(), UUID.randomUUID());
  private static final String WITHHELD =
      """
      {"materials":[],"items":[],"updatedAt":"2026-09-28T12:00:00Z","reason":"%s"}
      """;

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
                "exchange.connect exchange.demand.read",
                Instant.now().minusSeconds(30)));
    when(registryReader.current())
        .thenReturn(
            ExchangeTestSupport.registryWithLimits(
                Set.of("exchange.connect", "exchange.demand.read"), "2.0.0", 120));
    when(revocationReader.isDenied(anyString())).thenReturn(false);
  }

  @Test
  void theDemandIsRelayedAndCheckedAgainstItsSchema() throws Exception {
    when(relay.forward(
            eq(HttpMethod.GET), eq("/api/v1/exchange/me/org-demand"), isNull(), any(), any()))
        .thenReturn(ok(DEMAND));

    call()
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.materials[0].source").value("material-order"))
        .andExpect(jsonPath("$.items[0].craftableByMe").value(false));
  }

  @Test
  void aDemandOutsideTheContractIsARelayFailure() throws Exception {
    when(relay.forward(any(), anyString(), any(), any(), any()))
        .thenReturn(ok(DEMAND.replace("\"source\":\"material-order\"", "\"source\":\"x\"")));

    call()
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value(ExchangeRelay.RELAY_FAILED));
  }

  @Test
  void aWithheldDemandIsRelayedWithItsReason() throws Exception {
    when(relay.forward(
            eq(HttpMethod.GET), eq("/api/v1/exchange/me/org-demand"), isNull(), any(), any()))
        .thenReturn(ok(WITHHELD.formatted("NOT_PERMITTED")));

    call()
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reason").value("NOT_PERMITTED"))
        .andExpect(jsonPath("$.materials.length()").value(0))
        .andExpect(jsonPath("$.items.length()").value(0));
  }

  @Test
  void aReasonOutsideTheContractIsARelayFailure() throws Exception {
    when(relay.forward(any(), anyString(), any(), any(), any()))
        .thenReturn(ok(WITHHELD.formatted("HIDDEN")));

    call()
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value(ExchangeRelay.RELAY_FAILED));
  }

  @Test
  void theSchemaDeclaresNoPersonOrderOrFreeTextField() throws Exception {
    JsonNode schema = MAPPER.readTree(Files.readString(SCHEMAS.resolve("org-demand.schema.json")));

    assertThat(names(schema.at("/properties")))
        .containsExactly("items", "materials", "reason", "updatedAt");
    assertThat(schema.at("/properties/reason/enum").valueStream().map(JsonNode::asString))
        .containsExactly("NOT_PERMITTED");
    assertThat(names(schema.at("/properties/materials/items/properties")))
        .containsExactly("material", "minQuality", "openQuantity", "rawRefs", "source");
    assertThat(names(schema.at("/properties/items/items/properties")))
        .containsExactly("craftableByMe", "item", "openQuantity");
  }

  /**
   * Calls the route with a fresh DPoP proof.
   *
   * @return the result
   * @throws Exception if the request fails
   */
  private ResultActions call() throws Exception {
    return ExchangeTestSupport.call(
        mockMvc, key, TOKEN, HttpMethod.GET, "/exchange/v1/me/org-demand", null, "VerseKit/2.1.0");
  }

  /**
   * Lists the property names of a schema object, sorted.
   *
   * @param properties the {@code properties} object
   * @return the names
   */
  private static @NotNull Set<String> names(@NotNull JsonNode properties) {
    Set<String> names = new TreeSet<>();
    properties.propertyNames().forEach(names::add);
    return names;
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
