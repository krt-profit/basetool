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

package de.greluc.krt.profit.basetool.frontend.kernel.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;

import de.greluc.krt.profit.basetool.frontend.kernel.security.KeycloakBackchannelProperties;
import de.greluc.krt.profit.basetool.frontend.kernel.security.KeycloakBackchannelTls;
import io.micrometer.observation.ObservationRegistry;
import java.security.KeyStore;
import java.time.Duration;
import java.time.Instant;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ssl.DefaultSslBundleRegistry;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.ssl.SslStoreBundle;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;

/**
 * Verifies the token client's TLS trust on the internal Keycloak back-channel against a real HTTPS
 * server (REQ-SEC-087): the {@code keycloak-trust} bundle applies only when a back-channel is
 * configured, and the hostname check stays on.
 */
class KeycloakBackchannelTokenTrustTest {

  /** A certificate whose name matches what the tests dial. */
  private static final HeldCertificate LOCALHOST_CERT =
      new HeldCertificate.Builder().addSubjectAlternativeName("localhost").build();

  /** A certificate for the internal service name, which the tests reach as {@code localhost}. */
  private static final HeldCertificate KEYCLOAK_CERT =
      new HeldCertificate.Builder().addSubjectAlternativeName("keycloak").build();

  private static final String BACKCHANNEL = "https://keycloak:18443/auth/realms/iri";

  @Test
  void withABackchannelThePinnedCertificateIsTrusted() throws Exception {
    try (MockWebServer keycloak = httpsServer(LOCALHOST_CERT)) {
      OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> client =
          config(pinning(LOCALHOST_CERT), BACKCHANNEL).oauthRefreshTokenResponseClient();

      assertThat(
              client
                  .getTokenResponse(refreshRequest(keycloak.url("/token").toString()))
                  .getAccessToken()
                  .getTokenValue())
          .isEqualTo("internal-access-token");
    }
  }

  @Test
  void withABackchannelAMisnamedCertificateIsRefused() throws Exception {
    try (MockWebServer keycloak = httpsServer(KEYCLOAK_CERT)) {
      OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> client =
          config(pinning(KEYCLOAK_CERT), BACKCHANNEL).oauthRefreshTokenResponseClient();
      OAuth2RefreshTokenGrantRequest request = refreshRequest(keycloak.url("/token").toString());

      assertThatExceptionOfType(OAuth2AuthorizationException.class)
          .isThrownBy(() -> client.getTokenResponse(request));
    }
  }

  @Test
  void withoutABackchannelTheBundleIsNotApplied() throws Exception {
    try (MockWebServer keycloak = httpsServer(LOCALHOST_CERT)) {
      OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> client =
          config(pinning(LOCALHOST_CERT), null).oauthRefreshTokenResponseClient();
      OAuth2RefreshTokenGrantRequest request = refreshRequest(keycloak.url("/token").toString());

      assertThatExceptionOfType(OAuth2AuthorizationException.class)
          .isThrownBy(() -> client.getTokenResponse(request));
    }
  }

  @Test
  void theTlsContextNeedsBothTheBackchannelAndTheBundle() throws Exception {
    assertThat(config(pinning(LOCALHOST_CERT), null).keycloakBackchannelSslContext()).isNull();
    assertThat(config(new DefaultSslBundleRegistry(), BACKCHANNEL).keycloakBackchannelSslContext())
        .isNull();
    assertThat(config(pinning(LOCALHOST_CERT), BACKCHANNEL).keycloakBackchannelSslContext())
        .isNotNull();
  }

  /**
   * An HTTPS server presenting the given certificate and answering one token grant.
   *
   * @param certificate the server certificate
   * @return the started server
   * @throws Exception when the server cannot start
   */
  private static MockWebServer httpsServer(HeldCertificate certificate) throws Exception {
    MockWebServer server = new MockWebServer();
    server.useHttps(
        new HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(),
        false);
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json;charset=UTF-8")
            .setBody(
                "{\"access_token\":\"internal-access-token\",\"token_type\":\"Bearer\","
                    + "\"expires_in\":300}"));
    server.start();
    return server;
  }

  /**
   * A bundle registry whose {@code keycloak-trust} bundle pins the given certificate.
   *
   * @param pinned the certificate to trust
   * @return the registry
   * @throws Exception when the truststore cannot be built
   */
  private static SslBundles pinning(HeldCertificate pinned) throws Exception {
    KeyStore truststore = KeyStore.getInstance("PKCS12");
    truststore.load(null, null);
    truststore.setCertificateEntry("keycloak", pinned.certificate());
    DefaultSslBundleRegistry registry = new DefaultSslBundleRegistry();
    registry.registerBundle(
        KeycloakBackchannelTls.KEYCLOAK_TRUST_BUNDLE,
        SslBundle.of(SslStoreBundle.of(null, null, truststore)));
    return registry;
  }

  /**
   * Builds the real {@link WebClientConfig} with light test doubles.
   *
   * @param sslBundles the bundles the config resolves the Keycloak trust from
   * @param backchannel the configured back-channel, or {@code null} for none
   * @return the config under test
   */
  private static WebClientConfig config(SslBundles sslBundles, String backchannel) {
    return new WebClientConfig(
        new AppBackendProperties("https://backend:11261"),
        new AppHttpProperties(
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            Duration.ofSeconds(120),
            Duration.ofSeconds(3),
            Duration.ofSeconds(3),
            AppHttpProperties.BackendProtocol.H2,
            20,
            AppHttpProperties.BackendCodec.CBOR,
            false),
        mock(WebClientLoggingFilter.class),
        mock(ActiveSquadronRelayFilter.class),
        mock(UserLocaleRelayFilter.class),
        new ClientIpRelayFilter(),
        mock(Environment.class),
        sslBundles,
        ObservationRegistry.NOOP,
        new KeycloakBackchannelProperties(backchannel));
  }

  /**
   * Builds a refresh-token grant request whose registration points at the given token URI.
   *
   * @param tokenUri the token endpoint
   * @return the grant request
   */
  private static OAuth2RefreshTokenGrantRequest refreshRequest(String tokenUri) {
    ClientRegistration registration =
        ClientRegistration.withRegistrationId("keycloak")
            .clientId("basetool-frontend")
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
            .scope("openid", "profile")
            .authorizationUri(
                "https://profit-base.online/auth/realms/iri/protocol/openid-connect/auth")
            .tokenUri(tokenUri)
            .build();
    return new OAuth2RefreshTokenGrantRequest(
        registration,
        new OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER,
            "old-access-token",
            Instant.now().minusSeconds(600),
            Instant.now().minusSeconds(300)),
        new OAuth2RefreshToken("current-refresh-token", Instant.now().minusSeconds(600)));
  }
}
