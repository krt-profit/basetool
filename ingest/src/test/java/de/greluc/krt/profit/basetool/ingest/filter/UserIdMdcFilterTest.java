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

package de.greluc.krt.profit.basetool.ingest.filter;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.ingest.support.TestLoggingProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Unit tests for the {@code userId} MDC refinement (REQ-OBS-001/-002/-004): the authenticated JWT
 * {@code sub} replaces the {@code anonymous} seed, every other authentication shape leaves the seed
 * alone, and no claim other than {@code sub} is ever read.
 */
class UserIdMdcFilterTest {

  private final UserIdMdcFilter filter = new UserIdMdcFilter(TestLoggingProperties.defaults());

  @AfterEach
  void reset() {
    SecurityContextHolder.clearContext();
    MDC.clear();
  }

  /** Captures the MDC as the downstream chain sees it. */
  private static final class MdcCapturingChain implements FilterChain {

    private String userId;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response) {
      userId = MDC.get("userId");
    }
  }

  private static Jwt jwt(String subject, String preferredUsername) {
    Jwt.Builder builder =
        Jwt.withTokenValue("token")
            .header("alg", "none")
            .claim("preferred_username", preferredUsername)
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300));
    if (subject != null) {
      builder = builder.subject(subject);
    }
    return builder.build();
  }

  private String runWith(Jwt token) throws Exception {
    MDC.put("userId", CorrelationIdFilter.ANONYMOUS);
    if (token != null) {
      SecurityContextHolder.getContext()
          .setAuthentication(
              new JwtAuthenticationToken(token, AuthorityUtils.NO_AUTHORITIES, "ignored"));
    }
    MdcCapturingChain chain = new MdcCapturingChain();
    filter.doFilter(
        new MockHttpServletRequest("POST", "/v1/refinery-extract"),
        new MockHttpServletResponse(),
        chain);
    return chain.userId;
  }

  @Test
  void replacesTheAnonymousSeedWithTheJwtSubject() throws Exception {
    assertThat(runWith(jwt("2b9f1c3e-0000-4000-8000-abcdefabcdef", "Falcon")))
        .isEqualTo("2b9f1c3e-0000-4000-8000-abcdefabcdef");
  }

  @Test
  void neverLogsTheCallsignEvenThoughTheTokenCarriesIt() throws Exception {
    // REQ-OBS-004: preferred_username is a name and must never reach an appender.
    assertThat(runWith(jwt("2b9f1c3e-0000-4000-8000-abcdefabcdef", "Falcon")))
        .isNotEqualTo("Falcon");
  }

  @Test
  void keepsTheSeedForAnUnauthenticatedRequest() throws Exception {
    assertThat(runWith(null)).isEqualTo(CorrelationIdFilter.ANONYMOUS);
  }

  @Test
  void keepsTheSeedWhenTheTokenCarriesNoSubject() throws Exception {
    assertThat(runWith(jwt(null, "Falcon"))).isEqualTo(CorrelationIdFilter.ANONYMOUS);
  }

  @Test
  void keepsTheSeedWhenTheSubjectIsBlank() throws Exception {
    // A blank sub in the MDC would render as an empty [] field and read as a bug in the log format.
    assertThat(runWith(jwt("   ", "Falcon"))).isEqualTo(CorrelationIdFilter.ANONYMOUS);
  }

  @Test
  void keepsTheSeedForANonBearerAuthentication() throws Exception {
    MDC.put("userId", CorrelationIdFilter.ANONYMOUS);
    SecurityContextHolder.getContext()
        .setAuthentication(new TestingAuthenticationToken("someone", "n/a", List.of()));
    MdcCapturingChain chain = new MdcCapturingChain();

    filter.doFilter(
        new MockHttpServletRequest("POST", "/v1/refinery-extract"),
        new MockHttpServletResponse(),
        chain);

    assertThat(chain.userId).isEqualTo(CorrelationIdFilter.ANONYMOUS);
  }

  @Test
  void leavesTheKeyInPlaceForTheOuterAccessLogLine() throws Exception {
    // The value must survive the filter so RequestLoggingFilter — which logs outside the security
    // chain — still renders the subject. CorrelationIdFilter owns the removal.
    runWith(jwt("2b9f1c3e-0000-4000-8000-abcdefabcdef", "Falcon"));

    assertThat(MDC.get("userId")).isEqualTo("2b9f1c3e-0000-4000-8000-abcdefabcdef");
  }
}
