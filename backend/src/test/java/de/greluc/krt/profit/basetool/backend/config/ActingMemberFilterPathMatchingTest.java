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

import de.greluc.krt.profit.basetool.backend.support.ActingMemberAuthorities;
import de.greluc.krt.profit.basetool.backend.support.ActingSubjectResolver;
import de.greluc.krt.profit.basetool.backend.support.IngestGatewayProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The endpoint bound of the acting-member header, driven directly against a raw request URI.
 *
 * <p>Deliberately not part of {@link ActingMemberFilterChainTest}: MockMvc normalises the path
 * before any filter sees it, so the chain level cannot reproduce a percent-encoded spelling — the
 * same limitation {@code TermsAcceptanceAccessFilterTest} records for the identical guard. Testing
 * it there would produce a green assertion that never exercises the code it names.
 *
 * <p>What is at stake: {@code getRequestURI()} is the raw, still-encoded URI while MVC routes on
 * the decoded path (REQ-SEC-029). A {@code startsWith} bound would accept {@code
 * /%61pi/v1/missions} here and let the request dispatch as {@code /api/v1/missions} — impersonation
 * on an endpoint ADR-0129 never granted it.
 */
class ActingMemberFilterPathMatchingTest {

  private static final String MEMBER = "44444444-4444-4444-4444-444444444444";

  private final ActingMemberAuthorities authorities = mock(ActingMemberAuthorities.class);

  private ActingMemberFilter filter() {
    IngestGatewayProperties properties = new IngestGatewayProperties();
    properties.setClientIds(List.of("test-ingest-gateway"));
    return new ActingMemberFilter(properties, authorities, new SimpleMeterRegistry());
  }

  private static MockHttpServletRequest gatewayRequest(String rawUri) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", rawUri);
    request.setRequestURI(rawUri);
    request.addHeader(ActingSubjectResolver.ON_BEHALF_OF_HEADER, MEMBER);
    Jwt token =
        Jwt.withTokenValue("t")
            .header("alg", "none")
            .subject("55555555-5555-5555-5555-555555555555")
            .claim("azp", "test-ingest-gateway")
            .build();
    SecurityContextHolder.getContext()
        .setAuthentication(new JwtAuthenticationToken(token, List.of()));
    return request;
  }

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  /** An encoded spelling of a non-acting path is refused, not waved through. */
  @Test
  void refusesAnEncodedSpellingOfANonActingPath() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter().doFilter(gatewayRequest("/%61pi/v1/missions"), response, chain);

    assertThat(response.getStatus()).isEqualTo(403);
    verify(chain, never()).doFilter(any(), any());
    verify(authorities, never()).authoritiesFor(any());
  }

  /**
   * The bound is equally encoding-proof in the other direction: an encoded acting path still acts.
   */
  @Test
  void stillActsOnAnEncodedSpellingOfAnActingPath() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter().doFilter(gatewayRequest("/api/v1/%72efinery-orders/import-extract"), response, chain);

    verify(authorities).authoritiesFor(any());
  }
}
