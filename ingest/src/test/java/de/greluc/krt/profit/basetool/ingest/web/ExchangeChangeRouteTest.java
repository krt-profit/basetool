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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * The change routes through both gates, with the backend relay, the idempotency store and the
 * budget mocked (REQ-XCH-015…-017, REQ-XCH-021, REQ-XCH-023).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ExchangeChangeRouteTest {

  private static final String TOKEN = "change-token";
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final Path EXAMPLES = Path.of("../docs/exchange/examples/v1");
  private static final String ADD =
      "{\"ops\":[{\"op\":\"add\",\"ref\":{\"name\":\"Arrowhead\"},\"colour\":\"red\"}]}";
  private static final String RESULT =
      "{\"dryRun\":false,\"applied\":1,\"unchanged\":0,\"notApplied\":0,\"results\":[]}";

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
    String scopes =
        "exchange.connect exchange.blueprints.write exchange.stock.write exchange.hangar.write";
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
  void aBlueprintChangeIsRelayedAndItsUnknownFieldsReported() throws Exception {
    when(relay.forward(
            eq(HttpMethod.POST), eq("/api/v1/exchange/me/blueprints/changes"), any(), any(), any()))
        .thenReturn(ok(RESULT));

    post("/exchange/v1/me/blueprints/changes", ADD)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(1))
        .andExpect(jsonPath("$.warnings[0].pointer").value("/ops/0/colour"))
        .andExpect(jsonPath("$.warnings[0].code").value("UNKNOWN_FIELD"));
  }

  @Test
  void theStockAndShipExamplesReachTheirBackendRoutes() throws Exception {
    when(relay.forward(eq(HttpMethod.POST), anyString(), any(), any(), any()))
        .thenReturn(ok(RESULT));

    post("/exchange/v1/me/stock/changes", example("change-set--stockChangeSet/valid/set.json"))
        .andExpect(status().isOk());
    post("/exchange/v1/me/ships/changes", example("change-set--shipChangeSet/valid/remove.json"))
        .andExpect(status().isOk());

    verify(relay)
        .forward(eq(HttpMethod.POST), eq("/api/v1/exchange/me/stock/changes"), any(), any(), any());
    verify(relay)
        .forward(eq(HttpMethod.POST), eq("/api/v1/exchange/me/ships/changes"), any(), any(), any());
  }

  @Test
  void moreThan500OpsAreTooLargeAndNeverRelayed() throws Exception {
    String op = "{\"op\":\"remove\",\"key\":\"x\"},";
    String body = "{\"ops\":[" + op.repeat(500) + "{\"op\":\"remove\",\"key\":\"y\"}]}";

    post("/exchange/v1/me/blueprints/changes", body)
        .andExpect(status().isContentTooLarge())
        .andExpect(jsonPath("$.code").value("BATCH_TOO_LARGE"));

    verify(relay, never()).forward(any(), anyString(), any(), any(), any());
  }

  @Test
  void aChangeSetOfMoreThan100OpsIsRelayedWithinTheLargeSetLimit() throws Exception {
    when(relay.forwardLarge(any(), anyString(), any(), any(), any())).thenReturn(ok(RESULT));

    post("/exchange/v1/me/blueprints/changes", removals(101)).andExpect(status().isOk());

    verify(relay)
        .forwardLarge(
            eq(HttpMethod.POST), eq("/api/v1/exchange/me/blueprints/changes"), any(), any(), any());
    verify(relay, never()).forward(any(), anyString(), any(), any(), any());
  }

  @Test
  void aChangeSetOf100OpsNeedsNoLargeSetSlot() throws Exception {
    when(relay.forward(any(), anyString(), any(), any(), any())).thenReturn(ok(RESULT));

    post("/exchange/v1/me/blueprints/changes", removals(100)).andExpect(status().isOk());

    verify(relay, never()).forwardLarge(any(), anyString(), any(), any(), any());
  }

  @Test
  void aLargeChangeSetWithoutAFreeSlotIsRelayBusyWithRetryAfterAndNotCached() throws Exception {
    when(relay.forwardLarge(any(), anyString(), any(), any(), any()))
        .thenReturn(ExchangeRelay.Result.busy());

    post("/exchange/v1/me/blueprints/changes", removals(101))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Retry-After", "10"))
        .andExpect(jsonPath("$.code").value("RELAY_BUSY"));

    verify(idempotency, never()).store(anyString(), any());
    verify(quotas).refundCounted(any());
  }

  @Test
  void aRelayedChangeSetKeepsItsQuotaCount() throws Exception {
    when(relay.forwardLarge(any(), anyString(), any(), any(), any())).thenReturn(ok(RESULT));

    post("/exchange/v1/me/blueprints/changes", removals(101)).andExpect(status().isOk());

    verify(quotas, never()).refundCounted(any());
  }

  @Test
  void anUnknownFieldTooLongToReportIsRefusedBeforeTheRelay() throws Exception {
    when(relay.forward(any(), anyString(), any(), any(), any())).thenReturn(ok(RESULT));
    String name = "x".repeat(250);
    String body =
        "{\"ops\":[{\"op\":\"add\",\"ref\":{\"name\":\"Arrowhead\"},\"" + name + "\":1}]}";

    post("/exchange/v1/me/blueprints/changes", body)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("SCHEMA_INVALID"))
        .andExpect(jsonPath("$.errors[0].pointer").value("/ops/0"));

    verify(relay, never()).forward(any(), anyString(), any(), any(), any());
  }

  @Test
  void anUnknownFieldAtTheLongestReportablePointerIsStillAWarning() throws Exception {
    when(relay.forward(any(), anyString(), any(), any(), any())).thenReturn(ok(RESULT));
    String name = "y".repeat(200 - "/ops/0/".length());

    post(
            "/exchange/v1/me/blueprints/changes",
            "{\"ops\":[{\"op\":\"add\",\"ref\":{\"name\":\"Arrowhead\"},\"" + name + "\":1}]}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.warnings[0].pointer").value("/ops/0/" + name));
  }

  @Test
  void aBodyThatIsNoJsonIsSchemaInvalidAndNeverCached() throws Exception {
    for (String body : new String[] {"{\"ops\":[", "not json", ""}) {
      post("/exchange/v1/me/blueprints/changes", body)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("SCHEMA_INVALID"))
          .andExpect(jsonPath("$.errors[0].pointer").value(""))
          .andExpect(header().doesNotExist("Idempotency-Replayed"));
    }

    verify(relay, never()).forward(any(), anyString(), any(), any(), any());
    verify(idempotency, never()).store(anyString(), any());
    verify(budget, never())
        .settle(anyString(), anyString(), anyString(), anyLong(), anyString(), anyLong(), any());
    verify(budget, times(3))
        .release(eq(ExchangeTestSupport.CLIENT), eq(member), anyString(), anyLong());
  }

  @Test
  void aBodyOfAnotherMediaTypeIsRefusedWithARegisteredCodeAndNeverCached() throws Exception {
    ExchangeTestSupport.call(
            mockMvc,
            key,
            TOKEN,
            HttpMethod.POST,
            "/exchange/v1/me/stock/changes",
            null,
            "VerseKit/2.1.0")
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));

    verify(relay, never()).forward(any(), anyString(), any(), any(), any());
    verify(idempotency, never()).store(anyString(), any());
  }

  @Test
  void anOpOutsideTheSchemaIsRefusedBeforeTheRelay() throws Exception {
    post("/exchange/v1/me/blueprints/changes", "{\"ops\":[{\"op\":\"rename\"}]}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("SCHEMA_INVALID"));

    verify(relay, never()).forward(any(), anyString(), any(), any(), any());
  }

  @Test
  void aHeldBackMassChangeIsStagedForTheMembersConfirmation() throws Exception {
    when(relay.forward(any(), anyString(), any(), any(), any()))
        .thenReturn(
            new ExchangeRelay.Result(
                409, null, "MASS_CHANGE_CONFIRMATION_REQUIRED", "40 removals in 24 hours."));
    when(stagingService.stageMassChange(eq("versekit"), eq(member), anyString(), anyLong()))
        .thenReturn(new HandoffStagingService.Staged("hid-1", "ingest:handoff:x:hid-1", 321L));
    when(stagingService.stagedBytes(eq(HandoffKind.MASS_CHANGE), anyString())).thenReturn(321L);
    double before = staged();
    Instant start = Instant.now();

    post("/exchange/v1/me/blueprints/changes", ADD)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("MASS_CHANGE_CONFIRMATION_REQUIRED"))
        .andExpect(jsonPath("$.detail").value("40 removals in 24 hours."))
        .andExpect(
            jsonPath("$.confirmationUrl")
                .value("http://localhost:18081/connected-apps/confirm?handoff=hid-1"));

    ArgumentCaptor<String> staged = ArgumentCaptor.forClass(String.class);
    verify(stagingService).stageMassChange(eq("versekit"), eq(member), staged.capture(), anyLong());
    assertThat(MAPPER.readTree(staged.getValue()).get("clientId").stringValue())
        .isEqualTo("versekit");
    assertThat(MAPPER.readTree(staged.getValue()).get("resource").stringValue())
        .isEqualTo("blueprints");
    assertThat(MAPPER.readTree(staged.getValue()).at("/changeSet/ops/0/op").stringValue())
        .isEqualTo("add");
    assertThat(Instant.parse(MAPPER.readTree(staged.getValue()).get("stagedAt").stringValue()))
        .isBetween(start, Instant.now());
    verify(budget)
        .reserve(
            eq("versekit"), eq(member), startsWith(ExchangeBudget.PENDING_PREFIX), eq(321L), any());
    verify(budget)
        .settle(
            eq("versekit"),
            eq(member),
            startsWith(ExchangeBudget.PENDING_PREFIX),
            eq(321L),
            eq("ingest:handoff:x:hid-1"),
            eq(321L),
            any());
    assertThat(staged() - before).isEqualTo(1.0);
  }

  @Test
  void aMassChangeWhoseStagedFormIsTooLargeIsRefusedWith413AndNotCached() throws Exception {
    when(relay.forward(any(), anyString(), any(), any(), any()))
        .thenReturn(new ExchangeRelay.Result(409, null, "MASS_CHANGE_CONFIRMATION_REQUIRED", ""));
    when(stagingService.stagedBytes(eq(HandoffKind.MASS_CHANGE), anyString()))
        .thenReturn(100_000_000L);

    post("/exchange/v1/me/blueprints/changes", ADD)
        .andExpect(status().isContentTooLarge())
        .andExpect(jsonPath("$.code").value("BATCH_TOO_LARGE"));

    verify(stagingService, never())
        .stageMassChange(anyString(), anyString(), anyString(), anyLong());
    verify(idempotency, never()).store(anyString(), any());
  }

  @Test
  void aFullBudgetRefusesTheStagingWithRetryAfter() throws Exception {
    when(relay.forward(any(), anyString(), any(), any(), any()))
        .thenReturn(new ExchangeRelay.Result(409, null, "MASS_CHANGE_CONFIRMATION_REQUIRED", ""));
    when(budget.reserve(anyString(), anyString(), anyString(), anyLong(), any()))
        .thenReturn(true, false);
    when(budget.retryAfterSeconds(anyString(), anyString(), anyLong())).thenReturn(777L);

    post("/exchange/v1/me/blueprints/changes", ADD)
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Retry-After", "777"))
        .andExpect(jsonPath("$.code").value("EXCHANGE_BUDGET_EXHAUSTED"));

    verify(stagingService, never())
        .stageMassChange(anyString(), anyString(), anyString(), anyLong());
    verify(quotas).refundCounted(any());
  }

  @Test
  void eachChangeRouteNeedsItsWriteCapability() throws Exception {
    grant(Set.of("exchange.connect", "exchange.blueprints.write"));

    post("/exchange/v1/me/ships/changes", example("change-set--shipChangeSet/valid/remove.json"))
        .andExpect(status().isForbidden());

    verify(relay, never()).forward(any(), anyString(), any(), any(), any());
  }

  /**
   * Reads the staged mass-change counter of the test client.
   *
   * @return its count
   */
  private double staged() {
    return meterRegistry
        .counter(MetricNames.EXCHANGE_MASS_CHANGES_STAGED, MetricNames.TAG_CLIENT_ID, "versekit")
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
   * Posts a change set with a fresh DPoP proof and an idempotency key.
   *
   * @param path the route
   * @param json the change set
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
   * Builds a blueprint change set of removals.
   *
   * @param count the number of ops
   * @return the body
   */
  private static @NotNull String removals(int count) {
    StringBuilder body = new StringBuilder("{\"ops\":[");
    for (int i = 0; i < count; i++) {
      body.append(i == 0 ? "" : ",")
          .append("{\"op\":\"remove\",\"key\":\"k")
          .append(i)
          .append("\"}");
    }
    return body.append("]}").toString();
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
