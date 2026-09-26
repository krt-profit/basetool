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

package de.greluc.krt.profit.basetool.ingest.config;

import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.boot.ssl.NoSuchSslBundleException;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;

/**
 * Builds a {@link ClientHttpRequestFactory} whose JDK {@link HttpClient} trusts only a named SSL
 * bundle's truststore, for the JWKS fetch from an internal Keycloak (REQ-SEC-024).
 *
 * <p>Hostname verification stays on, so the pinned certificate must carry {@code dns:keycloak} in
 * its SAN.
 */
public final class KeycloakTrustSupport {

  /**
   * Name of the Spring SSL bundle whose truststore pins the self-signed certificate the production
   * Keycloak presents on its internal {@code https://keycloak:18443} connector. Defined in {@code
   * application-prod.yml}; absent in dev/test, where Keycloak is reached over plain HTTP.
   */
  public static final String KEYCLOAK_TRUST_BUNDLE = "keycloak-trust";

  private KeycloakTrustSupport() {}

  /**
   * Builds a truststore-pinned {@link ClientHttpRequestFactory} from the named SSL bundle; callers
   * should cache the result.
   *
   * @param sslBundles the registered Spring SSL bundles
   * @param bundleName the name of the bundle whose truststore pins the accepted certificate
   * @return a truststore-pinned request factory, or {@code null} when the bundle is absent
   * @throws IllegalStateException if the bundle exists but no TLS context can be built from it
   */
  @Nullable
  public static ClientHttpRequestFactory trustedRequestFactory(
      @NotNull SslBundles sslBundles, @NotNull String bundleName) {
    try {
      SslBundle bundle = sslBundles.getBundle(bundleName);
      KeyStore truststore = bundle.getStores().getTrustStore();
      TrustManagerFactory tmf =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      tmf.init(truststore);
      SSLContext sslContext = SSLContext.getInstance("TLS");
      sslContext.init(null, tmf.getTrustManagers(), null);
      HttpClient httpClient = HttpClient.newBuilder().sslContext(sslContext).build();
      return new JdkClientHttpRequestFactory(httpClient);
    } catch (NoSuchSslBundleException ex) {
      return null;
    } catch (GeneralSecurityException ex) {
      throw new IllegalStateException(
          "Failed to build the '" + bundleName + "' TLS trust context", ex);
    }
  }
}
