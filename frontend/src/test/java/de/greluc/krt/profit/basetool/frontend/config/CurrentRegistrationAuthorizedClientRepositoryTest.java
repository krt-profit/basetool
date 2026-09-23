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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;

/**
 * Pins the two jobs of {@link CurrentRegistrationAuthorizedClientRepository} (REQ-SEC-069,
 * ADR-0001): the client secret never reaches the session store, and a session stored under the old
 * client type refreshes with the current one — which is what lets the switch from public to
 * confidential happen without signing a single member out.
 */
class CurrentRegistrationAuthorizedClientRepositoryTest {

  private static final String SECRET = "test-client-secret";

  private final Authentication principal = new TestingAuthenticationToken("member", "n/a");
  private final MockHttpServletRequest request = new MockHttpServletRequest();
  private final MockHttpServletResponse response = new MockHttpServletResponse();
  private final HttpSessionOAuth2AuthorizedClientRepository sessionStore =
      new HttpSessionOAuth2AuthorizedClientRepository();

  @Test
  void aSessionStoredWhileThePublicClientRanRefreshesAsTheConfidentialOne() {
    // Stored before the rollout: the public registration, no secret.
    sessionStore.saveAuthorizedClient(
        authorizedClient(registration(ClientAuthenticationMethod.NONE, null)),
        principal,
        request,
        response);
    CurrentRegistrationAuthorizedClientRepository repository =
        repositoryRunning(registration(ClientAuthenticationMethod.CLIENT_SECRET_BASIC, SECRET));

    OAuth2AuthorizedClient loaded = repository.loadAuthorizedClient("keycloak", principal, request);

    assertThat(loaded.getClientRegistration().getClientAuthenticationMethod())
        .isEqualTo(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
    assertThat(loaded.getClientRegistration().getClientSecret()).isEqualTo(SECRET);
    assertThat(loaded.getRefreshToken().getTokenValue())
        .as("the member's own tokens are kept")
        .isEqualTo("refresh-token");
    assertThat(loaded.getPrincipalName()).isEqualTo("member");
  }

  @Test
  void theSecretNeverReachesTheSessionStore() {
    ClientRegistration confidential =
        registration(ClientAuthenticationMethod.CLIENT_SECRET_BASIC, SECRET);
    CurrentRegistrationAuthorizedClientRepository repository = repositoryRunning(confidential);

    repository.saveAuthorizedClient(authorizedClient(confidential), principal, request, response);

    OAuth2AuthorizedClient stored =
        sessionStore.loadAuthorizedClient("keycloak", principal, request);
    assertThat(stored.getClientRegistration().getClientSecret()).isEmpty();
    // And not in what Redis would hold either: the session attribute, serialized as production
    // serializes it.
    Object attribute =
        request
            .getSession()
            .getAttribute(
                HttpSessionOAuth2AuthorizedClientRepository.class.getName()
                    + ".AUTHORIZED_CLIENTS");
    byte[] json =
        new GenericJacksonJsonRedisSerializer(
                RedisSessionConfig.buildSessionJsonMapper(getClass().getClassLoader()))
            .serialize(attribute);
    assertThat(new String(json, StandardCharsets.UTF_8)).doesNotContain(SECRET);
    // While the application still sees it on every read.
    assertThat(
            repository
                .loadAuthorizedClient("keycloak", principal, request)
                .getClientRegistration()
                .getClientSecret())
        .isEqualTo(SECRET);
  }

  @Test
  void anUnknownRegistrationIsReturnedAsStoredAndNothingStoredIsNothing() {
    CurrentRegistrationAuthorizedClientRepository repository =
        repositoryRunning(registration(ClientAuthenticationMethod.NONE, null));

    assertThat((Object) repository.loadAuthorizedClient("keycloak", principal, request)).isNull();

    ClientRegistration other =
        ClientRegistration.withClientRegistration(
                registration(ClientAuthenticationMethod.NONE, null))
            .registrationId("other")
            .build();
    sessionStore.saveAuthorizedClient(authorizedClient(other), principal, request, response);
    OAuth2AuthorizedClient loaded = repository.loadAuthorizedClient("other", principal, request);
    assertThat(loaded.getClientRegistration().getRegistrationId()).isEqualTo("other");
  }

  @Test
  void removeReachesTheStore() {
    CurrentRegistrationAuthorizedClientRepository repository =
        repositoryRunning(registration(ClientAuthenticationMethod.NONE, null));
    repository.saveAuthorizedClient(
        authorizedClient(registration(ClientAuthenticationMethod.NONE, null)),
        principal,
        request,
        response);

    repository.removeAuthorizedClient("keycloak", principal, request, response);

    assertThat((Object) sessionStore.loadAuthorizedClient("keycloak", principal, request)).isNull();
  }

  /**
   * The repository under test, over this test's session store, with the given registration as the
   * one the application runs with now.
   *
   * @param current the current {@code keycloak} registration.
   * @return the repository.
   */
  private CurrentRegistrationAuthorizedClientRepository repositoryRunning(
      ClientRegistration current) {
    return new CurrentRegistrationAuthorizedClientRepository(
        sessionStore, new InMemoryClientRegistrationRepository(current));
  }

  /**
   * The frontend's {@code keycloak} registration in one of its two shapes.
   *
   * @param method the client authentication method.
   * @param secret the client secret, or {@code null}.
   * @return the registration.
   */
  private static ClientRegistration registration(ClientAuthenticationMethod method, String secret) {
    return ClientRegistration.withRegistrationId("keycloak")
        .clientId("basetool-frontend")
        .clientSecret(secret)
        .clientAuthenticationMethod(method)
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
        .scope("openid")
        .authorizationUri("https://kc.example.test/auth")
        .tokenUri("https://kc.example.test/token")
        .jwkSetUri("https://kc.example.test/certs")
        .userNameAttributeName("preferred_username")
        .build();
  }

  /**
   * An authorized client for {@code member} under the given registration.
   *
   * @param registration the registration.
   * @return the client, with an access and a refresh token.
   */
  private static OAuth2AuthorizedClient authorizedClient(ClientRegistration registration) {
    Instant now = Instant.parse("2026-09-23T10:00:00Z");
    return new OAuth2AuthorizedClient(
        registration,
        "member",
        new OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER,
            "access-token",
            now,
            now.plusSeconds(300),
            Collections.unmodifiableSet(Set.of("openid"))),
        new OAuth2RefreshToken("refresh-token", now));
  }
}
