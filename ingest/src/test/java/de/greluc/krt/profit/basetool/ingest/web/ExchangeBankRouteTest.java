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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
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
 * The bank read routes through both gates, with the backend relay mocked (REQ-BXCH-002): the
 * gateway checks the account id and the bounds before it relays, relays the bounds as UTC instants,
 * passes the backend's shared {@code 404 NOT_FOUND} on and checks every answer against its schema.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ExchangeBankRouteTest {

  private static final String TOKEN = "bank-token";
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final Path EXAMPLES = Path.of("../docs/exchange/examples/v1");
  private static final String ACCOUNT = "5d0e9c1a-2b3f-4a6d-8e71-9c0b1a2d3e4f";
  private static final String BACKEND = "/api/v1/exchange/bank/accounts";

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
                "exchange.connect exchange.bank.read exchange.bank.queue",
                Instant.now().minusSeconds(30)));
    when(registryReader.current())
        .thenReturn(
            ExchangeTestSupport.registryWithLimits(
                Set.of("exchange.connect", "exchange.bank.read", "exchange.bank.queue"),
                "2.0.0",
                120,
                true));
    when(revocationReader.isDenied(anyString())).thenReturn(false);
  }

  @Test
  void theAccountsAreRelayedAndCheckedAgainstTheirSchema() throws Exception {
    when(relay.forward(eq(HttpMethod.GET), eq(BACKEND + "?limit=2"), isNull(), any(), any()))
        .thenReturn(ok(example("page--bankAccountPage/valid/snapshot.json")));

    call("/exchange/v1/bank/accounts?limit=2")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].requestable").value(true))
        .andExpect(jsonPath("$.items[0].ownLimit").value(1000));
  }

  @Test
  void anAccountOutsideTheContractIsARelayFailure() throws Exception {
    when(relay.forward(any(), anyString(), any(), any(), any()))
        .thenReturn(ok(example("page--bankAccountPage/invalid/account-without-type.json")));

    call("/exchange/v1/bank/accounts")
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value(ExchangeRelay.RELAY_FAILED));
  }

  @Test
  void theBookingBoundsAreRelayedAsUtcInstants() throws Exception {
    String target =
        BACKEND
            + "/"
            + ACCOUNT
            + "/bookings?from=2026-11-01T01:00:00Z&to=2026-11-08T12:00:00Z&limit=2";
    when(relay.forward(eq(HttpMethod.GET), eq(target), isNull(), any(), any()))
        .thenReturn(ok(example("page--bankBookingPage/valid/first-page.json")));

    call("/exchange/v1/bank/accounts/"
            + ACCOUNT.toUpperCase(Locale.ROOT)
            + "/bookings?from=2026-11-01T00:00:00-01:00&to=2026-11-08T12:00:00Z&limit=2")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].type").value("DEPOSIT"))
        .andExpect(jsonPath("$.hasMore").value(true));
  }

  @Test
  void theBackendsSharedNotFoundIsPassedOn() throws Exception {
    when(relay.forward(any(), anyString(), any(), any(), any()))
        .thenReturn(
            new ExchangeRelay.Result(404, null, "NOT_FOUND", "No such resource, or hidden."));

    call("/exchange/v1/bank/accounts/" + ACCOUNT + "/bookings")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void aMalformedAccountIdOrBoundIsRefusedBeforeTheRelay() throws Exception {
    call("/exchange/v1/bank/accounts/KB-0042/bookings")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("SCHEMA_INVALID"))
        .andExpect(jsonPath("$.errors[0].pointer").value("/id"));
    call("/exchange/v1/bank/accounts/" + ACCOUNT + "/bookings?from=yesterday")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].pointer").value("/from"));

    verify(relay, never()).forward(any(), anyString(), any(), any(), any());
  }

  @Test
  void aBoundOutsideTheRfc3339YearsIsRefusedBeforeTheRelay() throws Exception {
    call("/exchange/v1/bank/accounts/" + ACCOUNT + "/bookings?to=9999-12-31T23:00:00-05:00")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("SCHEMA_INVALID"))
        .andExpect(jsonPath("$.errors[0].pointer").value("/to"));
    call("/exchange/v1/bank/accounts/" + ACCOUNT + "/bookings?from=-0001-01-01T00:00:00Z")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].pointer").value("/from"));

    verify(relay, never()).forward(any(), anyString(), any(), any(), any());
  }

  @Test
  void theBalanceSeriesIsRelayedWithBothBounds() throws Exception {
    String target =
        BACKEND
            + "/"
            + ACCOUNT
            + "/balance-series?from=2026-11-06T00:00:00Z&to=2026-11-08T23:59:59Z";
    when(relay.forward(eq(HttpMethod.GET), eq(target), isNull(), any(), any()))
        .thenReturn(ok(example("bank-balance-series/valid/three-days.json")));

    call("/exchange/v1/bank/accounts/"
            + ACCOUNT
            + "/balance-series?from=2026-11-06T00:00:00Z&to=2026-11-08T23:59:59Z")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.points.length()").value(3))
        .andExpect(jsonPath("$.points[2].date").value("2026-11-08"));
  }

  @Test
  void theBalanceSeriesNeedsBothBoundsWithinAYear() throws Exception {
    String series = "/exchange/v1/bank/accounts/" + ACCOUNT + "/balance-series";
    call(series + "?from=2026-11-06T00:00:00Z")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].pointer").value("/to"));
    call(series + "?from=2025-11-06T00:00:00Z&to=2026-11-08T00:00:00Z")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].pointer").value("/to"));
    call(series + "?from=2026-11-08T00:00:00Z&to=2026-11-06T00:00:00Z")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].pointer").value("/from"));

    verify(relay, never()).forward(any(), anyString(), any(), any(), any());
  }

  @Test
  void theStaffQueueIsRelayedAndCheckedAgainstItsSchema() throws Exception {
    when(relay.forward(
            eq(HttpMethod.GET),
            eq("/api/v1/exchange/bank/queue?limit=500"),
            isNull(),
            any(),
            any()))
        .thenReturn(ok(example("bank-queue/valid/staff-and-approver.json")));

    call("/exchange/v1/bank/queue?limit=500")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.open[0].mayConfirm").value(true))
        .andExpect(jsonPath("$.toApprove[0].requiredApprover").value("BANK_MANAGEMENT"));
  }

  @Test
  void aStaffQueuePageAboveFiveHundredIsRefusedBeforeTheRelay() throws Exception {
    call("/exchange/v1/bank/queue?limit=501")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("SCHEMA_INVALID"))
        .andExpect(jsonPath("$.errors[0].pointer").value("/limit"));

    verify(relay, never()).forward(any(), anyString(), any(), any(), any());
  }

  @Test
  void aStaffQueueItemLinkingOutsideTheBasetoolIsARelayFailure() throws Exception {
    when(relay.forward(any(), anyString(), any(), any(), any()))
        .thenReturn(ok(example("bank-queue/invalid/foreign-link.json")));

    call("/exchange/v1/bank/queue")
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value(ExchangeRelay.RELAY_FAILED));
  }

  @Test
  void aTokenWithoutTheBankScopeIsRefused() throws Exception {
    when(registryReader.current())
        .thenReturn(
            ExchangeTestSupport.registryWithLimits(Set.of("exchange.connect"), "2.0.0", 120, true));

    call("/exchange/v1/bank/accounts")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SCOPE_MISSING"));
  }

  /**
   * Calls a route with a fresh DPoP proof.
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
