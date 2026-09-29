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

package de.greluc.krt.profit.basetool.ingest.exchange;

import static de.greluc.krt.profit.basetool.ingest.exchange.ExchangeTestSupport.BLUEPRINT_CHANGES;
import static de.greluc.krt.profit.basetool.ingest.exchange.ExchangeTestSupport.STOCK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.ECKey;
import de.greluc.krt.profit.basetool.ingest.service.HandoffStagingService;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Idempotent exchange writes within the byte budget, end to end through the gates (REQ-XCH-020).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import(ExchangeTestSupport.ProbeRoutes.class)
class ExchangeIdempotencyFilterTest {

  private static final String TOKEN = "idem-token";
  private static final String KEY = "write-0000001";
  private static final String BODY = "{\"ops\":[]}";
  private static final String CLAIM_TOKEN = "claim-token";
  private static final Set<String> GRANTS =
      Set.of("exchange.connect", "exchange.stock.read", "exchange.blueprints.write");

  @Autowired private WebApplicationContext context;

  @MockitoBean private JwtDecoder jwtDecoder;
  @MockitoBean private HandoffStagingService handoffStagingService;
  @MockitoBean private ExchangeRegistryReader registryReader;
  @MockitoBean private ExchangeRevocationReader revocationReader;
  @MockitoBean private ExchangeQuotas quotas;
  @MockitoBean private ExchangeIdempotency idempotency;
  @MockitoBean private ExchangeBudget budget;

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
    when(jwtDecoder.decode(TOKEN))
        .thenReturn(
            ExchangeTestSupport.token(
                TOKEN,
                "basetool-ingest",
                ExchangeTestSupport.thumbprint(key),
                member,
                String.join(" ", GRANTS),
                Instant.now().minusSeconds(30)));
    when(registryReader.current())
        .thenReturn(ExchangeTestSupport.registry(true, true, GRANTS, null));
    when(revocationReader.isDenied(anyString())).thenReturn(false);
    when(idempotency.find(anyString())).thenReturn(Optional.empty());
    when(idempotency.claim(anyString())).thenReturn(Optional.of(CLAIM_TOKEN));
    when(idempotency.sizeOf(anyString(), any())).thenReturn(200);
    when(budget.reserve(anyString(), anyString(), anyString(), anyLong(), any())).thenReturn(true);
    when(budget.settle(
            anyString(), anyString(), anyString(), anyLong(), anyString(), anyLong(), any()))
        .thenReturn(true);
  }

  @Test
  void aWriteWithoutAKeyIsRefused() throws Exception {
    write(null, null)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_MISSING"));
    write("short", null).andExpect(status().isBadRequest());
  }

  @Test
  void aFirstWriteRunsAndIsCachedWithItsFingerprint() throws Exception {
    write(KEY, null).andExpect(status().isOk());

    ArgumentCaptor<ExchangeIdempotency.Stored> stored =
        ArgumentCaptor.forClass(ExchangeIdempotency.Stored.class);
    verify(idempotency).store(eq(namespace()), stored.capture());
    assertThat(stored.getValue().status()).isEqualTo(200);
    assertThat(stored.getValue().fingerprint())
        .isEqualTo(
            ExchangeIdempotency.fingerprint(
                "POST", BLUEPRINT_CHANGES, BODY.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    long reserved = ExchangeIdempotency.claimBytes(namespace()) + 32768L;
    verify(budget)
        .reserve(
            eq(ExchangeTestSupport.CLIENT),
            eq(member),
            eq(ExchangeIdempotency.CLAIM_PREFIX + namespace()),
            eq(reserved),
            any());
    verify(budget)
        .settle(
            eq(ExchangeTestSupport.CLIENT),
            eq(member),
            eq(ExchangeIdempotency.CLAIM_PREFIX + namespace()),
            eq(reserved),
            eq(ExchangeIdempotency.PREFIX + namespace()),
            eq(200L),
            any());
    verify(budget, never()).release(anyString(), anyString(), anyString(), anyLong());
    verify(idempotency).releaseClaim(namespace(), CLAIM_TOKEN);
  }

  @Test
  void aRequestThatRacedTheFirstReplaysItsAnswerUnderTheLock() throws Exception {
    when(idempotency.find(namespace()))
        .thenReturn(Optional.empty())
        .thenReturn(
            Optional.of(
                new ExchangeIdempotency.Stored(
                    ExchangeIdempotency.fingerprint(
                        "POST",
                        BLUEPRINT_CHANGES,
                        BODY.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    201,
                    MediaType.APPLICATION_JSON_VALUE,
                    "{\"first\":true}")));

    write(KEY, null)
        .andExpect(status().isCreated())
        .andExpect(header().string(ExchangeIdempotencyFilter.REPLAYED, "true"))
        .andExpect(content().json("{\"first\":true}"));

    verify(budget, never()).reserve(anyString(), anyString(), anyString(), anyLong(), any());
    verify(idempotency, never()).store(anyString(), any());
    verify(idempotency).releaseClaim(namespace(), CLAIM_TOKEN);
  }

  @Test
  void aRequestThatRacedTheFirstWithAnotherBodyIsRefusedUnderTheLock() throws Exception {
    when(idempotency.find(namespace()))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(new ExchangeIdempotency.Stored("other", 200, null, "{}")));

    write(KEY, null)
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

    verify(idempotency, never()).store(anyString(), any());
    verify(idempotency).releaseClaim(namespace(), CLAIM_TOKEN);
  }

  @Test
  void anAnswerTheBudgetCannotHoldIsDeliveredButNotCached() throws Exception {
    when(budget.settle(
            anyString(), anyString(), anyString(), anyLong(), anyString(), anyLong(), any()))
        .thenReturn(false);

    write(KEY, null).andExpect(status().isOk());

    verify(idempotency, never()).store(anyString(), any());
    verify(budget)
        .release(
            eq(ExchangeTestSupport.CLIENT),
            eq(member),
            eq(ExchangeIdempotency.CLAIM_PREFIX + namespace()),
            anyLong());
    verify(idempotency).releaseClaim(namespace(), CLAIM_TOKEN);
  }

  @Test
  void theSameRequestIsReplayed() throws Exception {
    when(idempotency.find(namespace()))
        .thenReturn(
            Optional.of(
                new ExchangeIdempotency.Stored(
                    ExchangeIdempotency.fingerprint(
                        "POST",
                        BLUEPRINT_CHANGES,
                        BODY.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    201,
                    MediaType.APPLICATION_JSON_VALUE,
                    "{\"replayed\":true}")));

    write(KEY, null)
        .andExpect(status().isCreated())
        .andExpect(header().string(ExchangeIdempotencyFilter.REPLAYED, "true"))
        .andExpect(content().json("{\"replayed\":true}"));
    verify(idempotency, never()).claim(anyString());
  }

  @Test
  void aReusedKeyWithAnotherBodyIsRefused() throws Exception {
    when(idempotency.find(namespace()))
        .thenReturn(Optional.of(new ExchangeIdempotency.Stored("other", 200, null, "{}")));

    write(KEY, null)
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    verify(idempotency, never()).store(anyString(), any());
  }

  @Test
  void aDuplicateInFlightIsRefused() throws Exception {
    when(idempotency.claim(namespace())).thenReturn(Optional.empty());

    write(KEY, null)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_IN_PROGRESS"));
    verify(idempotency, never()).store(anyString(), any());
  }

  @Test
  void aFullBudgetRefusesBeforeTheWrite() throws Exception {
    when(budget.reserve(anyString(), anyString(), anyString(), anyLong(), any())).thenReturn(false);
    when(budget.retryAfterSeconds(anyString(), anyString(), anyLong())).thenReturn(1234L);

    write(KEY, null)
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1234"))
        .andExpect(jsonPath("$.code").value("EXCHANGE_BUDGET_EXHAUSTED"));
    verify(quotas).refundCounted(any());
    verify(idempotency, never()).store(anyString(), any());
    verify(budget, never())
        .settle(anyString(), anyString(), anyString(), anyLong(), anyString(), anyLong(), any());
    verify(budget, never()).release(anyString(), anyString(), anyString(), anyLong());
    verify(idempotency).releaseClaim(namespace(), CLAIM_TOKEN);
  }

  @Test
  void anUnreachableStoreFailsClosed() throws Exception {
    when(idempotency.find(anyString())).thenThrow(new ExchangeUnavailableException("down", null));

    write(KEY, null)
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
  }

  @Test
  void aDomainRefusalIsCachedButAServerFailureAndAMassChangeAreNot() throws Exception {
    write(KEY, "409:VERSION_CONFLICT").andExpect(status().isConflict());
    verify(idempotency).store(eq(namespace()), any());

    write("write-0000002", "409:MASS_CHANGE_CONFIRMATION_REQUIRED")
        .andExpect(status().isConflict());
    write("write-0000003", "503:SERVICE_UNAVAILABLE").andExpect(status().isServiceUnavailable());
    write("write-0000004", "403:NOT_PERMITTED").andExpect(status().isForbidden());

    verify(idempotency, never()).store(eq(namespace("write-0000002")), any());
    verify(idempotency, never()).store(eq(namespace("write-0000003")), any());
    verify(idempotency, never()).store(eq(namespace("write-0000004")), any());
    verify(idempotency).releaseClaim(namespace("write-0000003"), CLAIM_TOKEN);
    verify(budget)
        .release(
            eq(ExchangeTestSupport.CLIENT),
            eq(member),
            eq(ExchangeIdempotency.CLAIM_PREFIX + namespace("write-0000003")),
            anyLong());
  }

  @Test
  void otherDomainRefusalsAreCachedAndAServerErrorIsNot() throws Exception {
    write("write-0000005", "422:SCHEMA_INVALID").andExpect(status().isUnprocessableContent());
    write("write-0000006", "410:CURSOR_EXPIRED").andExpect(status().isGone());
    write("write-0000007", "500:INTERNAL_ERROR").andExpect(status().isInternalServerError());

    verify(idempotency).store(eq(namespace("write-0000005")), any());
    verify(idempotency).store(eq(namespace("write-0000006")), any());
    verify(idempotency, never()).store(eq(namespace("write-0000007")), any());
  }

  @Test
  void anAnswerIsDeliveredEvenWhenItCannotBeCached() throws Exception {
    doThrow(new ExchangeUnavailableException("down", null))
        .when(idempotency)
        .store(anyString(), any());

    write(KEY, null).andExpect(status().isOk());
    verify(idempotency).releaseClaim(namespace(), CLAIM_TOKEN);
    verify(budget)
        .release(
            eq(ExchangeTestSupport.CLIENT),
            eq(member),
            eq(ExchangeIdempotency.PREFIX + namespace()),
            eq(200L));
  }

  @Test
  void aReplayWithoutAContentTypeStillReplays() throws Exception {
    when(idempotency.find(namespace()))
        .thenReturn(
            Optional.of(
                new ExchangeIdempotency.Stored(
                    ExchangeIdempotency.fingerprint(
                        "POST",
                        BLUEPRINT_CHANGES,
                        BODY.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    204,
                    null,
                    "")));

    write(KEY, null)
        .andExpect(status().isNoContent())
        .andExpect(header().string(ExchangeIdempotencyFilter.REPLAYED, "true"));
  }

  @Test
  void readsNeedNoKey() throws Exception {
    ExchangeTestSupport.call(mockMvc, key, TOKEN, HttpMethod.GET, STOCK, null, null)
        .andExpect(status().isOk());
    verify(idempotency, never()).find(anyString());
  }

  /**
   * Sends one write.
   *
   * @param idempotencyKey the key, or {@code null}
   * @param probe {@code status:code} for the probe to answer with, or {@code null} for 200
   * @return the result
   * @throws Exception if the request fails
   */
  private @NotNull ResultActions write(@Nullable String idempotencyKey, @Nullable String probe)
      throws Exception {
    String nonce =
        mockMvc
            .perform(
                MockMvcRequestBuilders.post(BLUEPRINT_CHANGES)
                    .header(HttpHeaders.AUTHORIZATION, "DPoP " + TOKEN)
                    .header(
                        "DPoP",
                        ExchangeTestSupport.proof(key, TOKEN, "POST", BLUEPRINT_CHANGES, null)))
            .andReturn()
            .getResponse()
            .getHeader(ExchangeTokenGateFilter.DPOP_NONCE_HEADER);
    var request =
        MockMvcRequestBuilders.post(BLUEPRINT_CHANGES)
            .header(HttpHeaders.AUTHORIZATION, "DPoP " + TOKEN)
            .header("DPoP", ExchangeTestSupport.proof(key, TOKEN, "POST", BLUEPRINT_CHANGES, nonce))
            .contentType(MediaType.APPLICATION_JSON)
            .content(BODY);
    if (idempotencyKey != null) {
      request.header(ExchangeIdempotencyFilter.IDEMPOTENCY_KEY, idempotencyKey);
    }
    if (probe != null) {
      request.header(ExchangeTestSupport.PROBE_ANSWER, probe);
    }
    return mockMvc.perform(request);
  }

  /**
   * Returns the namespace of the default key.
   *
   * @return the namespace
   */
  private @NotNull String namespace() {
    return namespace(KEY);
  }

  /**
   * Returns the namespace of a key for the test member.
   *
   * @param idempotencyKey the key
   * @return the namespace
   */
  private @NotNull String namespace(@NotNull String idempotencyKey) {
    return ExchangeIdempotency.namespace(ExchangeTestSupport.CLIENT, member, idempotencyKey);
  }
}
