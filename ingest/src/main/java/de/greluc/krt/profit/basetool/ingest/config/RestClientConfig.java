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

import de.greluc.krt.profit.basetool.ingest.logging.BackendCallLoggingInterceptor;
import io.micrometer.observation.ObservationRegistry;
import java.net.Socket;
import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.boot.ssl.NoSuchSslBundleException;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Builds the gateway's two outbound {@link RestClient}s on the JDK {@link HttpClient}: the backend
 * relay and the Keycloak client-credentials client (ADR-0204).
 *
 * <p>In {@code dev}/{@code test} all certificates are trusted. Elsewhere the backend relay trusts
 * only the {@code backend-trust} bundle (hostname checked per {@code
 * app.ingest.verify-backend-hostname}, REQ-SEC-070) or else the JVM defaults; the Keycloak client
 * trusts the JVM defaults plus {@code keycloak-trust} and always checks the hostname.
 */
@Configuration
@RequiredArgsConstructor
public class RestClientConfig {

  /** Upper bound on establishing a TCP (and TLS) connection, for both clients. */
  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

  /**
   * Timeout on the backend relay once connected, covering the request until response headers and
   * the body read.
   */
  static final Duration BACKEND_READ_TIMEOUT = Duration.ofSeconds(15);

  /**
   * Ceiling on the token call once connected; the effective bound is the smaller of this and {@code
   * app.ingest.service-account.timeout-millis}.
   */
  static final Duration KEYCLOAK_READ_TIMEOUT_CEILING = Duration.ofSeconds(10);

  private final IngestProperties ingestProperties;
  private final ServiceAccountProperties serviceAccountProperties;
  private final Environment environment;
  private final SslBundles sslBundles;

  /**
   * Observation registry wired into both clients so they record {@code http.client.requests} and
   * propagate {@code traceparent} (REQ-OBS-009).
   */
  private final ObservationRegistry observationRegistry;

  /** Emits the one-line-per-relay outbound access log with its elapsed time (REQ-OBS-001). */
  private final BackendCallLoggingInterceptor backendCallLoggingInterceptor;

  /**
   * The backend-facing client: a 5&nbsp;s connect timeout, a 15&nbsp;s read timeout, profile-gated
   * TLS trust, the outbound call log, and a response body capped at the configured max payload size
   * so a hostile or buggy backend response cannot exhaust heap.
   *
   * @return a {@link RestClient} bound to the configured backend base URL
   */
  @Bean
  public RestClient backendRestClient() {
    return RestClient.builder()
        .baseUrl(ingestProperties.backendBaseUrl())
        .requestFactory(requestFactory(backendSslContext(), BACKEND_READ_TIMEOUT))
        .observationRegistry(observationRegistry)
        .requestInterceptor(backendCallLoggingInterceptor)
        .requestInterceptor(new ResponseSizeLimitInterceptor(ingestProperties.maxPayloadBytes()))
        .build();
  }

  /**
   * The client for the gateway's own client-credentials grant against Keycloak (ADR-0129), with no
   * base URL, no backend-relay logging and its own trust set ({@link #keycloakSslContext()}).
   *
   * @return a {@link RestClient} for the Keycloak token endpoint
   */
  @Bean
  public RestClient keycloakRestClient() {
    Duration readTimeout =
        Duration.ofMillis(
            Math.min(
                KEYCLOAK_READ_TIMEOUT_CEILING.toMillis(),
                serviceAccountProperties.timeoutMillis()));
    return RestClient.builder()
        .requestFactory(requestFactory(keycloakSslContext(), readTimeout))
        .observationRegistry(observationRegistry)
        .build();
  }

  /**
   * Builds a {@link JdkClientHttpRequestFactory} over an HTTP/1.1 JDK client with the given TLS
   * context.
   *
   * @param sslContext the TLS context for {@code https://} targets
   * @param readTimeout the bound on one exchange once connected
   * @return the request factory
   */
  static @NotNull JdkClientHttpRequestFactory requestFactory(
      @NotNull SSLContext sslContext, @NotNull Duration readTimeout) {
    HttpClient httpClient =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(CONNECT_TIMEOUT)
            .sslContext(sslContext)
            .build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
    factory.setReadTimeout(readTimeout);
    return factory;
  }

