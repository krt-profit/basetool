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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nimbusds.jose.jwk.ECKey;
import de.greluc.krt.profit.basetool.ingest.gate.ExchangeRequestContext;
import de.greluc.krt.profit.basetool.ingest.handoff.HandoffStagingService;
import de.greluc.krt.profit.basetool.ingest.registry.ExchangeRegistryReader;
import de.greluc.krt.profit.basetool.ingest.registry.ExchangeRevocationReader;
import de.greluc.krt.profit.basetool.ingest.relay.ExchangeRelay;
import de.greluc.krt.profit.basetool.ingest.support.ExchangeTestSupport;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The first relayed exchange routes end to end through both gates, with the backend relay mocked
 * (REQ-XCH-001, REQ-XCH-011, REQ-XCH-026).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ExchangeControllerTest {

  private static final String TOKEN = "controller-token";
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @Autowired private WebApplicationContext context;

  @MockitoBean private JwtDecoder jwtDecoder;
  @MockitoBean private HandoffStagingService handoffStagingService;
  @MockitoBean private ExchangeRegistryReader registryReader;
  @MockitoBean private ExchangeRevocationReader revocationReader;
  @MockitoBean private ExchangeRelay relay;

  private MockMvc mockMvc;
  private ECKey key;
  private String member;

  @BeforeEach
  void setUp() throws Exception {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    key = ExchangeTestSupport.newKey();
    member = UUID.randomUUID().toString();
    when(jwtDecoder.decode(TOKEN))
        .thenReturn(
            ExchangeTestSupport.token(
                TOKEN,
                "basetool-ingest",
                ExchangeTestSupport.thumbprint(key),
                member,
                "exchange.connect exchange.stock.read",
                Instant.now().minusSeconds(30)));
    when(registryReader.current())
        .thenReturn(
            ExchangeTestSupport.registryWithLimits(
                Set.of("exchange.connect", "exchange.stock.read"), "2.0.0", 120));
    when(revocationReader.isDenied(anyString())).thenReturn(false);
  }

  @Test
  void resolveRelaysAValidBodyAndReportsItsUnknownFields() throws Exception {
    when(relay.forward(
            eq(HttpMethod.POST), eq("/api/v1/exchange/catalog/resolve"), any(), any(), isNull()))
        .thenReturn(ok("{\"results\":[{\"index\":0,\"status\":\"unmatched\"}]}"));

    ExchangeTestSupport.call(
            mockMvc,
            key,
            TOKEN,
            HttpMethod.POST,
            "/exchange/v1/catalog/resolve",
            "{\"kind\":\"ITEM\",\"hint\":1,\"refs\":[{\"name\":\"x\",\"colour\":\"red\"}]}",
            "VerseKit/2.1.0")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.results[0].status").value("unmatched"))
        .andExpect(jsonPath("$.warnings.length()").value(2))
        .andExpect(jsonPath("$.warnings[?(@.pointer == '/hint')].code").value("UNKNOWN_FIELD"))
        .andExpect(jsonPath("$.warnings[?(@.pointer == '/refs/0/colour')]").exists());

    ArgumentCaptor<ExchangeRequestContext> admitted =
        ArgumentCaptor.forClass(ExchangeRequestContext.class);
    verify(relay)
        .forward(
            eq(HttpMethod.POST),
            eq("/api/v1/exchange/catalog/resolve"),
            any(),
            admitted.capture(),
            isNull());
    assertThat(admitted.getValue().member()).isEqualTo(member);
    assertThat(admitted.getValue().capabilities())
        .containsExactlyInAnyOrder("exchange.connect", "exchange.stock.read");
    assertThat(admitted.getValue().connectedAt())
        .isBetween(Instant.now().getEpochSecond() - 120L, Instant.now().getEpochSecond());
  }

  @Test
  void anInvalidBodyIsRefusedBeforeTheRelay() throws Exception {
    ExchangeTestSupport.call(
            mockMvc,
            key,
            TOKEN,
            HttpMethod.POST,
            "/exchange/v1/catalog/resolve",
            "{\"kind\":\"SPACESHIP\",\"refs\":[]}",
            "VerseKit/2.1.0")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("SCHEMA_INVALID"))
        .andExpect(jsonPath("$.errors[?(@.pointer == '/kind')]").exists());

    verify(relay, never()).forward(any(), anyString(), any(), any(), any());
  }

  @Test
  void anAnswerThatBreaksTheContractIsARelayFailure() throws Exception {
    when(relay.forward(
            eq(HttpMethod.GET), eq("/api/v1/exchange/catalog/locations"), any(), any(), any()))
        .thenReturn(ok("{\"places\":[]}"));

    ExchangeTestSupport.call(
            mockMvc,
            key,
            TOKEN,
            HttpMethod.GET,
            "/exchange/v1/catalog/locations",
            null,
            "VerseKit/2.1.0")
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value("BACKEND_RELAY_FAILED"));
  }

  @Test
  void locationsPassThroughWhenTheyMatch() throws Exception {
    when(relay.forward(
            eq(HttpMethod.GET), eq("/api/v1/exchange/catalog/locations"), any(), any(), any()))
        .thenReturn(ok("{\"items\":[{\"name\":\"Area18\",\"uex\":{\"kind\":\"CITY\",\"id\":3}}]}"));

    ExchangeTestSupport.call(
            mockMvc,
            key,
            TOKEN,
            HttpMethod.GET,
            "/exchange/v1/catalog/locations",
            null,
            "VerseKit/2.1.0")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].name").value("Area18"));
  }

  @Test
  void aBackendRefusalWithARegistryCodePassesThrough() throws Exception {
    when(relay.forward(any(), anyString(), any(), any(), any()))
        .thenReturn(new ExchangeRelay.Result(403, null, "TERMS_NOT_ACCEPTED", "Accept the terms."));

    ExchangeTestSupport.call(
            mockMvc,
            key,
            TOKEN,
            HttpMethod.GET,
            "/exchange/v1/catalog/locations",
            null,
            "VerseKit/2.1.0")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("TERMS_NOT_ACCEPTED"));
  }

  @Test
  void aRelayedUnavailableGateCodeCarriesTheGatewayGatesRetryAfter() throws Exception {
    when(relay.forward(any(), anyString(), any(), any(), any()))
        .thenReturn(
            new ExchangeRelay.Result(503, null, "EXCHANGE_DISABLED", "The exchange is off."));

    ExchangeTestSupport.call(
            mockMvc,
            key,
            TOKEN,
            HttpMethod.GET,
            "/exchange/v1/catalog/locations",
            null,
            "VerseKit/2.1.0")
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Retry-After", "30"))
        .andExpect(jsonPath("$.code").value("EXCHANGE_DISABLED"));
  }

  @Test
  void theServiceDocumentAnswersTheBackendGatesRefusalAsTheGatewayGateDoes() throws Exception {
    when(relay.forward(
            eq(HttpMethod.GET), eq("/api/v1/exchange/me/installation"), isNull(), any(), any()))
        .thenReturn(
            new ExchangeRelay.Result(403, null, "CLIENT_SUSPENDED", "This client is suspended."));

    ExchangeTestSupport.call(
            mockMvc, key, TOKEN, HttpMethod.GET, "/exchange/v1", null, "VerseKit/2.1.0")
        .andExpect(status().isForbidden())
        .andExpect(header().doesNotExist("Retry-After"))
        .andExpect(jsonPath("$.code").value("CLIENT_SUSPENDED"))
        .andExpect(jsonPath("$.apiVersion").doesNotExist());
  }

  @Test
  void theServiceDocumentNamesTheGrantsLimitsAndInstallation() throws Exception {
    when(relay.forward(
            eq(HttpMethod.GET), eq("/api/v1/exchange/me/installation"), isNull(), any(), any()))
        .thenReturn(ok("{\"installationId\":\"inst-1\",\"firstSeenAt\":\"2026-09-27T10:00:00Z\"}"));

    ExchangeTestSupport.call(
            mockMvc, key, TOKEN, HttpMethod.GET, "/exchange/v1", null, "VerseKit/2.1.0")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.apiVersion").value("1.0"))
        .andExpect(jsonPath("$.capabilities.length()").value(2))
        .andExpect(jsonPath("$.installationId").value("inst-1"))
        .andExpect(jsonPath("$.limits.batchMaxOps").value(500))
        .andExpect(jsonPath("$.limits.requestsPerMinute").value(120))
        .andExpect(jsonPath("$.minClientVersion").value("2.0.0"))
        .andExpect(jsonPath("$.docsUrl").exists());
  }

  @Test
  void theServiceDocumentStandsWithoutAnInstallation() throws Exception {
    when(relay.forward(any(), anyString(), any(), any(), any()))
        .thenReturn(ExchangeRelay.Result.failed());

    ExchangeTestSupport.call(
            mockMvc, key, TOKEN, HttpMethod.GET, "/exchange/v1", null, "VerseKit/2.1.0")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.installationId").doesNotExist());
  }

  @Test
  void aLabelThatBreaksTheRuleNeverReachesTheBackend() throws Exception {
    ExchangeTestSupport.call(
            mockMvc,
            key,
            TOKEN,
            HttpMethod.POST,
            "/exchange/v1/me/installation",
            "{\"label\":\" leading space\"}",
            "VerseKit/2.1.0")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("SCHEMA_INVALID"));

    verify(relay, never()).forward(any(), anyString(), any(), any(), any());
  }

  @Test
  void theServiceDocumentOmitsLimitsTheClientDoesNotOverride() throws Exception {
    when(registryReader.current())
        .thenReturn(
            ExchangeTestSupport.registry(
                true, true, Set.of("exchange.connect", "exchange.stock.read"), null));
    when(relay.forward(any(), anyString(), any(), any(), any()))
        .thenReturn(ok("{\"firstSeenAt\":\"2026-09-27T10:00:00Z\"}"));

    ExchangeTestSupport.call(mockMvc, key, TOKEN, HttpMethod.GET, "/exchange/v1", null, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.installationId").doesNotExist())
        .andExpect(jsonPath("$.limits.requestsPerMinute").doesNotExist())
        .andExpect(jsonPath("$.minClientVersion").isEmpty());
  }

  @Test
  void unknownFieldsJoinTheWarningsTheBackendAlreadySent() throws Exception {
    when(relay.forward(any(), anyString(), any(), any(), any()))
        .thenReturn(
            ok(
                "{\"results\":[{\"index\":0,\"status\":\"unmatched\"}],"
                    + "\"warnings\":[{\"pointer\":\"/refs/0/locKey\",\"code\":\"LOC_KEY_UNRESOLVED\"}]}"));

    ExchangeTestSupport.call(
            mockMvc,
            key,
            TOKEN,
            HttpMethod.POST,
            "/exchange/v1/catalog/resolve",
            "{\"kind\":\"ITEM\",\"extra\":true,\"refs\":[{\"locKey\":\"k\"}]}",
            "VerseKit/2.1.0")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.warnings.length()").value(2))
        .andExpect(jsonPath("$.warnings[1].pointer").value("/extra"));
  }

  @Test
  void aCleanBodyGetsNoWarnings() throws Exception {
    when(relay.forward(any(), anyString(), any(), any(), any()))
        .thenReturn(ok("{\"results\":[{\"index\":0,\"status\":\"unmatched\"}]}"));

    ExchangeTestSupport.call(
            mockMvc,
            key,
            TOKEN,
            HttpMethod.POST,
            "/exchange/v1/catalog/resolve",
            "{\"kind\":\"ITEM\",\"refs\":[{\"name\":\"x\"}]}",
            "VerseKit/2.1.0")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.warnings").doesNotExist());
  }

  @Test
  void aLabelIsRelayedAndItsUnknownFieldsIgnored() throws Exception {
    when(relay.forward(
            eq(HttpMethod.POST), eq("/api/v1/exchange/me/installation"), any(), any(), any()))
        .thenReturn(ok("{\"installationId\":\"inst-1\",\"label\":\"Gaming PC\"}"));

    ExchangeTestSupport.call(
            mockMvc,
            key,
            TOKEN,
            HttpMethod.POST,
            "/exchange/v1/me/installation",
            "{\"label\":\"Gaming PC\",\"hostname\":\"never-sent\"}",
            "VerseKit/2.1.0")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.label").value("Gaming PC"))
        .andExpect(jsonPath("$.warnings").doesNotExist());
  }

  @Test
  void theAccountCheckIsRelayedAndAnswersOnlyTheResult() throws Exception {
    when(relay.forward(
            eq(HttpMethod.POST), eq("/api/v1/exchange/me/account-check"), any(), any(), any()))
        .thenReturn(ok("{\"result\":\"mismatch\"}"));

    ExchangeTestSupport.call(
            mockMvc,
            key,
            TOKEN,
            HttpMethod.POST,
            "/exchange/v1/me/account-check",
            "{\"handle\":\"Alt_Account\"}",
            "VerseKit/2.1.0")
        .andExpect(status().isOk())
        .andExpect(content().json("{\"result\":\"mismatch\"}", JsonCompareMode.STRICT));

    ArgumentCaptor<JsonNode> relayed = ArgumentCaptor.forClass(JsonNode.class);
    verify(relay)
        .forward(
            eq(HttpMethod.POST),
            eq("/api/v1/exchange/me/account-check"),
            relayed.capture(),
            any(),
            any());
    assertThat(relayed.getValue().get("handle").asString()).isEqualTo("Alt_Account");
  }

  @Test
  void aValueThatIsNoHandleIsRefusedWithoutEchoOrLogAndNeverRelayed() throws Exception {
    Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    root.addAppender(appender);
    String body;
    try {
      body =
          ExchangeTestSupport.call(
                  mockMvc,
                  key,
                  TOKEN,
                  HttpMethod.POST,
                  "/exchange/v1/me/account-check",
                  "{\"handle\":\"guess who;\"}",
                  "VerseKit/2.1.0")
              .andExpect(status().isBadRequest())
              .andExpect(jsonPath("$.code").value("SCHEMA_INVALID"))
              .andExpect(jsonPath("$.errors[0].pointer").value("/handle"))
              .andReturn()
              .getResponse()
              .getContentAsString();
    } finally {
      root.detachAppender(appender);
    }

    assertThat(body).doesNotContain("guess who");
    assertThat(appender.list).noneMatch(e -> e.getFormattedMessage().contains("guess who"));
    verify(relay, never()).forward(any(), anyString(), any(), any(), any());
  }

  @Test
  void anAccountCheckAnswerOutsideTheContractIsARelayFailure() throws Exception {
    when(relay.forward(any(), anyString(), any(), any(), any()))
        .thenReturn(ok("{\"result\":\"maybe\"}"));

    ExchangeTestSupport.call(
            mockMvc,
            key,
            TOKEN,
            HttpMethod.POST,
            "/exchange/v1/me/account-check",
            "{\"handle\":\"Cutter_Pilot\"}",
            "VerseKit/2.1.0")
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value("BACKEND_RELAY_FAILED"));
  }

  /**
   * Builds a usable relay result.
   *
   * @param json the backend's answer
   * @return the result
   */
  private static ExchangeRelay.Result ok(String json) {
    JsonNode node = MAPPER.readTree(json);
    return new ExchangeRelay.Result(200, node, null, null);
  }
}
