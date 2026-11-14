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

package de.greluc.krt.profit.basetool.frontend.kernel.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import jakarta.servlet.FilterChain;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The one filter and the one list of the frontend's session gates (ADR-0385): the gates run in
 * their order, the first that holds wins, and no gate holds a member on its own page, on an earlier
 * gate's page or on the approval waiting page.
 */
class SessionGateFilterTest {

  private final StubSessionGate first =
      new StubSessionGate("/first", "X-First-Required", "first-gate", "FIRST_REQUIRED");
  private final StubSessionGate second =
      new StubSessionGate("/second", "X-Second-Required", "second-gate", "SECOND_REQUIRED");
  private final SessionGates gates = new SessionGates(List.of(first, second));

  private SessionGateFilter filter;

  @BeforeEach
  void setUp() {
    filter = new SessionGateFilter(gates, new MockEnvironment());
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "member", "n/a", AuthorityUtils.createAuthorityList("ROLE_KRT_MEMBER")));
  }

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void aSessionNoGateHoldsPasses() throws Exception {
    FilterChain chain = mock(FilterChain.class);

    assertThat(invoke("/missions", chain).getRedirectedUrl()).isNull();
    verify(chain).doFilter(any(), any());
  }

  @Test
  void theFirstHoldingGateSendsTheSessionToItsPage() throws Exception {
    first.holding(true);
    second.holding(true);
    FilterChain chain = mock(FilterChain.class);

    assertThat(invoke("/missions", chain).getRedirectedUrl()).isEqualTo("/first");
    assertThat(second.asked()).isZero();
    verify(chain, never()).doFilter(any(), any());
  }

  @Test
  void aLaterGateLeavesTheEarlierGatesPageOpenButNotTheOtherWayRound() throws Exception {
    second.holding(true);
    assertThat(invoke("/first", mock(FilterChain.class)).getRedirectedUrl()).isNull();

    second.holding(false);
    first.holding(true);
    assertThat(invoke("/second", mock(FilterChain.class)).getRedirectedUrl()).isEqualTo("/first");
  }

  @Test
  void theApprovalWaitingPageAndThePublicPathsAreNeverHeld() throws Exception {
    first.holding(true);

    for (String path : new String[] {"/pending-approval", "/pending-approval/status", "/terms"}) {
      assertThat(invoke(path, mock(FilterChain.class)).getRedirectedUrl()).as(path).isNull();
    }
    assertThat(first.asked()).isZero();
  }

  @Test
  void anAnonymousVisitorAndTheTestProfileAreNeverHeld() throws Exception {
    first.holding(true);
    MockEnvironment test = new MockEnvironment();
    test.setActiveProfiles("test");

    assertThat(
            invoke(new SessionGateFilter(gates, test), "/missions", mock(FilterChain.class))
                .getRedirectedUrl())
        .isNull();
    SecurityContextHolder.clearContext();
    assertThat(invoke("/missions", mock(FilterChain.class)).getRedirectedUrl()).isNull();
    assertThat(first.asked()).isZero();
  }

  @Test
  void aRequestForAGatesPageIsMarkedAndAnyOtherIsNot() throws Exception {
    first.holding(true);
    MockHttpServletRequest gatePage = new MockHttpServletRequest("GET", "/first");
    MockHttpServletRequest other = new MockHttpServletRequest("GET", "/missions");

    filter.doFilter(gatePage, new MockHttpServletResponse(), mock(FilterChain.class));
    filter.doFilter(other, new MockHttpServletResponse(), mock(FilterChain.class));

    try {
      RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(gatePage));
      assertThat(GatePageRequest.isCurrent()).isTrue();
      RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(other));
      assertThat(GatePageRequest.isCurrent()).isFalse();
    } finally {
      RequestContextHolder.resetRequestAttributes();
    }
  }

  @Test
  void theListAnswersForTheGatePagesTheRefusalsAndTheCachedVerdicts() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/missions");

    assertThat(gates.isGatePage("/first")).isTrue();
    assertThat(gates.isGatePage("/second")).isTrue();
    assertThat(gates.isGatePage("/pending-approval/status")).isTrue();
    assertThat(gates.isGatePage("/missions")).isFalse();
    assertThat(gates.refusedBy("SECOND_REQUIRED")).containsSame(second);
    assertThat(gates.refusedBy("NO_ROLE")).isEmpty();
    assertThat(gates.anyKnownHolding(request)).isFalse();
    second.knownHolding(true);
    assertThat(gates.anyKnownHolding(request)).isTrue();
  }

  /**
   * Runs the filter against a path.
   *
   * @param path the request path
   * @param chain the rest of the chain
   * @return the response
   * @throws Exception if the filter throws
   */
  private @NotNull MockHttpServletResponse invoke(@NotNull String path, @NotNull FilterChain chain)
      throws Exception {
    return invoke(filter, path, chain);
  }

  /**
   * Runs a filter against a path.
   *
   * @param gateFilter the filter
   * @param path the request path
   * @param chain the rest of the chain
   * @return the response
   * @throws Exception if the filter throws
   */
  private static @NotNull MockHttpServletResponse invoke(
      @NotNull SessionGateFilter gateFilter, @NotNull String path, @NotNull FilterChain chain)
      throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    request.setRequestURI(path);
    MockHttpServletResponse response = new MockHttpServletResponse();
    gateFilter.doFilter(request, response, chain);
    return response;
  }
}