  /**
   * The backend relay's TLS context: trust-everything in {@code dev}/{@code test} (no hostname
   * check), the {@code backend-trust} bundle as the only anchor elsewhere (hostname checked when
   * {@code app.ingest.verify-backend-hostname} is set, REQ-SEC-070), or the JVM default with
   * hostname verification when no bundle is registered.
   *
   * @return the TLS context for the backend relay
   * @throws IllegalStateException if a TLS context cannot be built from the configured trust
   */
  SSLContext backendSslContext() {
    try {
      if (isDevOrTest()) {
        return sslContext(withoutHostnameVerification(acceptingEverything()));
      }
      KeyStore truststore = trustStoreOrNull("backend-trust");
      if (truststore != null) {
        X509TrustManager pinned = x509From(truststore);
        return sslContext(
            ingestProperties.verifyBackendHostname()
                ? pinned
                : withoutHostnameVerification(pinned));
      }
      return SSLContext.getDefault();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Failed to build the backend relay SSL context", e);
    }
  }

  /**
   * The Keycloak client's TLS context, with its own trust set: the JVM defaults plus the {@code
   * keycloak-trust} bundle, hostname verification on; accept-everything in {@code dev}/{@code
   * test}.
   *
   * <p>Must not reuse {@link #backendSslContext()}, whose sole anchor cannot validate Keycloak's
   * certificate.
   *
   * @return the TLS context for the token endpoint
   * @throws IllegalStateException if a TLS context cannot be built from the configured trust
   */
  SSLContext keycloakSslContext() {
    try {
      if (isDevOrTest()) {
        return sslContext(withoutHostnameVerification(acceptingEverything()));
      }
      return sslContext(keycloakTrustManager());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Failed to build the Keycloak client SSL context", e);
    }
  }

  /**
   * Whether a {@code dev} or {@code test} profile is active — the two in which the local stack's
   * ephemeral certificates are trusted without validation.
   *
   * @return {@code true} under {@code dev} or {@code test}
   */
  private boolean isDevOrTest() {
    List<String> profiles = Arrays.asList(environment.getActiveProfiles());
    return profiles.contains("dev") || profiles.contains("test");
  }

  /**
   * Reads the truststore of a named SSL bundle.
   *
   * @param bundleName the bundle to look up
   * @return its truststore, or {@code null} when no such bundle is registered
   */
  private @Nullable KeyStore trustStoreOrNull(@NotNull String bundleName) {
    try {
      SslBundle bundle = sslBundles.getBundle(bundleName);
      return bundle.getStores().getTrustStore();
    } catch (NoSuchSslBundleException noBundle) {
      return null;
    }
  }

  /**
   * Trust manager for the token endpoint: the JVM's default anchors plus the pinned {@code
   * keycloak-trust} bundle when configured, so both a public and an internal Keycloak validate.
   *
   * <p>Hostname verification stays on.
   *
   * @return a trust manager accepting both anchor sets
   * @throws GeneralSecurityException if neither trust manager can be initialised
   */
  private X509TrustManager keycloakTrustManager() throws GeneralSecurityException {
    X509TrustManager defaults = x509From(null);
    KeyStore pinnedStore = trustStoreOrNull(KeycloakTrustSupport.KEYCLOAK_TRUST_BUNDLE);
    return pinnedStore == null ? defaults : additiveTrustManager(defaults, x509From(pinnedStore));
  }

