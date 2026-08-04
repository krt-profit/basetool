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
 * The acting-member trust boundary, exercised through the <strong>real filter chain</strong>
 * (ADR-0129).
 *
 * <p>Deliberately not a unit test of the filter. Every failure this boundary has actually had was
 * an ordering or wiring failure that a filter tested in isolation cannot see: filters running
 * before authentication against an empty context, a gate evaluating the wrong subject, a caller
 * reaching an endpoint the header was never meant for. So these drive {@link FilterChainProxy} as
 * configured, with the approval gate live.
 *
 * <p>The consent gate is NOT live here: the {@code test} profile stands it down for the whole
 * suite, which is exactly how a fail-open on that gate stayed green. {@link
 * ActingMemberIdentityChainTest} re-arms it for itself and owns that case; this class owns the
 * refusals.
 *
 * <p>The allowlist is set to a test client id, which is the only thing that distinguishes a gateway
 * from any other caller — matching production, where an empty allowlist admits nobody.
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
   * How often this filter has refused for one reason so far.
   *
   * <p>Read as a delta around each request rather than as an absolute: the meter registry is a
   * context-scoped singleton, so counters carry over from every test that ran before this one in
   * the same context.
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

    // Asserted on the code AND the reason, not on the status alone. Three filters in this chain
    // answer 403 and the request would have been refused downstream anyway (this caller has no
    // consent row either), so a bare status assertion passes even when THIS guard never ran.
    assertThat(refusals(MetricNames.ON_BEHALF_OF_NOT_A_GATEWAY)).isEqualTo(before + 1);
  }

  /**
   * The gateway may not use the header on an endpoint outside the two it is bounded to.
   *
   * <p>ADR-0129 bounds the header to the two import endpoints. Before this the bound existed only
   * in prose, so a future endpoint would have inherited impersonation by accident.
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

  // The percent-encoded-path case is NOT here on purpose: MockMvc normalises the path before any
  // filter sees it, so this level cannot reproduce it — the same limitation TermsAcceptanceAccess-
  // FilterTest documents for the identical guard. It is covered directly in
  // ActingMemberFilterPathMatchingTest instead, which drives the filter with a raw request URI.

  /**
   * A member with no local account is refused, not created.
   *
   * <p>The login path creates a row for a first-seen subject, which is right when a person
   * authenticated. Here nobody did — inventing a member from a header would make the header a
   * registration primitive, and would also defeat the liveness check by re-creating exactly the row
   * that was meant to be missing.
   */
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

    // Counted as "not live" rather than as its own reason: the answer must not distinguish an
    // unknown subject from an offboarded one, or the endpoint becomes an enumeration oracle.
    assertThat(refusals(MetricNames.ON_BEHALF_OF_MEMBER_NOT_LIVE)).isEqualTo(before + 1);
  }

  /**
   * An unauthenticated caller sending the header at an unbound endpoint is counted as
   * <em>endpoint_not_bound</em>, not as <em>no_authenticated_caller</em>.
   *
   * <p>This pins the guard ORDER, which is load-bearing. This filter sits on the unmatched chain,
   * so it sees every path; while the caller check came first, any anonymous internet request that
   * carried this header — a header shipped in the extractor and documented publicly — was counted
   * under a reason documented as structurally impossible and alerted on as evidence of a
   * filter-ordering bug. One probe produced an hour-long page pointing at the wrong thing.
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

  /**
   * On a bound endpoint, a header with no authenticated caller is still refused and still counted.
   *
   * <p>The reason survives the reorder — it is now confined to the two endpoints that accept the
   * header at all, which is what makes a sustained rate on it meaningful rather than ambient
   * internet noise.
   */
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
