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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.identity.model.RegistrationStatusDto;
import de.greluc.krt.profit.basetool.frontend.identity.model.TermsStatusDto;
import de.greluc.krt.profit.basetool.frontend.identity.model.UserDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.ReauthenticationRequiredException;
import jakarta.servlet.FilterChain;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import tools.jackson.databind.json.JsonMapper;

/** Exercises session-loss handoffs at the actual role and consent filter boundaries. */
class BackendRoleSyncReauthenticationTest {

  private static final String CONTEXT = "/basetool";
  private static final String LOGIN = CONTEXT + "/oauth2/authorization/keycloak";
  private static final String USERS_ME = "/api/v1/users/me";
  private static final String REGISTRATION_STATUS = "/api/v1/users/me/registration-status";

  private BackendApiClient backend;
  private BackendRoleSyncFilter filter;
  private FilterChain chain;

  @BeforeEach
  void setUp() {
    backend = mock(BackendApiClient.class);
    filter = new BackendRoleSyncFilter(backend);
    chain = mock(FilterChain.class);
    var authorities = List.of(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"));
    var principal =
        new DefaultOidcUser(
            authorities,
            OidcIdToken.withTokenValue("synthetic-token").subject("synthetic-member").build());
    SecurityContextHolder.getContext()
        .setAuthentication(new OAuth2AuthenticationToken(principal, authorities, "keycloak"));
  }

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @ParameterizedTest
  @MethodSource("lostSessionCases")
  void refreshHandsSessionLossToTheBrowser(Read read, Format format, Failure failure)
      throws Exception {
    MockHttpServletRequest request = request(read, "/orders");
    format.apply(request);
    failRead(read, failure.create());
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, chain);

    assertHandoff(format, response);
    assertNoSyncStamp(request);
    verify(chain, never()).doFilter(any(), any());
  }

  @ParameterizedTest
  @MethodSource("cleanupCases")
  void gateExemptPathsContinueWithoutAReauthenticationLoop(Read read, String path)
      throws Exception {
    MockHttpServletRequest request = request(read, path);
    failRead(read, Failure.WRAPPED.create());
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, chain);

