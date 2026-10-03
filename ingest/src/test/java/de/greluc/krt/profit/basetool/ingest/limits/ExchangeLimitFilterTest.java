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

package de.greluc.krt.profit.basetool.ingest.limits;

import static de.greluc.krt.profit.basetool.ingest.support.ExchangeTestSupport.ACCOUNT_CHECK;
import static de.greluc.krt.profit.basetool.ingest.support.ExchangeTestSupport.BLUEPRINT_CHANGES;
import static de.greluc.krt.profit.basetool.ingest.support.ExchangeTestSupport.CLIENT;
import static de.greluc.krt.profit.basetool.ingest.support.ExchangeTestSupport.STOCK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import de.greluc.krt.profit.basetool.ingest.registry.ExchangeUnavailableException;
import de.greluc.krt.profit.basetool.ingest.relay.ExchangeRelay;
import de.greluc.krt.profit.basetool.ingest.store.ExchangeBudget;
import de.greluc.krt.profit.basetool.ingest.store.ExchangeIdempotency;
import de.greluc.krt.profit.basetool.ingest.store.ExchangeQuotas;
import de.greluc.krt.profit.basetool.ingest.support.ExchangeTestSupport;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/** The exchange's limits end to end through the gates (REQ-XCH-023). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import(ExchangeTestSupport.ProbeRoutes.class)
@TestPropertySource(
    properties = {
      "app.exchange.limits.member-per-minute=2",
      "app.exchange.limits.client-per-minute=1000",
      "app.exchange.limits.writes-per-day=1",
      "app.exchange.limits.account-checks-per-hour=1"
    })
class ExchangeLimitFilterTest {

  private static final String TOKEN = "limit-token";
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
                String.join(" ", GRANTS),
                Instant.now().minusSeconds(30)));
    when(registryReader.current())
        .thenReturn(ExchangeTestSupport.registry(true, true, GRANTS, null));
    when(revocationReader.isDenied(anyString())).thenReturn(false);
    when(idempotency.claim(anyString())).thenReturn(Optional.of("claim-token"));
    when(budget.reserve(anyString(), anyString(), anyString(), anyLong(), any())).thenReturn(true);
    when(budget.settle(
            anyString(), anyString(), anyString(), anyLong(), anyString(), anyLong(), any()))
        .thenReturn(true);
    when(quotas.secondsUntilTomorrow()).thenReturn(3600L);
  }

  @Test
  void theMemberBucketAdmitsItsLimitThenRefusesWithRetryAfter() throws Exception {
    call(HttpMethod.GET, STOCK)
        .andExpect(status().isOk())
        .andExpect(header().string(ExchangeLimitFilter.RATE_LIMIT_POLICY, "2;w=60"))
        .andExpect(
            header().string(ExchangeLimitFilter.RATE_LIMIT, "limit=2, remaining=1, reset=30"));
    call(HttpMethod.GET, STOCK).andExpect(status().isOk());
    call(HttpMethod.GET, STOCK)
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
        .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
  }

  @Test
  void theClientsOverrideRaisesTheMemberLimit() throws Exception {
    when(registryReader.current())
        .thenReturn(ExchangeTestSupport.registryWithLimits(GRANTS, null, 5));

    for (int i = 0; i < 5; i++) {
      call(HttpMethod.GET, STOCK).andExpect(status().isOk());
    }
    call(HttpMethod.GET, STOCK).andExpect(status().isTooManyRequests());
  }

  @Test
  void writesCountAgainstTheDailyQuota() throws Exception {
    when(quotas.countWrite(CLIENT, member))
        .thenReturn(new ExchangeQuotas.Counted("q", 1L))
        .thenReturn(new ExchangeQuotas.Counted("q", 2L));

    call(HttpMethod.POST, BLUEPRINT_CHANGES).andExpect(status().isOk());
    call(HttpMethod.POST, BLUEPRINT_CHANGES)
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string(HttpHeaders.RETRY_AFTER, "3600"))
        .andExpect(jsonPath("$.code").value("QUOTA_EXCEEDED"));
  }

  @Test
  void readsDoNotTouchTheQuota() throws Exception {
    call(HttpMethod.GET, STOCK).andExpect(status().isOk());

    verify(quotas, never()).countWrite(anyString(), anyString());
  }

  @Test
  void anUncountableQuotaFailsClosed() throws Exception {
    when(quotas.countWrite(CLIENT, member))
        .thenThrow(new ExchangeUnavailableException("down", null));

    call(HttpMethod.POST, BLUEPRINT_CHANGES)
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
  }

  @Test
  void theAccountCheckHasItsOwnHourlyLimit() throws Exception {
    when(relay.forward(any(), anyString(), any(), any(), any()))
        .thenReturn(
            new ExchangeRelay.Result(
                200, JsonMapper.builder().build().readTree("{\"result\":\"match\"}"), null, null));
    String body = "{\"handle\":\"Cutter_Pilot\"}";

    ExchangeTestSupport.call(mockMvc, key, TOKEN, HttpMethod.POST, ACCOUNT_CHECK, body, null)
        .andExpect(status().isOk());
    ExchangeTestSupport.call(mockMvc, key, TOKEN, HttpMethod.POST, ACCOUNT_CHECK, body, null)
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    verify(relay, times(1)).forward(any(), anyString(), any(), any(), any());
  }

  @Test
  void theResetEstimateRoundsUp() {
    assertThat(ExchangeLimitFilter.secondsUntilFull(120, 120)).isZero();
    assertThat(ExchangeLimitFilter.secondsUntilFull(120, 119)).isEqualTo(1L);
    assertThat(ExchangeLimitFilter.secondsUntilFull(2, 0)).isEqualTo(60L);
  }

  /**
   * Sends one DPoP-bound request as the member.
   *
   * @param method the method
   * @param path the path
   * @return the result
   * @throws Exception if the request fails
   */
  private @NotNull ResultActions call(@NotNull HttpMethod method, @NotNull String path)
      throws Exception {
    return ExchangeTestSupport.call(mockMvc, key, TOKEN, method, path, null, null);
  }
}
