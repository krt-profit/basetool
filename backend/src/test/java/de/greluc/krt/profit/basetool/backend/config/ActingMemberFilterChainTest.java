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

package de.greluc.krt.profit.basetool.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.support.ActingMemberHeader;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Tests the refusals of the acting-member trust boundary through the real filter chain, with the
 * approval gate live (ADR-0129).
 *
 * <p>The consent gate is stood down here; {@link ActingMemberIdentityChainTest} covers it.
 */
@SpringBootTest
@TestPropertySource(properties = "app.security.ingest-gateway.client-ids=test-ingest-gateway")
class ActingMemberFilterChainTest {

  private static final String INGEST_PATH = "/api/v1/refinery-orders/import-extract";
  private static final String OTHER_PATH = "/api/v1/missions";
  private static final String MEMBER = "44444444-4444-4444-4444-444444444444";
  private static final String GATEWAY = "55555555-5555-5555-5555-555555555555";

  @Autowired private WebApplicationContext context;
  @Autowired private MeterRegistry meterRegistry;

  private MockMvc mockMvc;

  /**
   * Returns how often the filter has refused for one reason so far; tests read it as a delta
   * because the registry is shared across the context.
   *
   * @param reason the bounded {@code MetricNames.ON_BEHALF_OF_*} reason
   * @return the current count, or {@code 0} when nothing has been counted under it yet
   */
  private double refusals(String reason) {
    Counter counter =
        meterRegistry
            .find(MetricNames.ON_BEHALF_OF_REFUSED)
            .tag(MetricNames.TAG_REASON, reason)
            .counter();
    return counter == null ? 0d : counter.count();
  }

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean(FilterChainProxy.class))
            .build();
  }

  /**
   * A caller that is not a configured gateway may not act for anyone.
   *
   * <p>The most important assertion here: without it the header is an impersonation primitive for
   * every authenticated member in the application.
   */
  @Test
  void refusesAnOnBehalfOfHeaderFromAnOrdinaryMember() throws Exception {
    double before = refusals(MetricNames.ON_BEHALF_OF_NOT_A_GATEWAY);

    mockMvc
        .perform(
            post(INGEST_PATH)
                .with(jwt().jwt(token -> token.subject(GATEWAY).claim("azp", "basetool-frontend")))
                .header(ActingMemberHeader.ON_BEHALF_OF_HEADER, MEMBER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(ActingMemberFilter.CODE_ACTING_MEMBER_REFUSED));

    assertThat(refusals(MetricNames.ON_BEHALF_OF_NOT_A_GATEWAY)).isEqualTo(before + 1);
  }

  /**
   * The gateway may not use the header on an endpoint outside the two it is bound to (ADR-0129).
   */
  @Test
  void refusesTheHeaderOnAnEndpointItIsNotBoundTo() throws Exception {
    double before = refusals(MetricNames.ON_BEHALF_OF_ENDPOINT_NOT_BOUND);

    mockMvc
        .perform(
            post(OTHER_PATH)
                .with(
                    jwt().jwt(token -> token.subject(GATEWAY).claim("azp", "test-ingest-gateway")))
                .header(ActingMemberHeader.ON_BEHALF_OF_HEADER, MEMBER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(ActingMemberFilter.CODE_ACTING_MEMBER_REFUSED));

    assertThat(refusals(MetricNames.ON_BEHALF_OF_ENDPOINT_NOT_BOUND)).isEqualTo(before + 1);
  }

  /** A member with no local account is refused, not created. */
  @Test
  void refusesAMemberWithNoLocalAccount() throws Exception {
    double before = refusals(MetricNames.ON_BEHALF_OF_MEMBER_NOT_LIVE);

    mockMvc
        .perform(
            post(INGEST_PATH)
                .with(
                    jwt().jwt(token -> token.subject(GATEWAY).claim("azp", "test-ingest-gateway")))
                .header(ActingMemberHeader.ON_BEHALF_OF_HEADER, MEMBER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(ActingMemberFilter.CODE_ACTING_MEMBER_REFUSED));

    assertThat(refusals(MetricNames.ON_BEHALF_OF_MEMBER_NOT_LIVE)).isEqualTo(before + 1);
  }

  /**
   * An unauthenticated caller sending the header at an unbound endpoint is counted as
   * <em>endpoint_not_bound</em>, not as <em>no_authenticated_caller</em>, pinning the guard order.
   */
  @Test
  void countsAnAnonymousProbeOnAnUnboundPathAsOutOfBoundsNotAsAMissingCaller() throws Exception {
    double bound = refusals(MetricNames.ON_BEHALF_OF_ENDPOINT_NOT_BOUND);
    double caller = refusals(MetricNames.ON_BEHALF_OF_NO_CALLER);

    mockMvc
        .perform(
            post(OTHER_PATH)
                .header(ActingMemberHeader.ON_BEHALF_OF_HEADER, MEMBER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(ActingMemberFilter.CODE_ACTING_MEMBER_REFUSED));

    assertThat(refusals(MetricNames.ON_BEHALF_OF_ENDPOINT_NOT_BOUND)).isEqualTo(bound + 1);
    assertThat(refusals(MetricNames.ON_BEHALF_OF_NO_CALLER)).isEqualTo(caller);
  }

  /** On a bound endpoint, a header with no authenticated caller is still refused and counted. */
  @Test
  void stillRefusesAHeaderWithNoAuthenticatedCallerOnABoundPath() throws Exception {
    double before = refusals(MetricNames.ON_BEHALF_OF_NO_CALLER);

    mockMvc
        .perform(
            post(INGEST_PATH)
                .header(ActingMemberHeader.ON_BEHALF_OF_HEADER, MEMBER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(ActingMemberFilter.CODE_ACTING_MEMBER_REFUSED));

    assertThat(refusals(MetricNames.ON_BEHALF_OF_NO_CALLER)).isEqualTo(before + 1);
  }

  /** A malformed subject never reaches the persistence layer. */
  @Test
  void refusesAMalformedSubject() throws Exception {
    double before = refusals(MetricNames.ON_BEHALF_OF_MALFORMED);

    mockMvc
        .perform(
            post(INGEST_PATH)
                .with(
                    jwt().jwt(token -> token.subject(GATEWAY).claim("azp", "test-ingest-gateway")))
                .header(ActingMemberHeader.ON_BEHALF_OF_HEADER, "not-a-uuid")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(ActingMemberFilter.CODE_ACTING_MEMBER_REFUSED));

    assertThat(refusals(MetricNames.ON_BEHALF_OF_MALFORMED)).isEqualTo(before + 1);
  }

  /**
   * Without the header nothing changes — the ordinary path must be untouched.
   *
   * <p>A 4xx is expected here for reasons that have nothing to do with this filter (the body is not
   * a valid extract); what matters is that it is not the 403 the guards above produce.
   */
  @Test
  void leavesAnOrdinaryRequestAlone() throws Exception {
    mockMvc
        .perform(
            post(INGEST_PATH)
                .with(jwt().jwt(token -> token.subject(MEMBER)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(
            result ->
                org.junit.jupiter.api.Assertions.assertNotEquals(
                    403,
                    result.getResponse().getStatus(),
                    "no header must mean no acting-member handling"));
  }
}
