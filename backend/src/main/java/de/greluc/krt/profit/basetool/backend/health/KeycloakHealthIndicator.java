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

package de.greluc.krt.profit.basetool.backend.health;

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
 * {@link HealthIndicator} that probes the Keycloak realm's OIDC discovery endpoint and reports
 * {@code DOWN} when it is unreachable.
 *
 * <p>Part of the {@code readiness} health group under the key {@code keycloak}; disabled by {@code
 * management.health.keycloak.enabled=false}. The probe uses a 2&nbsp;s connect and 3&nbsp;s read
 * timeout.
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
   * Production constructor; builds the probe client with the indicator's default timeouts.
   *
   * @param issuerUri the Keycloak realm issuer URI from {@code
   *     spring.security.oauth2.resourceserver.jwt.issuer-uri}; the discovery path is appended
   */
  @Autowired
  public KeycloakHealthIndicator(
      @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri) {
    this(issuerUri, CONNECT_TIMEOUT, READ_TIMEOUT);
  }

  /**
   * Test constructor accepting custom timeouts.
   *
   * @param issuerUri the Keycloak realm issuer URI; the OIDC discovery path is appended verbatim
   * @param connectTimeout maximum wait to establish the TCP connection
   * @param readTimeout maximum wait for response bytes
   */
  KeycloakHealthIndicator(String issuerUri, Duration connectTimeout, Duration readTimeout) {
    this.discoveryUrl = issuerUri + DISCOVERY_PATH;
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
    factory.setReadTimeout(readTimeout);
    this.client = RestClient.builder().requestFactory(factory).build();
  }

  /**
   * Fetches the OIDC discovery document and maps the outcome to a {@link Health}: {@code UP} on
   * 2xx, {@code DOWN} with the status code or exception class otherwise.
   *
   * @return {@link Health#up()} on a 2xx reply; {@link Health#down()} with diagnostic details
   *     otherwise
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
