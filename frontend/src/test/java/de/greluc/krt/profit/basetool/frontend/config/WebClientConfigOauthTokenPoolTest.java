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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;

import de.greluc.krt.profit.basetool.frontend.logging.ActiveSquadronRelayFilter;
import de.greluc.krt.profit.basetool.frontend.logging.ClientIpRelayFilter;
import de.greluc.krt.profit.basetool.frontend.logging.UserLocaleRelayFilter;
import de.greluc.krt.profit.basetool.frontend.logging.WebClientLoggingFilter;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;

/**
 * Verifies that the pool-hardened OAuth2 token client (ADR-0115) still round-trips a real {@code
 * refresh_token} grant against a {@link MockWebServer}, parsing the response into an {@link
 * OAuth2AccessTokenResponse}.
 */
class WebClientConfigOauthTokenPoolTest {

  private MockWebServer server;

  /** Starts the fake Keycloak token endpoint before each test. */
  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
  }

  /** Shuts the fake token endpoint down after each test. */
  @AfterEach
  void tearDown() throws Exception {
    server.shutdown();
  }

  /** Builds the real {@link WebClientConfig} with light test doubles for its collaborators. */
  private static WebClientConfig buildConfig() {
    return buildConfig(
        new AppHttpProperties(
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            Duration.ofSeconds(120),
            Duration.ofSeconds(3),
            Duration.ofSeconds(3),
            AppHttpProperties.BackendProtocol.H2,
            20,
            AppHttpProperties.BackendCodec.CBOR,
            false));
  }

  /**
   * Builds the real {@link WebClientConfig} with light test doubles and the given HTTP timeouts.
   *
   * @param httpProperties the timeout settings the config under test runs with
   */
  private static WebClientConfig buildConfig(AppHttpProperties httpProperties) {
    Environment environment = mock(Environment.class);
    return new WebClientConfig(
        new AppBackendProperties("https://backend:11261"),
        httpProperties,
        mock(WebClientLoggingFilter.class),
        mock(ActiveSquadronRelayFilter.class),
        mock(UserLocaleRelayFilter.class),
        new ClientIpRelayFilter(),
        environment,
        mock(SslBundles.class),
        ObservationRegistry.NOOP);
  }

  @Test
  void refreshTokenResponseClientRoundTripsAGrantThroughTheHardenedPool() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json;charset=UTF-8")
            .setBody(
                "{\"access_token\":\"new-access-token\",\"token_type\":\"Bearer\","
                    + "\"expires_in\":300,\"refresh_token\":\"rotated-refresh-token\","
                    + "\"scope\":\"openid profile\"}"));

    OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> client =
        buildConfig().oauthRefreshTokenResponseClient();

    OAuth2AccessTokenResponse response =
        client.getTokenResponse(refreshRequest(server.url("/token").toString()));

    assertThat(response.getAccessToken().getTokenValue()).isEqualTo("new-access-token");
    assertThat(response.getRefreshToken()).isNotNull();
    assertThat(response.getRefreshToken().getTokenValue()).isEqualTo("rotated-refresh-token");

    RecordedRequest recorded = server.takeRequest(2, TimeUnit.SECONDS);
    assertThat(recorded).isNotNull();
    assertThat(recorded.getMethod()).isEqualTo("POST");
    assertThat(recorded.getBody().readUtf8())
        .contains("grant_type=refresh_token")
        .contains("refresh_token=current-refresh-token");
  }

  @Test
  void refreshTokenErrorResponseIsMappedByThePreservedOAuth2ErrorHandler() {
    server.enqueue(
        new MockResponse()
            .setResponseCode(400)
            .addHeader("Content-Type", "application/json;charset=UTF-8")
            .setBody(
                "{\"error\":\"invalid_grant\","
                    + "\"error_description\":\"Token is not active\"}"));

    OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> client =
        buildConfig().oauthRefreshTokenResponseClient();
    OAuth2RefreshTokenGrantRequest request = refreshRequest(server.url("/token").toString());

    assertThatExceptionOfType(OAuth2AuthorizationException.class)
        .isThrownBy(() -> client.getTokenResponse(request))
        .satisfies(ex -> assertThat(ex.getError().getErrorCode()).isEqualTo("invalid_grant"));
  }

  @Test
  void stalledTokenEndpointFailsWithinTheClientSideBound() {
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

    OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> client =
        buildConfig(
                new AppHttpProperties(
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(30),
                    Duration.ofSeconds(120),
                    Duration.ofMillis(500),
                    Duration.ofMillis(500),
                    AppHttpProperties.BackendProtocol.H2,
                    20,
                    AppHttpProperties.BackendCodec.CBOR,
                    false))
            .oauthRefreshTokenResponseClient();
    OAuth2RefreshTokenGrantRequest request = refreshRequest(server.url("/token").toString());

    Instant start = Instant.now();
    assertThatExceptionOfType(OAuth2AuthorizationException.class)
        .isThrownBy(() -> client.getTokenResponse(request));
    assertThat(Duration.between(start, Instant.now()))
        .as(
            "a stalled token exchange must fail via the idle-read bound, not the 30s"
                + " responseTimeout")
        .isLessThan(Duration.ofSeconds(10));
  }

  /**
   * Builds a refresh-token grant request whose client registration points at the given token URI.
   */
  private static OAuth2RefreshTokenGrantRequest refreshRequest(String tokenUri) {
    ClientRegistration registration =
        ClientRegistration.withRegistrationId("keycloak")
            .clientId("basetool-frontend")
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
            .scope("openid", "profile")
            .authorizationUri("https://keycloak.example.com/auth")
            .tokenUri(tokenUri)
            .build();
    OAuth2AccessToken expiredAccessToken =
        new OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER,
            "old-access-token",
            Instant.now().minusSeconds(600),
            Instant.now().minusSeconds(300));
    OAuth2RefreshToken currentRefreshToken =
        new OAuth2RefreshToken("current-refresh-token", Instant.now().minusSeconds(600));
    return new OAuth2RefreshTokenGrantRequest(
        registration, expiredAccessToken, currentRefreshToken);
  }
}
