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

package de.greluc.krt.profit.basetool.frontend.health;

import java.net.http.HttpClient;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Health indicator that probes the Keycloak realm's OIDC discovery endpoint ({@code
 * {issuer}/.well-known/openid-configuration}) and reports {@code DOWN} when it is unreachable, so
 * the frontend's readiness reflects SSO viability.
 *
 * <p>The issuer comes from the frontend's OAuth2 client property {@code
 * spring.security.oauth2.client.provider.keycloak.issuer-uri}. Probes use a 2&nbsp;s connect and
 * 3&nbsp;s read timeout. The indicator key is {@code keycloak}; {@code
 * management.health.keycloak.enabled=false} disables it.
 */
@Component
@ConditionalOnEnabledHealthIndicator("keycloak")
@Slf4j
public class KeycloakHealthIndicator implements HealthIndicator {

  /** Path appended to the issuer URI to reach the standard OIDC discovery document. */
  static final String DISCOVERY_PATH = "/.well-known/openid-configuration";

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

  private final String discoveryUrl;
  private final RestClient client;

  /**
   * Creates the indicator for the configured issuer with the indicator-specific timeouts.
   *
   * @param issuerUri the Keycloak realm issuer URI from {@code
   *     spring.security.oauth2.client.provider.keycloak.issuer-uri}; the discovery path is appended
   *     verbatim
   */
  @Autowired
  public KeycloakHealthIndicator(
      @Value("${spring.security.oauth2.client.provider.keycloak.issuer-uri}") String issuerUri) {
    this(issuerUri, CONNECT_TIMEOUT, READ_TIMEOUT);
  }

  /**
   * Visible-for-testing constructor that lets unit tests inject shorter timeouts when driving the
   * indicator against an in-process {@code MockWebServer}.
   *
   * @param issuerUri the Keycloak realm issuer URI; the OIDC discovery path is appended verbatim
   * @param connectTimeout maximum time the underlying {@link HttpClient} waits to establish the TCP
   *     connection before the probe is treated as {@code DOWN}
   * @param readTimeout maximum time the {@link RestClient} waits for response bytes before the
   *     probe is treated as {@code DOWN}
   */
  KeycloakHealthIndicator(String issuerUri, Duration connectTimeout, Duration readTimeout) {
    this.discoveryUrl = issuerUri + DISCOVERY_PATH;
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
    factory.setReadTimeout(readTimeout);
    this.client = RestClient.builder().requestFactory(factory).build();
  }

  /**
   * Probes the OIDC discovery endpoint: a 2xx yields {@code UP}; an HTTP error yields {@code DOWN}
   * with the status code, and an I/O failure {@code DOWN} with the exception class name.
   *
   * @return {@link Health#up()} when the discovery endpoint replied with a 2xx status; {@link
   *     Health#down()} otherwise, with diagnostic details attached
   */
  @Override
  public @NotNull Health health() {
    try {
      client.get().uri(discoveryUrl).retrieve().toBodilessEntity();
      return Health.up().withDetail("endpoint", "openid-configuration").build();
    } catch (RestClientResponseException ex) {
      log.warn(
          "Keycloak OIDC discovery returned HTTP {} from {}",
          ex.getStatusCode().value(),
          discoveryUrl);
      return Health.down()
          .withDetail("endpoint", "openid-configuration")
          .withDetail("status", ex.getStatusCode().value())
          .build();
    } catch (RestClientException ex) {
      log.warn(
          "Keycloak OIDC discovery unreachable at {}: {}", discoveryUrl, ex.getClass().getName());
      return Health.down()
          .withDetail("endpoint", "openid-configuration")
          .withDetail("error", ex.getClass().getSimpleName())
          .build();
    }
  }
}
