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

import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Duration;
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
 * TLS trust for the frontend's calls to the internal Keycloak connector (REQ-SEC-014, REQ-SEC-087).
 *
 * <p>The {@value #KEYCLOAK_TRUST_BUNDLE} bundle pins the internal certificate; without it the JVM
 * trust store applies. Hostname verification stays on, so the certificate must carry {@code
 * dns:keycloak}.
 */
public final class KeycloakBackchannelTls {

  /** Name of the SSL bundle pinning the internal Keycloak certificate; defined in {@code prod}. */
  public static final String KEYCLOAK_TRUST_BUNDLE = "keycloak-trust";

  private KeycloakBackchannelTls() {}

  /**
   * Builds a trust-manager factory from the bundle's truststore.
   *
   * @param sslBundles the registered Spring SSL bundles
   * @return the factory, or {@code null} when the bundle is absent
   * @throws IllegalStateException if the bundle exists but its truststore cannot be loaded
   */
  public static @Nullable TrustManagerFactory trustManagers(@NotNull SslBundles sslBundles) {
    try {
      SslBundle bundle = sslBundles.getBundle(KEYCLOAK_TRUST_BUNDLE);
      KeyStore truststore = bundle.getStores().getTrustStore();
      TrustManagerFactory tmf =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      tmf.init(truststore);
      return tmf;
    } catch (NoSuchSslBundleException _) {
      return null;
    } catch (GeneralSecurityException ex) {
      throw new IllegalStateException(
          "Failed to build the '" + KEYCLOAK_TRUST_BUNDLE + "' TLS trust context", ex);
    }
  }

  /**
   * Builds a JDK TLS context that trusts only the bundle's truststore.
   *
   * @param sslBundles the registered Spring SSL bundles
   * @return the context, or {@code null} when the bundle is absent
   * @throws IllegalStateException if the bundle exists but no context can be built from it
   */
  public static @Nullable SSLContext sslContext(@NotNull SslBundles sslBundles) {
    TrustManagerFactory tmf = trustManagers(sslBundles);
    if (tmf == null) {
      return null;
    }
    try {
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, tmf.getTrustManagers(), null);
      return context;
    } catch (GeneralSecurityException ex) {
      throw new IllegalStateException(
          "Failed to build the '" + KEYCLOAK_TRUST_BUNDLE + "' TLS context", ex);
    }
  }

  /**
   * Builds an HTTP/1.1 request factory bounded by the given timeouts.
   *
   * @param sslContext the TLS context to use, or {@code null} for the JVM default
   * @param connectTimeout maximum wait to establish the connection
   * @param readTimeout maximum wait for response bytes
   * @return the request factory
   */
  public static @NotNull ClientHttpRequestFactory requestFactory(
      @Nullable SSLContext sslContext,
      @NotNull Duration connectTimeout,
      @NotNull Duration readTimeout) {
    HttpClient.Builder builder =
        HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(connectTimeout);
    if (sslContext != null) {
      builder.sslContext(sslContext);
    }
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(builder.build());
    factory.setReadTimeout(readTimeout);
    return factory;
  }
}
