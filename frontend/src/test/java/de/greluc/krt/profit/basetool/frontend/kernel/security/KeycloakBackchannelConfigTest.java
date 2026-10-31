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
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.AppHttpProperties;
import java.time.Duration;
import java.util.Set;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.boot.ssl.DefaultSslBundleRegistry;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Unit tests for {@link KeycloakBackchannelConfig} (REQ-SEC-087): the registration keeps the
 * browser-facing endpoints and the issuer public and moves token, JWKS and userinfo to the internal
 * realm base, and nothing is registered while the back-channel is blank.
 */
class KeycloakBackchannelConfigTest {

  private static final String ISSUER = "https://profit-base.online/auth/realms/basetool";
  private static final String INTERNAL = "https://keycloak:18443/auth/realms/basetool";

  private static final String PROPERTY = "spring.security.oauth2.client.";

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(OAuth2ClientAutoConfiguration.class))
          .withUserConfiguration(KeycloakBackchannelConfig.class)
          .withBean(SslBundles.class, DefaultSslBundleRegistry::new)
          .withBean(AppHttpProperties.class, KeycloakBackchannelConfigTest::httpProperties)
          .withPropertyValues(
              PROPERTY + "registration.keycloak.client-id=basetool-frontend",
              PROPERTY + "registration.keycloak.client-authentication-method=none",
              PROPERTY + "registration.keycloak.authorization-grant-type=authorization_code",
              PROPERTY
                  + "registration.keycloak.redirect-uri={baseUrl}/login/oauth2/code/{registrationId}",
              PROPERTY + "registration.keycloak.scope=openid,profile,email",
              PROPERTY + "provider.keycloak.user-name-attribute=preferred_username");

  @Test
  void aBlankBackchannelLeavesSpringBootsRegistrationInPlace() {
    runner
        .withPropertyValues(
            "app.security.keycloak-backchannel.uri=",
            PROPERTY
                + "provider.keycloak.authorization-uri="
                + ISSUER
                + "/protocol/openid-connect/auth",
            PROPERTY + "provider.keycloak.token-uri=" + ISSUER + "/protocol/openid-connect/token")
        .withBean(KeycloakBackchannelProperties.class, () -> new KeycloakBackchannelProperties(""))
        .run(context -> {
          assertThat(
                  context
                      .getBean(ClientRegistrationRepository.class)
                      .findByRegistrationId(FrontendClientAuthenticationConfig.REGISTRATION_ID)
                      .getProviderDetails()
                      .getTokenUri())
              .isEqualTo(ISSUER + "/protocol/openid-connect/token");
          assertThat(context).doesNotHaveBean(JwtDecoderFactory.class);
          assertThat(context).doesNotHaveBean(OAuth2UserService.class);
        });
  }

  @Test
  void aBackchannelReplacesTheRegistrationAndStillBindsTheClientProperties() {
    runner
        .withPropertyValues(
            "app.security.keycloak-backchannel.uri=" + INTERNAL,
            PROPERTY + "provider.keycloak.issuer-uri=" + ISSUER)
        .withBean(
            KeycloakBackchannelProperties.class, () -> new KeycloakBackchannelProperties(INTERNAL))
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(OAuth2ClientProperties.class);
          ClientRegistration registration =
              context
                  .getBean(ClientRegistrationRepository.class)
                  .findByRegistrationId(FrontendClientAuthenticationConfig.REGISTRATION_ID);
          assertThat(registration).isNotNull();
          assertThat(registration.getClientId()).isEqualTo("basetool-frontend");
          assertThat(registration.getProviderDetails().getIssuerUri()).isEqualTo(ISSUER);
          assertThat(registration.getProviderDetails().getTokenUri())
              .isEqualTo(INTERNAL + "/protocol/openid-connect/token");
          assertThat(context).hasSingleBean(JwtDecoderFactory.class);
          assertThat(context).hasSingleBean(OAuth2UserService.class);
        });
  }

  @Test
  void theRegistrationSplitsPublicAndInternalEndpoints() {
    ClientRegistration registration =
        KeycloakBackchannelConfig.keycloakRegistration(clientProperties(), INTERNAL);

    ClientRegistration.ProviderDetails provider = registration.getProviderDetails();
    assertThat(provider.getIssuerUri()).isEqualTo(ISSUER);
    assertThat(provider.getAuthorizationUri()).isEqualTo(ISSUER + "/protocol/openid-connect/auth");
    assertThat(provider.getConfigurationMetadata())
        .containsEntry("end_session_endpoint", ISSUER + "/protocol/openid-connect/logout");
    assertThat(provider.getTokenUri()).isEqualTo(INTERNAL + "/protocol/openid-connect/token");
    assertThat(provider.getJwkSetUri()).isEqualTo(INTERNAL + "/protocol/openid-connect/certs");
    assertThat(provider.getUserInfoEndpoint().getUri())
        .isEqualTo(INTERNAL + "/protocol/openid-connect/userinfo");
    assertThat(provider.getUserInfoEndpoint().getUserNameAttributeName())
        .isEqualTo("preferred_username");
  }

  @Test
  void theRegistrationKeepsTheClientSettings() {
    ClientRegistration registration =
        KeycloakBackchannelConfig.keycloakRegistration(clientProperties(), INTERNAL);

    assertThat(registration.getClientId()).isEqualTo("basetool-frontend");
    assertThat(registration.getClientAuthenticationMethod())
        .isEqualTo(ClientAuthenticationMethod.NONE);
    assertThat(registration.getAuthorizationGrantType())
        .isEqualTo(AuthorizationGrantType.AUTHORIZATION_CODE);
    assertThat(registration.getRedirectUri())
        .isEqualTo("{baseUrl}/login/oauth2/code/{registrationId}");
    assertThat(registration.getScopes()).containsExactlyInAnyOrder("openid", "profile", "email");
  }

  @Test
  void aTrailingSlashOnTheIssuerIsDropped() {
    OAuth2ClientProperties properties = clientProperties();
    properties.getProvider().get("keycloak").setIssuerUri(ISSUER + "/");

    ClientRegistration registration =
        KeycloakBackchannelConfig.keycloakRegistration(properties, INTERNAL);

    assertThat(registration.getProviderDetails().getIssuerUri()).isEqualTo(ISSUER);
  }

  @Test
  void aMissingIssuerFailsTheStart() {
    OAuth2ClientProperties properties = clientProperties();
    properties.getProvider().get("keycloak").setIssuerUri(null);

    assertThatIllegalStateException()
        .isThrownBy(() -> KeycloakBackchannelConfig.keycloakRegistration(properties, INTERNAL))
        .withMessageContaining("issuer-uri");
  }

  @Test
  void theIdTokenDecoderFetchesTheJwksFromTheInternalUriOnce() throws Exception {
    try (MockWebServer keycloak = new MockWebServer()) {
      keycloak.enqueue(
          new MockResponse()
              .setResponseCode(200)
              .addHeader("Content-Type", "application/json")
              .setBody("{\"keys\":[]}"));
      keycloak.start();
      String realm = keycloak.url("/auth/realms/basetool").toString();
      ClientRegistration registration =
          KeycloakBackchannelConfig.keycloakRegistration(clientProperties(), realm);
      JwtDecoderFactory<ClientRegistration> factory =
          new KeycloakBackchannelConfig()
              .idTokenDecoderFactory(new DefaultSslBundleRegistry(), httpProperties());

      JwtDecoder decoder = factory.createDecoder(registration);

      assertThat(factory.createDecoder(registration)).isSameAs(decoder);
      assertThatThrownBy(() -> decoder.decode(UNSIGNED_RS256_TOKEN))
          .isInstanceOf(JwtException.class);
      RecordedRequest jwks = keycloak.takeRequest();
      assertThat(jwks.getPath()).isEqualTo("/auth/realms/basetool/protocol/openid-connect/certs");
    }
  }

  /** A syntactically valid RS256 token whose key id no JWKS will hold. */
  private static final String UNSIGNED_RS256_TOKEN =
      "eyJhbGciOiJSUzI1NiIsImtpZCI6Im5vbmUifQ"
          + ".eyJzdWIiOiJ4IiwiaXNzIjoiaHR0cHM6Ly9wcm9maXQtYmFzZS5vbmxpbmUvYXV0aC9yZWFsbXMvaXJpIn0"
          + ".c2lnbmF0dXJl";

  private static OAuth2ClientProperties clientProperties() {
    OAuth2ClientProperties properties = new OAuth2ClientProperties();
    OAuth2ClientProperties.Registration registration = new OAuth2ClientProperties.Registration();
    registration.setClientId("basetool-frontend");
    registration.setClientAuthenticationMethod(ClientAuthenticationMethod.NONE.getValue());
    registration.setAuthorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE.getValue());
    registration.setRedirectUri("{baseUrl}/login/oauth2/code/{registrationId}");
    registration.setScope(Set.of("openid", "profile", "email"));
    properties.getRegistration().put("keycloak", registration);
    OAuth2ClientProperties.Provider provider = new OAuth2ClientProperties.Provider();
    provider.setIssuerUri(ISSUER);
    provider.setUserNameAttribute("preferred_username");
    properties.getProvider().put("keycloak", provider);
    return properties;
  }

  private static AppHttpProperties httpProperties() {
    return new AppHttpProperties(
        Duration.ofSeconds(2),
        Duration.ofSeconds(5),
        Duration.ofSeconds(120),
        Duration.ofSeconds(3),
        Duration.ofSeconds(3),
        AppHttpProperties.BackendProtocol.H2,
        20,
        AppHttpProperties.BackendCodec.CBOR,
        false);
  }
}
