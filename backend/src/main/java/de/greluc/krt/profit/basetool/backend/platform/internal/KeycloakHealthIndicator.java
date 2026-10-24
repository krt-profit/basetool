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

package de.greluc.krt.profit.basetool.backend.platform.internal;

import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.ssl.NoSuchSslBundleException;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * {@link HealthIndicator} that probes the Keycloak realm's OIDC discovery endpoint and reports
 * {@code DOWN} when it is unreachable.
 *
 * <p>The realm probed is {@code app.security.keycloak-backchannel.uri} when set, otherwise the
 * issuer.
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

  /** Name of the SSL bundle pinning the internal Keycloak certificate; defined in {@code prod}. */
  static final String KEYCLOAK_TRUST_BUNDLE = "keycloak-trust";

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

  private final String discoveryUrl;
  private final RestClient client;

  /**
   * Production constructor; builds the probe client with the indicator's default timeouts and
   * probes the internal realm base over the {@code keycloak-trust} bundle when a back-channel is
   * configured (REQ-SEC-087).
   *
   * @param issuerUri the Keycloak realm issuer URI from {@code
   *     spring.security.oauth2.resourceserver.jwt.issuer-uri}
   * @param backchannelUri the internal realm base from {@code
   *     app.security.keycloak-backchannel.uri}, probed instead of the issuer when not blank
   * @param sslBundles the registered SSL bundles
   */
  @Autowired
  public KeycloakHealthIndicator(
      @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
      @Value("${app.security.keycloak-backchannel.uri:}") String backchannelUri,
      @NotNull SslBundles sslBundles) {
    this(
        StringUtils.hasText(backchannelUri) ? stripTrailingSlash(backchannelUri.trim()) : issuerUri,
        StringUtils.hasText(backchannelUri) ? keycloakTrust(sslBundles) : null,
        CONNECT_TIMEOUT,
        READ_TIMEOUT);
  }

  /**
   * Test constructor accepting custom timeouts.
   *
   * @param realmUri the realm URI probed; the OIDC discovery path is appended verbatim
   * @param sslContext the TLS context of the probe, or {@code null} for the JVM trust store
   * @param connectTimeout maximum wait to establish the TCP connection
   * @param readTimeout maximum wait for response bytes
   */
  KeycloakHealthIndicator(
      String realmUri,
      @Nullable SSLContext sslContext,
      Duration connectTimeout,
      Duration readTimeout) {
    this.discoveryUrl = realmUri + DISCOVERY_PATH;
    HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(connectTimeout);
    if (sslContext != null) {
      builder.sslContext(sslContext);
    }
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(builder.build());
    factory.setReadTimeout(readTimeout);
    this.client = RestClient.builder().requestFactory(factory).build();
  }

  /**
   * Builds a TLS context trusting only the {@value #KEYCLOAK_TRUST_BUNDLE} bundle's truststore.
   *
   * @param sslBundles the registered SSL bundles
   * @return the context, or {@code null} when the bundle is absent
   * @throws IllegalStateException if the bundle exists but no context can be built from it
   */
  static @Nullable SSLContext keycloakTrust(@NotNull SslBundles sslBundles) {
    try {
      KeyStore truststore = sslBundles.getBundle(KEYCLOAK_TRUST_BUNDLE).getStores().getTrustStore();
      TrustManagerFactory tmf =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      tmf.init(truststore);
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, tmf.getTrustManagers(), null);
      return context;
    } catch (NoSuchSslBundleException _) {
      return null;
    } catch (GeneralSecurityException ex) {
      throw new IllegalStateException(
          "Failed to build the '" + KEYCLOAK_TRUST_BUNDLE + "' TLS context", ex);
    }
  }

  private static String stripTrailingSlash(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
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
