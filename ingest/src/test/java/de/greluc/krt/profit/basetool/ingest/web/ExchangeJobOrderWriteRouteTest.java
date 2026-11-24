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

import static de.greluc.krt.profit.basetool.ingest.support.ExchangeTestSupport.example;
import static de.greluc.krt.profit.basetool.ingest.support.ExchangeTestSupport.ok;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The create options, the create, the cancel, the target change and the comment change of an aUEC
 * order through both gates, with the backend relay mocked (REQ-OXCH-004 to REQ-OXCH-009): the
 * gateway checks the order id and the body before the relay, relays the client's key on the create
 * only, answers a created order with {@code 201}, passes the pair refusal, both cap refusals and
 * the refusals of a write on an existing order on, the daily cap with the wait until the next UTC
 * day, checks the create options' limits and the target answer, and needs {@code
 * exchange.joborders.write}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ExchangeJobOrderWriteRouteTest {

  private static final String TOKEN = "job-order-write-token";
  private static final String BACKEND = "/api/v1/exchange/job-orders";
  private static final String ROUTE = "/exchange/v1/job-orders";
  private static final Set<String> SCOPES = Set.of("exchange.connect", "exchange.joborders.write");
  private static final String ORDER = "7b1e2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d";
  private static final String CANCEL = "job-order-write--cancel/valid/cancel.json";
  private static final String COMMENT = "job-order-write--comment/valid/comment.json";
  private static final String TARGET = "job-order-write--targetChange/valid/raise.json";

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
  void theOpenOrderCapReachesTheClientWithItsCodeAndNoWait() throws Exception {
    when(relay.forward(eq(HttpMethod.POST), eq(BACKEND), any(), any(), any(), anyString()))
        .thenReturn(new ExchangeRelay.Result(409, null, "JOB_ORDER_OPEN_ORDER_CAP", "Capped."));

    call(HttpMethod.POST, ROUTE, example("job-order-write--create/valid/with-comment.json"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("JOB_ORDER_OPEN_ORDER_CAP"))
        .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER));
  }

  @Test
  void theDailyCreateCapWaitsUntilTheNextUtcDay() throws Exception {
    when(quotas.secondsUntilTomorrow()).thenReturn(4321L);
    when(relay.forward(eq(HttpMethod.POST), eq(BACKEND), any(), any(), any(), anyString()))
        .thenReturn(new ExchangeRelay.Result(409, null, "JOB_ORDER_DAILY_CREATE_CAP", "Capped."));

    call(HttpMethod.POST, ROUTE, example("job-order-write--create/valid/with-comment.json"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("JOB_ORDER_DAILY_CREATE_CAP"))
        .andExpect(header().string(HttpHeaders.RETRY_AFTER, "4321"));
  }

  @Test
  void theCreateOptionsAreRelayedWithoutAKeyAndCheckedAgainstTheirSchema() throws Exception {
    when(relay.forward(eq(HttpMethod.GET), eq(BACKEND + "/create-options"), isNull(), any(), any()))
        .thenReturn(ok(example("job-order-create-options/valid/logistician.json")));

    call(HttpMethod.GET, ROUTE + "/create-options", null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requestingUnits[0].shorthand").value("ALP"))
        .andExpect(jsonPath("$.writesEnabled").value(true))
        .andExpect(jsonPath("$.limits.openOrderCap").value(20))
        .andExpect(jsonPath("$.limits.createdToday").value(1));
  }

  @Test
  void createOptionsWithoutTheirLimitsAreARelayFailure() throws Exception {
    when(relay.forward(any(), anyString(), any(), any(), any()))
        .thenReturn(ok(example("job-order-create-options/invalid/without-limits.json")));

    call(HttpMethod.GET, ROUTE + "/create-options", null)
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value(ExchangeRelay.RELAY_FAILED));
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

  @Test
  void aCancelIsRelayedToTheOrderWithoutTheKeyAndAnsweredWithTheWrittenOrder() throws Exception {
    when(relay.forward(
            eq(HttpMethod.POST), eq(BACKEND + "/" + ORDER + "/cancel"), any(), any(), any()))
        .thenReturn(ok(example("job-order-write--written/valid/cancelled.json")));

    call(HttpMethod.POST, cancelRoute(ORDER.toUpperCase(Locale.ROOT)), example(CANCEL))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"))
        .andExpect(jsonPath("$.versions.lifecycle").value(1));

    verify(relay, never()).forward(any(), anyString(), any(), any(), any(), anyString());
  }

  @Test
  void aCancelOfAMalformedIdIsRefusedBeforeTheRelay() throws Exception {
    call(HttpMethod.POST, cancelRoute("not-an-order"), example(CANCEL))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("SCHEMA_INVALID"))
        .andExpect(jsonPath("$.errors[0].pointer").value("/id"));

    verify(relay, never()).forward(any(), anyString(), any(), any(), any());
  }

  @Test
  void aCancelBodyOutsideTheSchemaIsRefusedBeforeTheRelay() throws Exception {
    call(
            HttpMethod.POST,
            cancelRoute(ORDER),
            example("job-order-write--cancel/invalid/negative-version.json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("SCHEMA_INVALID"));
    call(
            HttpMethod.POST,
            cancelRoute(ORDER),
            example("job-order-write--cancel/invalid/target-version.json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("SCHEMA_INVALID"));

    verify(relay, never()).forward(any(), anyString(), any(), any(), any());
  }

  @Test
  void theRefusalsOfAWriteOnAnExistingOrderReachTheClientWithTheirCodes() throws Exception {
    assertCancelRefused(404, "NOT_FOUND");
    assertCancelRefused(403, "NOT_PERMITTED");
    assertCancelRefused(409, "VERSION_CONFLICT");
    assertCancelRefused(409, "JOB_ORDER_RESPONSIBILITY_INVALID");
    assertCancelRefused(409, "JOB_ORDER_CLOSED");
    assertCancelRefused(409, "JOB_ORDER_MONEY_STARTED");
  }

  @Test
  void theReadScopeAloneDoesNotOpenTheCancel() throws Exception {
    Set<String> read = Set.of("exchange.connect", "exchange.joborders.read");
    when(registryReader.current())
        .thenReturn(ExchangeTestSupport.registryWithEveryArea(read, "2.0.0", 120));

    call(HttpMethod.POST, cancelRoute(ORDER), example(CANCEL))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SCOPE_MISSING"));
  }

  @Test
  void aCommentIsRelayedToTheOrderWithoutTheKeyAndAnsweredWithTheWrittenOrder() throws Exception {
    when(relay.forward(
            eq(HttpMethod.POST), eq(BACKEND + "/" + ORDER + "/comment"), any(), any(), any()))
        .thenReturn(ok(example("job-order-write--written/valid/commented.json")));

    call(HttpMethod.POST, commentRoute(ORDER.toUpperCase(Locale.ROOT)), example(COMMENT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("OPEN"))
        .andExpect(jsonPath("$.versions.comment").value(1));
    call(HttpMethod.POST, commentRoute(ORDER), example("job-order-write--comment/valid/clear.json"))
        .andExpect(status().isOk());

    verify(relay, never()).forward(any(), anyString(), any(), any(), any(), anyString());
  }

  @Test
  void aCommentOutsideTheSchemaOrOnAMalformedIdIsRefusedBeforeTheRelay() throws Exception {
    for (String invalid :
        List.of("without-comment.json", "lifecycle-version.json", "negative-version.json")) {
      call(
              HttpMethod.POST,
              commentRoute(ORDER),
              example("job-order-write--comment/invalid/" + invalid))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("SCHEMA_INVALID"));
    }
    call(
            HttpMethod.POST,
            commentRoute(ORDER),
            "{\"comment\":\"" + "x".repeat(1001) + "\",\"commentVersion\":0}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("SCHEMA_INVALID"));
    call(HttpMethod.POST, commentRoute("not-an-order"), example(COMMENT))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].pointer").value("/id"));

    verify(relay, never()).forward(any(), anyString(), any(), any(), any());
  }

  @Test
  void theRefusalsOfACommentReachTheClientWithTheirCodes() throws Exception {
    for (String code : List.of("VERSION_CONFLICT", "JOB_ORDER_CLOSED")) {
      String detail = "The fixed detail of " + code + ".";
      when(relay.forward(
              eq(HttpMethod.POST), eq(BACKEND + "/" + ORDER + "/comment"), any(), any(), any()))
          .thenReturn(new ExchangeRelay.Result(409, null, code, detail));

      call(HttpMethod.POST, commentRoute(ORDER), example(COMMENT))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value(code))
          .andExpect(jsonPath("$.detail").value(detail));
    }
  }

  @Test
  void theReadScopeAloneDoesNotOpenTheComment() throws Exception {
    Set<String> read = Set.of("exchange.connect", "exchange.joborders.read");
    when(registryReader.current())
        .thenReturn(ExchangeTestSupport.registryWithEveryArea(read, "2.0.0", 120));

    call(HttpMethod.POST, commentRoute(ORDER), example(COMMENT))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SCOPE_MISSING"));
  }

  /**
   * The gateway route of an order's comment change.
   *
   * @param id the order id as the path carries it
   * @return the route
   */
  private static @NotNull String commentRoute(@NotNull String id) {
    return ROUTE + "/" + id + "/comment";
  }

  @Test
  void aTargetChangeIsRelayedToTheOrderWithoutTheKeyAndAnsweredWithItsTarget() throws Exception {
    when(relay.forward(
            eq(HttpMethod.POST), eq(BACKEND + "/" + ORDER + "/target"), any(), any(), any()))
        .thenReturn(ok(example("job-order-write--targetChanged/valid/raised.json")));

    call(HttpMethod.POST, targetRoute(ORDER.toUpperCase(Locale.ROOT)), example(TARGET))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("OPEN"))
        .andExpect(jsonPath("$.target").value(400000))
        .andExpect(jsonPath("$.versions.target").value(1));

    verify(relay, never()).forward(any(), anyString(), any(), any(), any(), anyString());
  }

  @Test
  void aTargetChangeOutsideTheSchemaIsRefusedBeforeTheRelay() throws Exception {
    call(HttpMethod.POST, targetRoute("not-an-order"), example(TARGET))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("SCHEMA_INVALID"))
        .andExpect(jsonPath("$.errors[0].pointer").value("/id"));
    call(
            HttpMethod.POST,
            targetRoute(ORDER),
            example("job-order-write--targetChange/invalid/zero-target.json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("SCHEMA_INVALID"));
    call(
            HttpMethod.POST,
            targetRoute(ORDER),
            example("job-order-write--targetChange/invalid/lifecycle-version.json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("SCHEMA_INVALID"));

    verify(relay, never()).forward(any(), anyString(), any(), any(), any());
  }

  @Test
  void aTargetTooLowReachesTheClientWithItsCodeAndDetail() throws Exception {
    String detail = "The fixed detail of JOB_ORDER_TARGET_TOO_LOW.";
    when(relay.forward(
            eq(HttpMethod.POST), eq(BACKEND + "/" + ORDER + "/target"), any(), any(), any()))
        .thenReturn(new ExchangeRelay.Result(409, null, "JOB_ORDER_TARGET_TOO_LOW", detail));

    call(HttpMethod.POST, targetRoute(ORDER), example(TARGET))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("JOB_ORDER_TARGET_TOO_LOW"))
        .andExpect(jsonPath("$.detail").value(detail));
  }

  @Test
  void aTargetAnswerWithoutItsTargetIsARelayFailure() throws Exception {
    when(relay.forward(any(), anyString(), any(), any(), any()))
        .thenReturn(ok(example("job-order-write--targetChanged/invalid/without-target.json")));

    call(HttpMethod.POST, targetRoute(ORDER), example(TARGET))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value(ExchangeRelay.RELAY_FAILED));
  }

  @Test
  void theReadScopeAloneDoesNotOpenTheTargetChange() throws Exception {
    Set<String> read = Set.of("exchange.connect", "exchange.joborders.read");
    when(registryReader.current())
        .thenReturn(ExchangeTestSupport.registryWithEveryArea(read, "2.0.0", 120));

    call(HttpMethod.POST, targetRoute(ORDER), example(TARGET))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SCOPE_MISSING"));
  }

  /**
   * The gateway route of an order's target change.
   *
   * @param id the order id as the path carries it
   * @return the route
   */
  private static @NotNull String targetRoute(@NotNull String id) {
    return ROUTE + "/" + id + "/target";
  }

  /**
   * Answers the relayed cancel with a refusal as the relay passes it on and checks that it reaches
   * the client with its status, code and fixed detail.
   *
   * @param status the refusal's status
   * @param code the refusal's registry code
   * @throws Exception if the request fails
   */
  private void assertCancelRefused(int status, @NotNull String code) throws Exception {
    String detail = "The fixed detail of " + code + ".";
    when(relay.forward(
            eq(HttpMethod.POST), eq(BACKEND + "/" + ORDER + "/cancel"), any(), any(), any()))
        .thenReturn(new ExchangeRelay.Result(status, null, code, detail));

    call(HttpMethod.POST, cancelRoute(ORDER), example(CANCEL))
        .andExpect(status().is(status))
        .andExpect(jsonPath("$.code").value(code))
        .andExpect(jsonPath("$.detail").value(detail));
  }

  /**
   * The gateway route of an order's cancel.
   *
   * @param id the order id as the path carries it
   * @return the route
   */
  private static @NotNull String cancelRoute(@NotNull String id) {
    return ROUTE + "/" + id + "/cancel";
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
}
