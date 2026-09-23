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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.support.ActingMemberAuthorities;
import de.greluc.krt.profit.basetool.backend.support.ActingMemberHeader;
import de.greluc.krt.profit.basetool.backend.support.AppProblemProperties;
import de.greluc.krt.profit.basetool.backend.support.BoundProperties;
import de.greluc.krt.profit.basetool.backend.support.IngestGatewayProperties;
import de.greluc.krt.profit.basetool.backend.support.ProblemResponseFactory;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.ObjectMapper;

/**
 * The "no acting member" early exit of {@link ActingMemberFilter}, driven directly.
 *
 * <p>Pins the one guard that CodeQL alert #1125 ({@code java/user-controlled-bypass}) read as a
 * request-controlled skip of the acting-member lookup. Skipping it IS the design when the header is
 * absent: the request then runs under the caller's own identity, untouched, and every later check
 * still applies to that identity. What must hold, and is asserted here, is that an absent or blank
 * header neither refuses, nor looks anybody up, nor replaces the security context — and that a
 * present header never takes this exit.
 */
class ActingMemberFilterAbsentHeaderTest {

  private static final String ACTING_PATH = "/api/v1/refinery-orders/import-extract";
  private static final String OTHER_PATH = "/api/v1/missions";

  private final ActingMemberAuthorities authorities = mock(ActingMemberAuthorities.class);
  private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

  private ActingMemberFilter filter() {
    IngestGatewayProperties properties =
        new IngestGatewayProperties(List.of("test-ingest-gateway"));
    StaticMessageSource messages = new StaticMessageSource();
    messages.addMessage("problem.acting_member_refused.title", Locale.ENGLISH, "Forbidden");
    messages.addMessage("problem.acting_member_refused.detail", Locale.ENGLISH, "Refused.");
    return new ActingMemberFilter(
        properties,
        authorities,
        messages,
        new ProblemResponseFactory(BoundProperties.defaults(AppProblemProperties.class)),
        new ObjectMapper(),
        meterRegistry);
  }

  /**
   * Installs a gateway caller, the one identity for which the header would otherwise be honoured.
   *
   * @return the installed authentication, to compare the context against afterwards
   */
  private static Authentication gatewayCaller() {
    Jwt token =
        Jwt.withTokenValue("t")
            .header("alg", "none")
            .subject("55555555-5555-5555-5555-555555555555")
            .claim("azp", "test-ingest-gateway")
            .build();
    Authentication caller = new JwtAuthenticationToken(token, List.of());
    SecurityContextHolder.getContext().setAuthentication(caller);
    return caller;
  }

  /**
   * How often this filter refused, over every reason.
   *
   * @return the summed refusal count
   */
  private double refusals() {
    return meterRegistry.find(MetricNames.ON_BEHALF_OF_REFUSED).counters().stream()
        .mapToDouble(counter -> counter.count())
        .sum();
  }

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  /** Without the header the gateway's own request passes through exactly as it came in. */
  @Test
  void aMissingHeaderLeavesTheCallerAndTheContextAlone() throws Exception {
    Authentication caller = gatewayCaller();
    MockHttpServletRequest request = new MockHttpServletRequest("POST", ACTING_PATH);
    FilterChain chain = mock(FilterChain.class);

    filter().doFilter(request, new MockHttpServletResponse(), chain);

    verify(chain).doFilter(any(), any());
    verifyNoInteractions(authorities);
    assertThat(refusals()).isZero();
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(caller);
  }

  /**
   * A blank value names nobody and is treated as no header — on a bound and an unbound path alike,
   * so it is neither an impersonation nor a refusal the out-of-bounds alert would count.
   *
   * @param value the blank header value
   */
  @ParameterizedTest
  @ValueSource(strings = {"", " ", "\t", "   "})
  void aBlankHeaderIsTreatedAsAbsent(String value) throws Exception {
    Authentication caller = gatewayCaller();
    FilterChain chain = mock(FilterChain.class);

    for (String path : new String[] {ACTING_PATH, OTHER_PATH}) {
      MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
      request.addHeader(ActingMemberHeader.ON_BEHALF_OF_HEADER, value);
      filter().doFilter(request, new MockHttpServletResponse(), chain);
    }

    verify(chain, org.mockito.Mockito.times(2)).doFilter(any(), any());
    verifyNoInteractions(authorities);
    assertThat(refusals()).isZero();
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(caller);
  }

  /**
   * The converse: a present, non-blank header never takes the early exit. On an unbound path it is
   * refused rather than waved through under the caller's identity.
   */
  @Test
  void aPresentHeaderNeverTakesTheExit() throws Exception {
    gatewayCaller();
    MockHttpServletRequest request = new MockHttpServletRequest("POST", OTHER_PATH);
    request.addHeader(
        ActingMemberHeader.ON_BEHALF_OF_HEADER, "44444444-4444-4444-4444-444444444444");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter().doFilter(request, response, chain);

    verify(chain, never()).doFilter(any(), any());
    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(refusals()).isEqualTo(1d);
  }
}
