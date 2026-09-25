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

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

/**
 * Pins how the frontend authenticates to Keycloak's token endpoint (REQ-SEC-069, ADR-0001): the
 * client type follows the secret, and PKCE is on either way.
 *
 * <p>Runs Spring Boot's real OAuth2 client auto-configuration with the properties {@code
 * application.yml} sets, so what is asserted is the {@link ClientRegistration} the login and the
 * token refresh actually use — not the properties they were built from. A regression to "the secret
 * is set but never sent", or a confidential client that silently stopped sending PKCE (which
 * Keycloak's {@code S256} requirement would turn into a failed login for everyone), breaks here.
 */
class FrontendClientAuthenticationConfigTest {

  /**
   * The context under test: Boot's OAuth2 client, this application's selector, static endpoints.
   */
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(OAuth2ClientAutoConfiguration.class))
          .withUserConfiguration(FrontendClientAuthenticationConfig.class)
          .withPropertyValues(
              "spring.security.oauth2.client.registration.keycloak.client-id=basetool-frontend",
              "spring.security.oauth2.client.registration.keycloak.client-authentication-method=none",
              "spring.security.oauth2.client.registration.keycloak.authorization-grant-type=authorization_code",
              "spring.security.oauth2.client.registration.keycloak.redirect-uri={baseUrl}/login/oauth2/code/{registrationId}",
              "spring.security.oauth2.client.registration.keycloak.scope=openid,profile,email,roles",
              "spring.security.oauth2.client.provider.keycloak.authorization-uri=https://kc.example.test/auth",
              "spring.security.oauth2.client.provider.keycloak.token-uri=https://kc.example.test/token",
              "spring.security.oauth2.client.provider.keycloak.jwk-set-uri=https://kc.example.test/certs",
              "spring.security.oauth2.client.provider.keycloak.user-info-uri=https://kc.example.test/userinfo",
              "spring.security.oauth2.client.provider.keycloak.user-name-attribute=preferred_username");

  @Test
  void withASecretTheFrontendIsAConfidentialClientThatStillSendsPkce() {
    contextRunner
        .withPropertyValues(
            "spring.security.oauth2.client.registration.keycloak.client-secret=test-client-secret")
        .run(
            context -> {
              ClientRegistration registration =
                  context
                      .getBean(ClientRegistrationRepository.class)
                      .findByRegistrationId("keycloak");

              assertThat(registration.getClientAuthenticationMethod())
                  .isEqualTo(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
              assertThat(registration.getClientSecret()).isEqualTo("test-client-secret");
              assertThat(registration.getClientSettings().isRequireProofKey())
                  .as("PKCE stays on for the confidential client: Keycloak requires S256")
                  .isTrue();
            });
  }

  @Test
  void withoutASecretTheFrontendStaysThePublicClientItAlwaysWas() {
    contextRunner.run(
        context -> {
          ClientRegistration registration =
              context.getBean(ClientRegistrationRepository.class).findByRegistrationId("keycloak");

          assertThat(registration.getClientAuthenticationMethod())
              .isEqualTo(ClientAuthenticationMethod.NONE);
          assertThat(registration.getClientSecret()).isEmpty();
          assertThat(registration.getClientSettings().isRequireProofKey()).isTrue();
        });
  }

  @Test
  void aBlankSecretIsNoSecret() {
    contextRunner
        .withPropertyValues("spring.security.oauth2.client.registration.keycloak.client-secret=  ")
        .run(
            context ->
                assertThat(
                        context
                            .getBean(ClientRegistrationRepository.class)
                            .findByRegistrationId("keycloak")
                            .getClientAuthenticationMethod())
                    .isEqualTo(ClientAuthenticationMethod.NONE));
  }

  @Test
  void aMissingRegistrationIsLeftAlone() {
    assertThat(FrontendClientAuthenticationConfig.select(null)).isFalse();
    OAuth2ClientProperties.Registration registration = new OAuth2ClientProperties.Registration();
    registration.setClientSecret("s");
    assertThat(FrontendClientAuthenticationConfig.select(registration)).isTrue();
  }
}