  /**
   * Accepts a chain that either anchor set validates.
   *
   * <p>A plain {@link X509TrustManager}, so JSSE still verifies the hostname around it.
   *
   * @param defaults the JVM's default anchors
   * @param pinnedAnchors the operator-pinned anchors
   * @return a trust manager accepting either
   */
  @NotNull
  static X509TrustManager additiveTrustManager(
      X509TrustManager defaults, X509TrustManager pinnedAnchors) {
    return new X509TrustManager() {
      @Override
      public void checkClientTrusted(X509Certificate[] chain, String authType)
          throws CertificateException {
        defaults.checkClientTrusted(chain, authType);
      }

      @Override
      public void checkServerTrusted(X509Certificate[] chain, String authType)
          throws CertificateException {
        try {
          defaults.checkServerTrusted(chain, authType);
        } catch (CertificateException publicChainRejected) {
          try {
            pinnedAnchors.checkServerTrusted(chain, authType);
          } catch (CertificateException pinnedRejected) {
            publicChainRejected.addSuppressed(pinnedRejected);
            throw publicChainRejected;
          }
        }
      }

      @NotNull
      @Override
      public X509Certificate[] getAcceptedIssuers() {
        X509Certificate[] a = defaults.getAcceptedIssuers();
        X509Certificate[] b = pinnedAnchors.getAcceptedIssuers();
        X509Certificate[] all = new X509Certificate[a.length + b.length];
        System.arraycopy(a, 0, all, 0, a.length);
        System.arraycopy(b, 0, all, a.length, b.length);
        return all;
      }
    };
  }

  /**
   * Wraps a trust manager so it validates the certificate chain exactly as {@code delegate} does
   * but performs no hostname check, for the one client that installs it.
   *
   * @param delegate the trust manager whose chain validation is kept
   * @return an extended trust manager with the hostname check removed
   */
  static @NotNull X509ExtendedTrustManager withoutHostnameVerification(
      @NotNull X509TrustManager delegate) {
    return new X509ExtendedTrustManager() {
      @Override
      public void checkClientTrusted(X509Certificate[] chain, String authType)
          throws CertificateException {
        delegate.checkClientTrusted(chain, authType);
      }

      @Override
      public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
          throws CertificateException {
        delegate.checkClientTrusted(chain, authType);
      }

      @Override
      public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
          throws CertificateException {
        delegate.checkClientTrusted(chain, authType);
      }

      @Override
      public void checkServerTrusted(X509Certificate[] chain, String authType)
          throws CertificateException {
        delegate.checkServerTrusted(chain, authType);
      }

      @Override
      public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
          throws CertificateException {
        delegate.checkServerTrusted(chain, authType);
      }

      @Override
      public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
          throws CertificateException {
        delegate.checkServerTrusted(chain, authType);
      }

      @NotNull
      @Override
      public X509Certificate[] getAcceptedIssuers() {
        return delegate.getAcceptedIssuers();
      }
    };
  }

  /**
   * A trust manager that accepts every certificate; installed only in {@code dev}/{@code test} (see
   * {@link #isDevOrTest()}).
   *
   * @return a trust manager that validates nothing
   */
  private static @NotNull X509TrustManager acceptingEverything() {
    return new X509TrustManager() {
      @Override
      public void checkClientTrusted(X509Certificate[] chain, String authType) {}

      @Override
      public void checkServerTrusted(X509Certificate[] chain, String authType) {}

      @NotNull
      @Override
      public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
      }
    };
  }

  /**
   * Builds a TLS context around a single trust manager.
   *
   * @param trustManager the trust manager to install
   * @return an initialised TLS context
   * @throws GeneralSecurityException if the context cannot be created
   */
  private static @NotNull SSLContext sslContext(@NotNull TrustManager trustManager)
      throws GeneralSecurityException {
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(null, new TrustManager[] {trustManager}, null);
    return context;
  }

  /**
   * Builds an {@link X509TrustManager} over a truststore, or over the JVM defaults when {@code
   * null}.
   *
   * @param truststore the store to trust, or {@code null} for the JVM's {@code cacerts}
   * @return the first X.509 trust manager the factory produced
   * @throws GeneralSecurityException if the factory yields no X.509 trust manager
   */
  static X509TrustManager x509From(@Nullable KeyStore truststore) throws GeneralSecurityException {
    TrustManagerFactory factory =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    factory.init(truststore);
    for (TrustManager manager : factory.getTrustManagers()) {
      if (manager instanceof X509TrustManager x509) {
        return x509;
      }
    }
    throw new GeneralSecurityException("No X509TrustManager available");
  }
}
