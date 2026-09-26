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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizationContext;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Verifies that a request parameter named {@code scope} never reaches the refresh-token grant
 * (REQ-SEC-012): Spring's default mapper leaks it, while {@link
 * WebClientConfig#NO_REQUEST_DERIVED_ATTRIBUTES} drops it.
 */
class OAuth2ScopeRequestParamLeakTest {

  private static final String REGISTRATION_ID = "keycloak";

  /** Clears any servlet request bound during a test so the thread-local does not leak. */
  @AfterEach
  void clearRequestContext() {
    RequestContextHolder.resetRequestAttributes();
  }

  /** A minimal valid {@code keycloak} authorization-code client registration for the test. */
  private static ClientRegistration keycloakRegistration() {
    return ClientRegistration.withRegistrationId(REGISTRATION_ID)
        .clientId("basetool-frontend")
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri("{baseUrl}/login/oauth2/code/keycloak")
        .authorizationUri("https://kc/realms/iri/protocol/openid-connect/auth")
        .tokenUri("https://kc/realms/iri/protocol/openid-connect/token")
        .build();
  }

  /**
   * Drives {@code DefaultOAuth2AuthorizedClientManager.authorize(...)} down its reauthorize branch
   * with a {@code scope} request parameter and captures the context handed to the provider.
   *
   * @param applyFixMapper whether to install {@link WebClientConfig#NO_REQUEST_DERIVED_ATTRIBUTES}
   * @param scopeValue the simulated {@code scope} parameter value
   * @return the authorization context the provider received, never {@code null}
   */
  private static OAuth2AuthorizationContext captureContext(
      boolean applyFixMapper, String scopeValue) {
    ClientRegistration registration = keycloakRegistration();
    ClientRegistrationRepository registrations = mock(ClientRegistrationRepository.class);
    when(registrations.findByRegistrationId(REGISTRATION_ID)).thenReturn(registration);

    Authentication principal = new TestingAuthenticationToken("u-1", "n/a");
    OAuth2AccessToken accessToken =
        new OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER,
            "access-token",
            Instant.now(),
            Instant.now().plusSeconds(300));
    OAuth2AuthorizedClient existing =
        new OAuth2AuthorizedClient(registration, principal.getName(), accessToken);

    OAuth2AuthorizedClientRepository clients = mock(OAuth2AuthorizedClientRepository.class);
    when(clients.loadAuthorizedClient(eq(REGISTRATION_ID), any(), any())).thenReturn(existing);

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("scope", scopeValue);
    MockHttpServletResponse response = new MockHttpServletResponse();

    OAuth2AuthorizeRequest authorizeRequest =
        OAuth2AuthorizeRequest.withClientRegistrationId(REGISTRATION_ID)
            .principal(principal)
            .attribute(HttpServletRequest.class.getName(), request)
            .attribute(HttpServletResponse.class.getName(), response)
            .build();

    AtomicReference<OAuth2AuthorizationContext> captured = new AtomicReference<>();
    OAuth2AuthorizedClientProvider capturing =
        context -> {
          captured.set(context);
          return null;
        };

    DefaultOAuth2AuthorizedClientManager manager =
        new DefaultOAuth2AuthorizedClientManager(registrations, clients);
    manager.setAuthorizedClientProvider(capturing);
    if (applyFixMapper) {
      manager.setContextAttributesMapper(WebClientConfig.NO_REQUEST_DERIVED_ATTRIBUTES);
    }

    manager.authorize(authorizeRequest);
    assertNotNull(captured.get(), "provider must run on the reauthorize path");
    return captured.get();
  }

  @Test
  void springDefaultMapper_leaksScopeRequestParamIntoTheGrant() {
    OAuth2AuthorizationContext context = captureContext(false, "all");
    assertArrayEquals(
        new String[] {"all"},
        context.getAttribute(OAuth2AuthorizationContext.REQUEST_SCOPE_ATTRIBUTE_NAME),
        "baseline: Spring's default mapper copies the request param 'scope' into the OAuth2 grant");
  }

  @Test
  void fixMapper_dropsScopeAll_soNoScopeReachesKeycloak() {
    OAuth2AuthorizationContext context = captureContext(true, "all");
    assertNull(
        context.getAttribute(OAuth2AuthorizationContext.REQUEST_SCOPE_ATTRIBUTE_NAME),
        "a stray scope=all request param must not become the refresh-token grant scope");
  }

  @Test
  void fixMapper_dropsScopeMine_soNoScopeReachesKeycloak() {
    OAuth2AuthorizationContext context = captureContext(true, "mine");
    assertNull(
        context.getAttribute(OAuth2AuthorizationContext.REQUEST_SCOPE_ATTRIBUTE_NAME),
        "a stray scope=mine request param must not become the refresh-token grant scope");
  }
}
