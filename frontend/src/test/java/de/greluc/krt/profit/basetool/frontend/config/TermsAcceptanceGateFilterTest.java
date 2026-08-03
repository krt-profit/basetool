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

package de.greluc.krt.profit.basetool.frontend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.model.dto.TermsStatusDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Behaviour of the frontend consent gate (REQ-SEC-028).
 *
 * <p>{@link #neverBlocksTheDocumentsAUserMustBeAbleToRead} is the one that matters most. A gate
 * that redirects the terms, the privacy policy or the imprint back to itself asks a person to agree
 * to something it simultaneously prevents them from reading — which is both a bad experience and
 * self-defeating for the consent it is trying to obtain. That group is exactly what a carelessly
 * trimmed allowlist drops, and nothing else in the suite would notice.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TermsAcceptanceGateFilterTest {

  @Mock private BackendApiClient backendApiClient;
  @Mock private FilterChain filterChain;

  private TermsAcceptanceGateFilter filter;

  @BeforeEach
  void setUp() {
    // No active profile: the production path. The `test` profile stands the filter down entirely.
    filter = new TermsAcceptanceGateFilter(backendApiClient, new MockEnvironment());
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "member", "n/a", AuthorityUtils.createAuthorityList("ROLE_KRT_MEMBER")));
  }

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  /** A user without consent is routed to the consent page. */
  @Test
  void redirectsAUserWithoutConsent() throws Exception {
    stubStatus(false);

    MockHttpServletResponse response = invoke("/missions");

    assertThat(response.getRedirectedUrl()).isEqualTo("/terms/accept");
    verify(filterChain, never()).doFilter(any(), any());
  }

  /**
   * An AJAX caller gets a status plus a header it can act on, never a 302.
   *
   * <p>This is the tab that was already open when a new wording deployed — the moment the feature
   * first affects anyone. A redirect fails <em>silently</em> there: a fragment swap bails on {@code
   * res.redirected} with only a dev warning and the section stops updating, while a write follows
   * the redirect, receives the consent page as 200 HTML and shows a generic error toast.
   */
  @Test
  void signalsTheGateToAnAjaxCallerInsteadOfRedirecting() throws Exception {
    stubStatus(false);
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/missions/x/ajax");
    request.setRequestURI("/missions/x/ajax");
    request.addHeader("X-Requested-With", "XMLHttpRequest");
    request.setSession(new org.springframework.mock.web.MockHttpSession());
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getHeader("X-Terms-Acceptance-Required")).isEqualTo("/terms/accept");
    assertThat(response.getRedirectedUrl()).isNull();
    verify(filterChain, never()).doFilter(any(), any());
  }

  /** A consenting user passes through. */
  @Test
  void letsAConsentingUserThrough() throws Exception {
    stubStatus(true);

    assertThat(invoke("/missions").getRedirectedUrl()).isNull();
    verify(filterChain).doFilter(any(), any());
  }

  /**
   * The documents a person must be able to read before agreeing are never redirected — nor is the
   * consent page itself (which would loop) or logout (which would trap a user who declines).
   */
  @Test
  void neverBlocksTheDocumentsAUserMustBeAbleToRead() throws Exception {
    stubStatus(false);

    for (String path :
        new String[] {"/terms", "/privacy", "/impressum", "/terms/accept", "/logout"}) {
      assertThat(invoke(path).getRedirectedUrl()).as(path).isNull();
    }
    verify(filterChain, times(5)).doFilter(any(), any());
  }

  /** Static assets skip the filter, so a page load is not one backend read per stylesheet. */
  @Test
  void skipsStaticAssets() throws Exception {
    stubStatus(false);

    assertThat(invoke("/css/styles.css").getRedirectedUrl()).isNull();
    verify(backendApiClient, never()).get(any(String.class), eq(TermsStatusDto.class));
  }

  /** An anonymous visitor has no account to record consent against and is left alone. */
  @Test
  void ignoresAnAnonymousVisitor() throws Exception {
    SecurityContextHolder.clearContext();

    assertThat(invoke("/missions").getRedirectedUrl()).isNull();
    verify(backendApiClient, never()).get(any(String.class), eq(TermsStatusDto.class));
  }

  /**
   * An unreadable backend lets the request through. Failing closed here would mean a backend hiccup
   * strands everyone on the consent page — where accepting cannot work either, since recording
   * consent needs the same backend. The real boundary is in the backend, so this costs no security.
   */
  @Test
  void failsOpenWhenTheBackendIsUnreachable() throws Exception {
    when(backendApiClient.get(any(String.class), eq(TermsStatusDto.class)))
        .thenThrow(new BackendServiceException("backend down", null, 503));

    assertThat(invoke("/missions").getRedirectedUrl()).isNull();
    verify(filterChain).doFilter(any(), any());
  }

  /**
   * A positive verdict is cached for the session, so a burst of requests costs one backend read —
   * but the cache is time-bounded, which is what lets a wording change re-prompt a session that may
   * live for thirty days.
   */
  @Test
  void cachesAPositiveVerdictForTheSession() throws Exception {
    stubStatus(true);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/missions");
    request.setRequestURI("/missions");
    request.setSession(new org.springframework.mock.web.MockHttpSession());

    filter.doFilter(request, new MockHttpServletResponse(), filterChain);
    filter.doFilter(request, new MockHttpServletResponse(), filterChain);

    verify(backendApiClient, times(1)).get(any(String.class), eq(TermsStatusDto.class));
    assertThat(TermsAcceptanceGateFilter.RECHECK_MILLIS)
        .as("the cache must expire far inside the 30-day session lifetime")
        .isLessThan(java.time.Duration.ofHours(1).toMillis());
  }

  /**
   * A cached "not accepted" is visible to {@code BackendRoleSyncFilter}, which uses it to skip a
   * {@code /api/v1/users/me} call the consent gate would refuse anyway — one futile round trip per
   * non-static request, for everyone at once, right after a wording change.
   */
  @Test
  void reportsAFreshNegativeVerdictSoTheRoleSyncCanSkipItsDoomedCall() throws Exception {
    stubStatus(false);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/missions");
    request.setRequestURI("/missions");
    request.setSession(new org.springframework.mock.web.MockHttpSession());

    assertThat(TermsAcceptanceGateFilter.consentKnownMissing(request)).isFalse();
    filter.doFilter(request, new MockHttpServletResponse(), filterChain);

    assertThat(TermsAcceptanceGateFilter.consentKnownMissing(request)).isTrue();
  }

  /**
   * Only a cached negative counts. An unknown verdict must report false so the sync still runs —
   * this may skip work already known to be pointless, never work that might succeed.
   */
  @Test
  void reportsNothingKnownForASessionThatWasNeverChecked() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/missions");
    request.setSession(new org.springframework.mock.web.MockHttpSession());

    assertThat(TermsAcceptanceGateFilter.consentKnownMissing(request)).isFalse();
  }

  /** Recording consent discards the verdict at once, rather than leaving it stale until it ages. */
  @Test
  void clearingTheVerdictMakesTheNextRequestReReadIt() throws Exception {
    stubStatus(false);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/missions");
    request.setRequestURI("/missions");
    request.setSession(new org.springframework.mock.web.MockHttpSession());
    filter.doFilter(request, new MockHttpServletResponse(), filterChain);
    assertThat(TermsAcceptanceGateFilter.consentKnownMissing(request)).isTrue();

    TermsAcceptanceGateFilter.clearCachedVerdict(request);

    assertThat(TermsAcceptanceGateFilter.consentKnownMissing(request)).isFalse();
  }

  /** Stubs the backend consent status. */
  private void stubStatus(boolean accepted) {
    when(backendApiClient.get(any(String.class), eq(TermsStatusDto.class)))
        .thenReturn(new TermsStatusDto(accepted, "v1"));
  }

  /**
   * Runs the filter against the given path with a fresh session.
   *
   * @param path the request path
   * @return the response the filter produced
   * @throws Exception if the filter throws
   */
  private MockHttpServletResponse invoke(String path) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    request.setRequestURI(path);
    request.setSession(new org.springframework.mock.web.MockHttpSession());
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, filterChain);
    return response;
  }
}
