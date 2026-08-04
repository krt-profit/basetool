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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.support.ActingSubjectResolver;
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
 * configured, with both person-gates live.
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

  private MockMvc mockMvc;

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
    mockMvc
        .perform(
            post(INGEST_PATH)
                .with(jwt().jwt(token -> token.subject(GATEWAY).claim("azp", "basetool-frontend")))
                .header(ActingSubjectResolver.ON_BEHALF_OF_HEADER, MEMBER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden());
  }

  /**
   * The gateway may not use the header on an endpoint outside the two it is bounded to.
   *
   * <p>ADR-0129 bounds the header to the two import endpoints. Before this the bound existed only
   * in prose, so a future endpoint would have inherited impersonation by accident.
   */
  @Test
  void refusesTheHeaderOnAnEndpointItIsNotBoundTo() throws Exception {
    mockMvc
        .perform(
            post(OTHER_PATH)
                .with(
                    jwt().jwt(token -> token.subject(GATEWAY).claim("azp", "test-ingest-gateway")))
                .header(ActingSubjectResolver.ON_BEHALF_OF_HEADER, MEMBER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden());
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
    mockMvc
        .perform(
            post(INGEST_PATH)
                .with(
                    jwt().jwt(token -> token.subject(GATEWAY).claim("azp", "test-ingest-gateway")))
                .header(ActingSubjectResolver.ON_BEHALF_OF_HEADER, MEMBER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden());
  }

  /** A malformed subject never reaches the persistence layer. */
  @Test
  void refusesAMalformedSubject() throws Exception {
    mockMvc
        .perform(
            post(INGEST_PATH)
                .with(
                    jwt().jwt(token -> token.subject(GATEWAY).claim("azp", "test-ingest-gateway")))
                .header(ActingSubjectResolver.ON_BEHALF_OF_HEADER, "not-a-uuid")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden());
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
