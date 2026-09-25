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

package de.greluc.krt.profit.basetool.keycloak.spi;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import lombok.extern.jbosslog.JBossLog;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Builds the {@link HttpClient} the {@link BackendAccountChecker} uses to call the Basetool backend
 * over HTTPS (REQ-SEC-022). The backend serves a self-signed certificate that the default JVM
 * truststore inside the Keycloak container does not trust, so this loads an explicit PKCS#12
 * truststore (path + password from configuration) and pins it as the client's {@code SSLContext}.
 *
 * <p>Certificate validation is <strong>never disabled</strong> — there is deliberately no
 * trust-all/insecure path here. When no truststore is configured, or it cannot be loaded, the
 * client falls back to the default JVM truststore; a TLS handshake against the self-signed backend
 * certificate then fails, which the checker maps to {@link BackendAccountChecker.Result#UNKNOWN}
 * (fail open) rather than admitting an unverified connection.
 */
@JBossLog
final class BackendTrustSupport {

  private BackendTrustSupport() {}

  /**
   * Builds an HTTP client trusting the configured backend truststore, falling back to the default
   * JVM truststore when none is configured or it cannot be loaded.
   *
   * @param connectTimeout the connection timeout for the client
   * @param truststorePath filesystem path to a PKCS#12 truststore containing the backend
   *     certificate; {@code null}/blank uses the default JVM truststore
   * @param truststorePassword the truststore password; may be {@code null}/blank
   * @return a configured {@link HttpClient}
   */
  static @NotNull HttpClient httpClient(
      @NotNull Duration connectTimeout,
      @Nullable String truststorePath,
      @Nullable String truststorePassword) {
    HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(connectTimeout);
    SSLContext sslContext = sslContext(truststorePath, truststorePassword);
    if (sslContext != null) {
      builder.sslContext(sslContext);
    }
    return builder.build();
  }

  /**
   * Loads a PKCS#12 truststore and derives an {@link SSLContext} that trusts exactly its
   * certificates.
   *
   * @param truststorePath the truststore path; {@code null}/blank returns {@code null} (default
   *     trust)
   * @param truststorePassword the truststore password; may be {@code null}/blank
   * @return the pinned {@link SSLContext}, or {@code null} to use the default JVM truststore (no
   *     truststore configured, or a load failure — never an insecure trust-all context)
   */
  @Contract("null, _ -> null")
  private static @Nullable SSLContext sslContext(
      @Nullable String truststorePath, @Nullable String truststorePassword) {
    if (truststorePath == null || truststorePath.isBlank()) {
      return null;
    }
    try {
      KeyStore trustStore = KeyStore.getInstance("PKCS12");
      char[] password = truststorePassword == null ? new char[0] : truststorePassword.toCharArray();
      try (InputStream in = Files.newInputStream(Path.of(truststorePath))) {
        trustStore.load(in, password);
      }
      TrustManagerFactory trustManagerFactory =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      trustManagerFactory.init(trustStore);
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, trustManagerFactory.getTrustManagers(), null);
      return context;
    } catch (GeneralSecurityException | IOException e) {
      log.warn(
          "Failed to load the backend truststore; the Discord account-existence precheck will fail"
              + " open until it is fixed.",
          e);
      return null;
    }
  }
}
