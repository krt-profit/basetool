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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.support.AppProblemProperties;
import de.greluc.krt.profit.basetool.backend.support.ProblemResponseFactory;
import de.greluc.krt.profit.basetool.backend.support.TermsConsentCheck;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.ObjectMapper;

/**
 * Behaviour of the backend consent boundary (REQ-SEC-028).
 *
 * <p>The exemption tests carry the most weight. If the consent endpoints were ever refused, the
 * block would be permanent for everyone — there would be no request left that could record consent
 * — and that failure is invisible until a terms change puts the whole squadron behind the gate at
 * once.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TermsAcceptanceAccessFilterTest {

  private static final UUID USER_ID = UUID.randomUUID();

  @Mock private TermsConsentCheck termsConsentCheck;
  @Mock private FilterChain filterChain;

  private MeterRegistry meterRegistry;
  private TermsAcceptanceAccessFilter filter;

  @BeforeEach
  void setUp() {
    StaticMessageSource messages = new StaticMessageSource();
    messages.addMessage("problem.terms_not_accepted.title", java.util.Locale.ENGLISH, "Forbidden");
    messages.addMessage("problem.terms_not_accepted.detail", java.util.Locale.ENGLISH, "Accept.");
    meterRegistry = new SimpleMeterRegistry();
    filter =
        new TermsAcceptanceAccessFilter(
            termsConsentCheck,
            messages,
            new ProblemResponseFactory(new AppProblemProperties()),
            new ObjectMapper(),
            meterRegistry);
    authenticateWithSubject(USER_ID.toString());
  }

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  /** A consenting caller passes through untouched. */
  @Test
  void letsAConsentingCallerThrough() throws Exception {
    when(termsConsentCheck.hasAcceptedCurrentTerms(USER_ID)).thenReturn(true);

    MockHttpServletResponse response = invoke("/api/v1/missions");

    verify(filterChain).doFilter(any(), any());
    assertThat(response.getStatus()).isEqualTo(200);
  }

  /** A caller without consent is refused with the stable code and a problem body. */
  @Test
  void refusesACallerWithoutConsent() throws Exception {
    when(termsConsentCheck.hasAcceptedCurrentTerms(USER_ID)).thenReturn(false);

    MockHttpServletResponse response = invoke("/api/v1/missions");

    verify(filterChain, never()).doFilter(any(), any());
    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString()).contains("TERMS_NOT_ACCEPTED");
    assertThat(response.getHeader("X-Correlation-Id")).isNotBlank();
    assertThat(meterRegistry.counter("basetool.http.error", "code", "TERMS_NOT_ACCEPTED").count())
        .isEqualTo(1.0);
  }

  /**
   * The consent endpoints stay reachable for a caller without consent. Refusing these would make
   * the gate impossible to pass — for everyone, permanently.
   */
  @Test
  void neverRefusesTheConsentEndpoints() throws Exception {
    when(termsConsentCheck.hasAcceptedCurrentTerms(USER_ID)).thenReturn(false);

    for (String path : new String[] {"/api/v1/terms/status", "/api/v1/terms/acceptance"}) {
      MockHttpServletResponse response = invoke(path);
      assertThat(response.getStatus()).as(path).isEqualTo(200);
    }
    verify(filterChain, org.mockito.Mockito.times(2)).doFilter(any(), any());
  }

  /**
   * A caller who is also pending approval keeps reaching the registration-status endpoint, so the
   * frontend routes them to the waiting page — the message they can actually act on.
   */
  @Test
  void neverRefusesTheRegistrationStatusEndpoint() throws Exception {
    when(termsConsentCheck.hasAcceptedCurrentTerms(USER_ID)).thenReturn(false);

    assertThat(invoke("/api/v1/users/me/registration-status").getStatus()).isEqualTo(200);
  }

  /**
   * A percent-encoded path prefix does not slip past the gate.
   *
   * <p>{@code getRequestURI()} is the raw, still-encoded URI while Spring MVC routes on the decoded
   * path, so a naive {@code startsWith("/api/")} lets {@code /%61pi/v1/missions} through — and
   * {@code RequestMappingHandlerMapping} then decodes {@code %61pi} to {@code api} and dispatches
   * it. The default {@code StrictHttpFirewall} blocks {@code %2e}/{@code %2f}/{@code %25} but not
   * {@code %61}. Must be a direct filter test: MockMvc normalises the path before the filter sees
   * it, so it cannot reproduce this.
   */
  @Test
  void isNotSkippableByPercentEncodingThePathPrefix() throws Exception {
    when(termsConsentCheck.hasAcceptedCurrentTerms(USER_ID)).thenReturn(false);

    assertThat(invoke("/%61pi/v1/missions").getStatus()).isEqualTo(403);
    verify(filterChain, never()).doFilter(any(), any());
  }

  /**
   * The exemption is equally encoding-proof in the other direction: an encoded spelling of the
   * consent endpoint must still be exempt, or the gate would have no exit for a client that happens
   * to encode.
   */
  @Test
  void exemptsAnEncodedSpellingOfTheConsentEndpoint() throws Exception {
    when(termsConsentCheck.hasAcceptedCurrentTerms(USER_ID)).thenReturn(false);

    assertThat(invoke("/api/v1/%74erms/acceptance").getStatus()).isEqualTo(200);
  }

  /**
   * A path that merely begins with the consent literal is NOT exempt. Guards the over-broad {@code
   * startsWith("/api/v1/terms")} the pattern match replaced.
   */
  @Test
  void doesNotExemptAPathThatOnlyStartsWithTheConsentLiteral() throws Exception {
    when(termsConsentCheck.hasAcceptedCurrentTerms(USER_ID)).thenReturn(false);

    assertThat(invoke("/api/v1/terms-export").getStatus()).isEqualTo(403);
  }

  /** Non-API paths are none of this filter's business. */
  @Test
  void ignoresNonApiPaths() throws Exception {
    when(termsConsentCheck.hasAcceptedCurrentTerms(USER_ID)).thenReturn(false);

    assertThat(invoke("/actuator/health").getStatus()).isEqualTo(200);
  }

  /** An unauthenticated request is left to the security chain, not judged on consent. */
  @Test
  void ignoresAnUnauthenticatedRequest() throws Exception {
    SecurityContextHolder.clearContext();

    assertThat(invoke("/api/v1/missions").getStatus()).isEqualTo(200);
    verify(termsConsentCheck, never()).hasAcceptedCurrentTerms(any());
  }

  /**
   * A non-JWT principal is ignored: there is no {@code sub} to record consent against, and this is
   * not the filter that governs such callers.
   */
  @Test
  void ignoresANonJwtPrincipal() throws Exception {
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken("someone", "n/a"));

    assertThat(invoke("/api/v1/missions").getStatus()).isEqualTo(200);
    verify(termsConsentCheck, never()).hasAcceptedCurrentTerms(any());
  }

  /**
   * A {@code sub} that is not a UUID — a service account, or a malformed token — is let through
   * rather than blocked. It is not a person who can accept anything, and the audience and scope
   * checks already govern it; refusing here would be the wrong control in the wrong place.
   */
  @Test
  void ignoresASubjectThatIsNotAUuid() throws Exception {
    authenticateWithSubject("service-account-extractor");

    assertThat(invoke("/api/v1/missions").getStatus()).isEqualTo(200);
    verify(termsConsentCheck, never()).hasAcceptedCurrentTerms(any());
  }

  /**
   * Runs the filter against the given path.
   *
   * @param path the context-relative request path
   * @return the response the filter produced
   * @throws Exception if the filter throws
   */
  private MockHttpServletResponse invoke(String path) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    request.setRequestURI(path);
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, filterChain);
    return response;
  }

  /**
   * Installs a JWT authentication carrying the given subject.
   *
   * @param subject the {@code sub} claim
   */
  private static void authenticateWithSubject(String subject) {
    Jwt jwt =
        Jwt.withTokenValue("token")
            .header("alg", "none")
            .claim("sub", subject)
            .claims(claims -> claims.putAll(Map.of()))
            .build();
    SecurityContextHolder.getContext()
        .setAuthentication(new JwtAuthenticationToken(jwt, java.util.List.of()));
  }
}