    verify(chain).doFilter(request, response);
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getRedirectedUrl()).isNull();
    assertThat(response.getHeader("X-Reauthenticate")).isNull();
    assertThat(response.getContentAsString()).isEmpty();
    assertNoSyncStamp(request);
  }

  @ParameterizedTest
  @EnumSource(Read.class)
  void webSocketUpgradeRetainsItsSeparateSubscriptionPolicy(Read read) throws Exception {
    MockHttpServletRequest request = request(read, "/ws/sync");
    request.addHeader("Upgrade", "websocket");
    failRead(read, Failure.RAW_OAUTH.create());
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, chain);

    verify(chain).doFilter(request, response);
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getRedirectedUrl()).isNull();
    assertThat(response.getContentAsString()).isEmpty();
    assertNoSyncStamp(request);
  }

  @ParameterizedTest
  @EnumSource(Read.class)
  void ordinaryBackendFailureKeepsTheRefreshRetryable(Read read) throws Exception {
    MockHttpServletRequest request = request(read, "/orders");
    failRead(read, new BackendServiceException("synthetic backend outage", null, 503));
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, chain);

    verify(chain).doFilter(request, response);
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getHeader("X-Reauthenticate")).isNull();
    assertNoSyncStamp(request);
  }

  @ParameterizedTest
  @EnumSource(Format.class)
  void consentFilterUsesTheSameHandoffForWrappedSessionLoss(Format format) throws Exception {
    MockHttpServletRequest request = request(Read.ROLES, "/orders");
    format.apply(request);
    when(backend.get("/api/v1/terms/status", TermsStatusDto.class))
        .thenThrow(Failure.WRAPPED.create());
    MockHttpServletResponse response = new MockHttpServletResponse();
    var consent = new TermsAcceptanceGateFilter(backend, new MockEnvironment());

    consent.doFilter(request, response, chain);

    assertHandoff(format, response);
    assertThat(request.getSession().getAttribute(TermsAcceptanceGateFilter.SESSION_ACCEPTED)).isNull();
    assertThat(request.getSession().getAttribute(TermsAcceptanceGateFilter.SESSION_CHECKED_AT))
        .isNull();
    verify(chain, never()).doFilter(any(), any());
  }

  @Test
  void downstreamSessionLossIsNotCaughtOrReplayedByTheRefreshBoundary() throws Exception {
    MockHttpServletRequest request = request(Read.ROLES, "/logout");
    request
        .getSession()
        .setAttribute(BackendRoleSyncFilter.ROLES_SYNCED_AT_FLAG, System.currentTimeMillis());
    MockHttpServletResponse response = new MockHttpServletResponse();
    var failure = new ReauthenticationRequiredException("downstream session loss", null);
    doThrow(failure).when(chain).doFilter(request, response);

    assertThatThrownBy(() -> filter.doFilter(request, response, chain)).isSameAs(failure);

    verify(chain).doFilter(request, response);
    assertThat(response.getRedirectedUrl()).isNull();
  }

  private MockHttpServletRequest request(Read read, String path) {
    var request = new MockHttpServletRequest("GET", CONTEXT + path);
    request.setContextPath(CONTEXT);
    if (read == Read.ROLES) {
      request.getSession().setAttribute(BackendRoleSyncFilter.APPROVAL_STATE_FLAG, "ACTIVE");
    } else {
      request.getSession();
    }
    return request;
  }

  private void failRead(Read read, RuntimeException failure) {
    switch (read) {
      case APPROVAL ->
          when(backend.get(REGISTRATION_STATUS, RegistrationStatusDto.class)).thenThrow(failure);
      case ROLES -> when(backend.get(USERS_ME, UserDto.class)).thenThrow(failure);
    }
  }

  private static void assertNoSyncStamp(MockHttpServletRequest request) {
    assertThat(request.getSession().getAttribute(BackendRoleSyncFilter.ROLES_SYNCED_AT_FLAG)).isNull();
    assertThat(request.getSession().getAttribute(BackendRoleSyncFilter.APPROVAL_CHECKED_AT_FLAG))
        .isNull();
    assertThat(request.getSession().getAttribute(BackendRoleSyncFilter.SYNCED_AUTHORITIES_FLAG))
        .isNull();
  }

  private static void assertHandoff(Format format, MockHttpServletResponse response)
      throws Exception {
    switch (format) {
      case NAVIGATION -> {
        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo(LOGIN);
      }
      case SSE -> {
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).startsWith("text/event-stream");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-cache");
        assertThat(response.getContentAsString()).isEqualTo("event: reauth\ndata: " + LOGIN + "\n\n");
        assertThat(response.getRedirectedUrl()).isNull();
      }
      case XHR, JSON, FETCH -> {
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getRedirectedUrl()).isNull();
        assertThat(response.getHeader("X-Reauthenticate")).isEqualTo(LOGIN);
        assertThat(response.getContentType()).startsWith("application/json");
        var body = JsonMapper.builder().build().readTree(response.getContentAsString());
        assertThat(body.path("code").asString()).isEqualTo("REAUTH_REQUIRED");
        assertThat(body.path("status").asInt()).isEqualTo(401);
        assertThat(body.path("reauthenticate").asBoolean()).isTrue();
        assertThat(body.path("location").asString()).isEqualTo(LOGIN);
      }
    }
  }

  private static Stream<Arguments> lostSessionCases() {
    return Stream.of(Read.values())
        .flatMap(read ->
            Stream.of(Format.values())
                .flatMap(format ->
                    Stream.of(Failure.values()).map(failure -> Arguments.of(read, format, failure))));
  }

  private static Stream<Arguments> cleanupCases() {
    return Stream.of(Read.values())
        .flatMap(read ->
            Stream.of(
                    "/logout",
                    "/oauth2/authorization/keycloak",
                    "/login/oauth2/code/keycloak",
                    "/error",
                    "/actuator/health",
                    "/privacy",
                    "/licenses")
                .map(path -> Arguments.of(read, path)));
  }

  /** The independent backend reads that can encounter session loss. */
  private enum Read {
    APPROVAL,
    ROLES
  }

  /** Browser request shapes with distinct session-loss response contracts. */
  private enum Format {
    NAVIGATION,
    XHR,
    JSON,
    FETCH,
    SSE;

    void apply(MockHttpServletRequest request) {
      switch (this) {
        case NAVIGATION -> request.addHeader("Sec-Fetch-Mode", "navigate");
        case XHR -> request.addHeader("X-Requested-With", "XMLHttpRequest");
        case JSON -> request.addHeader("Accept", "application/json");
        case FETCH -> request.addHeader("Sec-Fetch-Mode", "cors");
        case SSE -> request.addHeader("Accept", "text/event-stream");
      }
    }
  }

  /** Direct, asynchronously wrapped and raw OAuth2 session-loss signals. */
  private enum Failure {
    DIRECT,
    WRAPPED,
    RAW_OAUTH,
    BACKEND_WRAPPED;

    RuntimeException create() {
      var reauth = new ReauthenticationRequiredException("synthetic session loss", null);
      return switch (this) {
        case DIRECT -> reauth;
        case WRAPPED -> new IllegalStateException(new CompletionException(reauth));
        case RAW_OAUTH ->
            new CompletionException(
                new ClientAuthorizationException(new OAuth2Error("invalid_grant"), "keycloak"));
        case BACKEND_WRAPPED -> new BackendServiceException("wrapped session loss", reauth, 503);
      };
    }
  }
}
