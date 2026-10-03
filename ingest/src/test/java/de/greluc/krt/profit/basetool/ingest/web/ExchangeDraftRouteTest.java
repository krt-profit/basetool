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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.ECKey;
import de.greluc.krt.profit.basetool.ingest.handoff.HandoffKind;
import de.greluc.krt.profit.basetool.ingest.handoff.HandoffStagingService;
import de.greluc.krt.profit.basetool.ingest.observability.MetricNames;
import de.greluc.krt.profit.basetool.ingest.registry.ExchangeRegistryReader;
import de.greluc.krt.profit.basetool.ingest.registry.ExchangeRevocationReader;
import de.greluc.krt.profit.basetool.ingest.registry.ExchangeUnavailableException;
import de.greluc.krt.profit.basetool.ingest.relay.ExchangeRelay;
import de.greluc.krt.profit.basetool.ingest.store.ExchangeBudget;
import de.greluc.krt.profit.basetool.ingest.store.ExchangeIdempotency;
import de.greluc.krt.profit.basetool.ingest.store.ExchangeQuotas;
import de.greluc.krt.profit.basetool.ingest.support.ExchangeTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
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
 * Web tests for the exchange drafts: checked, relayed to the backend's preview, staged for the
 * member's review like the extractor's upload, answered with the handoff (REQ-XCH-019).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ExchangeDraftRouteTest {

  private static final String TOKEN = "draft-token";
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final Path EXAMPLES = Path.of("../docs/exchange/examples/v1");
  private static final String PREVIEW =
      "{\"total\":1,\"matched\":1,\"matchedByAlias\":0,\"suggested\":0,\"unmatched\":0,"
          + "\"alreadyOwned\":0,\"entries\":[]}";

  @Autowired private WebApplicationContext context;
  @Autowired private MeterRegistry meterRegistry;

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
  private String member;

  @BeforeEach
  void setUp() throws Exception {
    when(quotas.countWrite(anyString(), anyString()))
        .thenReturn(new ExchangeQuotas.Counted("ingest:xch:quota:test", 1L));
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    key = ExchangeTestSupport.newKey();
    member = UUID.randomUUID().toString();
    String scopes = "exchange.connect exchange.drafts.blueprints exchange.drafts.refinery";
    when(jwtDecoder.decode(TOKEN))
        .thenReturn(
            ExchangeTestSupport.token(
                TOKEN,
                "basetool-ingest",
                ExchangeTestSupport.thumbprint(key),
                member,
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
  void aBlueprintDraftIsStagedForReviewAndAnsweredWithItsHandoff() throws Exception {
    when(relay.forward(
            eq(HttpMethod.POST), eq("/api/v1/exchange/me/drafts/blueprints"), any(), any(), any()))
        .thenReturn(ok(PREVIEW));
    when(stagingService.stageDraft(
            eq("versekit"), eq(member), eq(HandoffKind.BLUEPRINT), anyString(), eq(10)))
        .thenReturn(new HandoffStagingService.Staged("hid-b", "ingest:handoff:x:hid-b", 222L));
    when(stagingService.stagedBytes(eq(HandoffKind.BLUEPRINT), anyString())).thenReturn(222L);
    double before = handoffs(HandoffKind.BLUEPRINT);

    post("/exchange/v1/me/drafts/blueprints", example("blueprint-draft/valid/corpus-slice.json"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").value("BLUEPRINT"))
        .andExpect(jsonPath("$.handoffId").value("hid-b"))
        .andExpect(
            jsonPath("$.frontendUrl")
                .value("http://localhost:18081/personal-inventory/blueprints?handoff=hid-b"));

    ArgumentCaptor<String> staged = ArgumentCaptor.forClass(String.class);
    verify(stagingService)
        .stageDraft(
            eq("versekit"), eq(member), eq(HandoffKind.BLUEPRINT), staged.capture(), eq(10));
    assertThat(MAPPER.readTree(staged.getValue()).get("matched").intValue()).isEqualTo(1);
    verify(budget)
        .reserve(
            eq("versekit"), eq(member), startsWith(ExchangeBudget.PENDING_PREFIX), eq(222L), any());
    verify(budget)
        .settle(
            eq("versekit"),
            eq(member),
            startsWith(ExchangeBudget.PENDING_PREFIX),
            eq(222L),
            eq("ingest:handoff:x:hid-b"),
            eq(222L),
            any());
    assertThat(handoffs(HandoffKind.BLUEPRINT) - before).isEqualTo(1.0);
  }

  @Test
  void aDraftThatCannotBeStagedFreesItsReservation() throws Exception {
    when(relay.forward(any(), anyString(), any(), any(), any())).thenReturn(ok(PREVIEW));
    when(stagingService.stagedBytes(eq(HandoffKind.BLUEPRINT), anyString())).thenReturn(222L);
    when(stagingService.stageDraft(
            eq("versekit"), eq(member), eq(HandoffKind.BLUEPRINT), anyString(), eq(10)))
        .thenThrow(new RedisSystemException("down", null));

    post("/exchange/v1/me/drafts/blueprints", example("blueprint-draft/valid/corpus-slice.json"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));

    verify(budget)
        .release(eq("versekit"), eq(member), startsWith(ExchangeBudget.PENDING_PREFIX), eq(222L));
    verify(budget, never())
        .settle(
            anyString(),
            anyString(),
            startsWith(ExchangeBudget.PENDING_PREFIX),
            anyLong(),
            anyString(),
            anyLong(),
            any());
  }

  @Test
  void aLostRedisConnectionWhileStagingIsARetryable503WithTheRegistryCode() throws Exception {
    when(relay.forward(any(), anyString(), any(), any(), any())).thenReturn(ok(PREVIEW));
    when(stagingService.stageDraft(
            eq("versekit"), eq(member), eq(HandoffKind.BLUEPRINT), anyString(), eq(10)))
        .thenThrow(new RedisConnectionFailureException("refused"));
    double before = stagingFailures();

    post("/exchange/v1/me/drafts/blueprints", example("blueprint-draft/valid/corpus-slice.json"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Retry-After", "60"))
        .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
        .andExpect(jsonPath("$.detail").value("The draft cannot be staged; try again later."));

    assertThat(stagingFailures() - before)
        .as("IngestStagingUnavailable reads this series")
        .isEqualTo(1.0);
  }

  /**
   * Reads {@code basetool_ingest_handoff_errors_total{reason="staging_unavailable"}}.
   *
   * @return the count so far
   */
  private double stagingFailures() {
    return meterRegistry
        .counter(
            MetricNames.INGEST_HANDOFF_ERRORS,
            MetricNames.TAG_REASON,
            MetricNames.REASON_STAGING_UNAVAILABLE)
        .count();
  }

  @Test
  void anExchangeStoreFailureEscapingARouteIsA503NotA500() throws Exception {
    when(relay.forward(any(), anyString(), any(), any(), any()))
        .thenThrow(new ExchangeUnavailableException("down", null));

    post("/exchange/v1/me/drafts/blueprints", example("blueprint-draft/valid/corpus-slice.json"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Retry-After", "60"))
        .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
  }

  @Test
  void aStagingStoreFailureOfAnyDataAccessKindIsA503() throws Exception {
    when(relay.forward(any(), anyString(), any(), any(), any())).thenReturn(ok(PREVIEW));
    when(stagingService.stageDraft(
            eq("versekit"), eq(member), eq(HandoffKind.BLUEPRINT), anyString(), eq(10)))
        .thenThrow(new QueryTimeoutException("slow"));

    post("/exchange/v1/me/drafts/blueprints", example("blueprint-draft/valid/corpus-slice.json"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
  }

  @Test
  void aRefineryDraftReachesItsBackendRouteAndOpensTheCreateForm() throws Exception {
    when(relay.forward(
            eq(HttpMethod.POST),
            eq("/api/v1/exchange/me/drafts/refinery-orders"),
            any(),
            any(),
            any()))
        .thenReturn(ok("{\"goodsTotal\":0}"));
    when(stagingService.stageDraft(
            eq("versekit"), eq(member), eq(HandoffKind.REFINERY), anyString(), eq(10)))
        .thenReturn(new HandoffStagingService.Staged("hid-r", "ingest:handoff:x:hid-r", 20L));

    post("/exchange/v1/me/drafts/refinery-orders", example("refinery-draft/valid/one-order.json"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").value("REFINERY"))
        .andExpect(
            jsonPath("$.frontendUrl")
                .value("http://localhost:18081/refinery-orders/create?handoff=hid-r"));
  }

  @Test
  void aDraftOutsideItsSchemaIsRefusedBeforeTheRelay() throws Exception {
    post(
            "/exchange/v1/me/drafts/blueprints",
            example("blueprint-draft/invalid/item-without-ref.json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("SCHEMA_INVALID"));

    verify(relay, never()).forward(any(), anyString(), any(), any(), any());
  }

  @Test
  void aRefineryDraftWithoutOrdersIsRefusedBeforeTheRelay() throws Exception {
    post("/exchange/v1/me/drafts/refinery-orders", example("refinery-draft/invalid/no-orders.json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("SCHEMA_INVALID"))
        .andExpect(jsonPath("$.errors[0].pointer").value("/orders"));

    verify(relay, never()).forward(any(), anyString(), any(), any(), any());
  }

  @Test
  void aDraftOfAnotherMajorFormatVersionIsRefusedBeforeTheRelay() throws Exception {
    String draft =
        example("blueprint-draft/valid/corpus-slice.json")
            .replace("\"formatVersion\": \"1.0\"", "\"formatVersion\": \"2.0\"");
    assertThat(draft).contains("\"2.0\"");

    post("/exchange/v1/me/drafts/blueprints", draft)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("SCHEMA_INVALID"))
        .andExpect(jsonPath("$.errors[0].pointer").value("/formatVersion"))
        .andExpect(jsonPath("$.errors[0].message").value("unsupported major version"));

    verify(relay, never()).forward(any(), anyString(), any(), any(), any());
  }

  @Test
  void aDraftOfALaterMinorFormatVersionIsRelayed() throws Exception {
    when(relay.forward(any(), anyString(), any(), any(), any())).thenReturn(ok(PREVIEW));
    when(stagingService.stageDraft(
            eq("versekit"), eq(member), eq(HandoffKind.BLUEPRINT), anyString(), eq(10)))
        .thenReturn(new HandoffStagingService.Staged("hid-m", "ingest:handoff:x:hid-m", 222L));
    when(stagingService.stagedBytes(eq(HandoffKind.BLUEPRINT), anyString())).thenReturn(222L);
    String draft =
        example("blueprint-draft/valid/corpus-slice.json")
            .replace("\"formatVersion\": \"1.0\"", "\"formatVersion\": \"1.7\"");
    assertThat(draft).contains("\"1.7\"");

    post("/exchange/v1/me/drafts/blueprints", draft)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.handoffId").value("hid-m"));
  }

  @Test
  void onlyTheMajorOneIsSupported() {
    assertThat(ExchangeController.unsupportedFormatMajor(envelope("1.0"))).isNull();
    assertThat(ExchangeController.unsupportedFormatMajor(envelope("1.12"))).isNull();
    assertThat(ExchangeController.unsupportedFormatMajor(envelope("0.9"))).isNotNull();
    assertThat(ExchangeController.unsupportedFormatMajor(envelope("10.0"))).isNotNull();
    assertThat(ExchangeController.unsupportedFormatMajor(envelope("01.0"))).isNotNull();
  }

  @Test
  void aRefusedDraftIsPassedOnAndNothingIsStaged() throws Exception {
    when(relay.forward(any(), anyString(), any(), any(), any()))
        .thenReturn(new ExchangeRelay.Result(400, null, "SCHEMA_INVALID", "Unsupported panel."));

    post("/exchange/v1/me/drafts/refinery-orders", example("refinery-draft/valid/one-order.json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("SCHEMA_INVALID"));

    verify(stagingService, never())
        .stageDraft(anyString(), anyString(), any(), anyString(), anyInt());
  }

  @Test
  void aDraftWhoseStagedFormIsTooLargeIsRefusedWith413AndNotCached() throws Exception {
    when(relay.forward(any(), anyString(), any(), any(), any())).thenReturn(ok(PREVIEW));
    when(stagingService.stagedBytes(eq(HandoffKind.BLUEPRINT), anyString()))
        .thenReturn(100_000_000L);

    post("/exchange/v1/me/drafts/blueprints", example("blueprint-draft/valid/corpus-slice.json"))
        .andExpect(status().isContentTooLarge())
        .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));

    verify(stagingService, never())
        .stageDraft(anyString(), anyString(), any(), anyString(), anyInt());
    verify(idempotency, never()).store(anyString(), any());
  }

  @Test
  void aFullBudgetRefusesTheDraftWithRetryAfter() throws Exception {
    when(relay.forward(any(), anyString(), any(), any(), any())).thenReturn(ok(PREVIEW));
    when(budget.reserve(anyString(), anyString(), anyString(), anyLong(), any()))
        .thenReturn(true, false);
    when(budget.retryAfterSeconds(anyString(), anyString(), anyLong())).thenReturn(888L);

    post("/exchange/v1/me/drafts/blueprints", example("blueprint-draft/valid/corpus-slice.json"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Retry-After", "888"))
        .andExpect(jsonPath("$.code").value("EXCHANGE_BUDGET_EXHAUSTED"));

    verify(stagingService, never())
        .stageDraft(anyString(), anyString(), any(), anyString(), anyInt());
    verify(quotas).refundCounted(any());
  }

  @Test
  void eachDraftRouteNeedsItsCapability() throws Exception {
    grant(Set.of("exchange.connect", "exchange.drafts.blueprints"));

    post("/exchange/v1/me/drafts/refinery-orders", example("refinery-draft/valid/one-order.json"))
        .andExpect(status().isForbidden());

    verify(relay, never()).forward(any(), anyString(), any(), any(), any());
  }

  /**
   * Reads the handoff counter of one kind.
   *
   * @param kind the kind
   * @return its count
   */
  private double handoffs(@NotNull HandoffKind kind) {
    return meterRegistry
        .counter(MetricNames.INGEST_HANDOFF, MetricNames.TAG_KIND, kind.name())
        .count();
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
   * Posts a draft with a fresh DPoP proof and an idempotency key.
   *
   * @param path the route
   * @param json the draft
   * @return the result
   * @throws Exception if the request fails
   */
  private ResultActions post(@NotNull String path, @NotNull String json) throws Exception {
    return ExchangeTestSupport.call(
        mockMvc, key, TOKEN, HttpMethod.POST, path, json, "VerseKit/2.1.0");
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
   * Builds an envelope that carries only a format version.
   *
   * @param formatVersion the version
   * @return the envelope
   */
  private static @NotNull JsonNode envelope(@NotNull String formatVersion) {
    return MAPPER.createObjectNode().put("formatVersion", formatVersion);
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
