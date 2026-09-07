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
 * End-to-end guard for the pool-hardened OAuth2 token-response clients (ADR-0115). The Keycloak
 * hairpin fix swaps <b>only</b> the transport under Spring Security's default token client — a
 * dedicated, idle-evicting reactor-netty pool — by replacing the whole {@code RestClient} via
 * {@code setRestClient}. That replacement is only correct if the substituted {@code RestClient}
 * still carries the form-encoding request converter, the token-response converter and the OAuth2
 * error handler; drop any of them and a real refresh grant fails to encode or parse.
 *
 * <p>This test drives {@link WebClientConfig#oauthRefreshTokenResponseClient()} against a {@link
 * MockWebServer} standing in for Keycloak's token endpoint and asserts a real {@code refresh_token}
 * grant round-trips: the request is a {@code POST} carrying {@code grant_type=refresh_token}, and
 * the JSON token response is parsed back into an {@link OAuth2AccessTokenResponse}. So a regression
 * that clears the converter list without re-adding both converters can no longer compile-and-pass.
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
            Duration.ofSeconds(3),
            Duration.ofSeconds(3)));
  }

  /**
   * Builds the real {@link WebClientConfig} with light test doubles for its collaborators and the
   * given HTTP timeout knobs, letting individual tests pick timeout constellations that make one
   * specific transport bound observable (see {@link
   * #stalledTokenEndpointFailsWithinTheClientSideBound()}).
   *
   * @param httpProperties the timeout constellation the config under test should run with
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

    // Response converter intact: the token JSON parsed back into the typed response.
    assertThat(response.getAccessToken().getTokenValue()).isEqualTo("new-access-token");
    assertThat(response.getRefreshToken()).isNotNull();
    assertThat(response.getRefreshToken().getTokenValue()).isEqualTo("rotated-refresh-token");

    // Request converter intact: the grant went out as a form-encoded refresh_token POST.
    RecordedRequest recorded = server.takeRequest(2, TimeUnit.SECONDS);
    assertThat(recorded).isNotNull();
    assertThat(recorded.getMethod()).isEqualTo("POST");
    assertThat(recorded.getBody().readUtf8())
        .contains("grant_type=refresh_token")
        .contains("refresh_token=current-refresh-token");
  }

  @Test
  void refreshTokenErrorResponseIsMappedByThePreservedOAuth2ErrorHandler() {
    // A Keycloak token error (400 + an RFC 6749 error body). Only the preserved
    // OAuth2ErrorResponseErrorHandler — the third behaviour the setRestClient transport swap must
    // keep — maps this to a typed OAuth2AuthorizationException carrying the `invalid_grant` code
    // (the
    // signal the refresh provider surfaces as client_authorization_required, REQ-SEC-012). Without
    // that handler the RestClient raises a raw HttpClientErrorException that getTokenResponse wraps
    // as a generic `invalid_token_response`, so asserting the parsed error code proves the handler
    // survived the RestClient replacement.
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
    // ADR-0115 follow-up (2026-07-22 incident): reactor-netty's responseTimeout only arms once the
    // request has been FULLY written, so a token exchange that stalled mid-request had no
    // client-side bound at all and hung until the edge reaped the socket after ~60s — one lost
    // refresh grant per attempt. The ReadTimeoutHandler/WriteTimeoutHandler pair added in
    // oauthTokenRestClient() closes that gap. This test makes the idle-read bound observable: the
    // fake token endpoint accepts the connection but never responds, the read timeout is 500ms and
    // the responseTimeout a deliberately long 30s — only the ReadTimeoutHandler can fail the
    // exchange quickly, so an elapsed time far below the responseTimeout proves the handler is
    // wired (a regression dropping the doOnConnected handlers blocks for the full 30s and trips
    // the elapsed assertion).
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

    OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> client =
        buildConfig(
                new AppHttpProperties(
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(30),
                    Duration.ofMillis(500),
                    Duration.ofMillis(500)))
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
