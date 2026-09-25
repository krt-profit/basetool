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
 * Builds the gateway's two outbound clients — the relay to the internal backend and the
 * client-credentials grant against Keycloak — as {@link RestClient}s on the JDK {@link HttpClient}
 * (ADR-0204). The bearer token and per-request headers are attached per call in {@code
 * BackendImportClient}, not here.
 *
 * <p>Both calls are blocking — they run on the request thread that is waiting for the answer — so
 * the reactive {@code WebClient} this class used to build bought nothing but a second HTTP stack on
 * the internet-facing module's classpath and a {@code block()} at every call site.
 *
 * <p><b>TLS trust</b> (finding M-13, ING-SEC-04). In {@code dev}/{@code test} the ephemeral docker
 * certificate is trusted without validation and without a hostname check. In every other profile a
 * configured {@code backend-trust} SSL bundle becomes the backend relay's <em>only</em> trust
 * anchor. Whether the relay then also verifies the hostname is {@code
 * app.ingest.verify-backend-hostname} (REQ-SEC-070, ADR-0211): off by default — the chain is pinned
 * and the name ignored, as ADR-0204 kept it for the single shared self-signed certificate — and on
 * once every service serves its own leaf from the internal CA, where the pinned anchor vouches for
 * every service and only the name tells the backend's certificate from the gateway's own. With no
 * bundle the relay falls back to the JVM trust store with hostname verification ON. The Keycloak
 * client trusts the JVM anchors plus the pinned {@code keycloak-trust} bundle and always verifies
 * the hostname outside {@code dev}/{@code test}.
 *
 * <p><b>How the hostname check is switched off per client.</b> The JDK client cannot do that
 * through its own API — it always asks the TLS engine for HTTPS endpoint identification, and the
 * only switch is a JVM-wide system property. The check itself, though, is performed by the trust
 * manager: an {@link X509ExtendedTrustManager} is handed the engine and verifies the peer's name
 * against it, while a plain {@link X509TrustManager} is wrapped by JSSE in one that does. {@link
 * #withoutHostnameVerification} is an extended trust manager that validates the chain and ignores
 * the engine, so the check is dropped for exactly the client that installs it and nowhere else.
 */
@Configuration
@RequiredArgsConstructor
public class RestClientConfig {

  /** Upper bound on establishing a TCP (and TLS) connection, for both clients. */
  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

  /**
   * Upper bound on the backend relay once connected: the JDK request timeout (until the response
   * headers arrive, which includes sending the upload) and, via Spring's request factory, the body
   * read. The replaced client bounded read, write and response by 15&nbsp;s each.
   */
  static final Duration BACKEND_READ_TIMEOUT = Duration.ofSeconds(15);

  /**
   * Ceiling on the token call once connected. The effective bound is the smaller of this and {@code
   * app.ingest.service-account.timeout-millis}, which is what the replaced client's netty timeouts
   * (10&nbsp;s) and its {@code block(timeoutMillis)} amounted to together.
   */
  static final Duration KEYCLOAK_READ_TIMEOUT_CEILING = Duration.ofSeconds(10);

  private final IngestProperties ingestProperties;
  private final ServiceAccountProperties serviceAccountProperties;
  private final Environment environment;
  private final SslBundles sslBundles;

  /**
   * Micrometer observation registry wired into both clients (REQ-OBS-009). The clients are
   * hand-built with {@code RestClient.builder()} — this module does not ship Boot's {@code
   * spring-boot-restclient}, whose customizer would otherwise do it — so without this explicit
   * wiring no {@code http.client.requests} metrics are recorded and, with tracing enabled, no
   * {@code traceparent} header propagates to the backend.
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
   * The client used for the gateway's own client-credentials grant against Keycloak (ADR-0129).
   *
   * <p>Separate from {@link #backendRestClient()} for three reasons: it addresses a different host
   * (absolute token URI, so no base URL), it must not carry the backend-relay logging interceptor —
   * that interceptor names the call as a backend hop and this one is not — and its response is a
   * handful of bytes. It has its own trust set because it faces a different server; see {@link
   * #keycloakSslContext()}.
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
   * Builds a {@link JdkClientHttpRequestFactory} over a JDK client with the given TLS context.
   *
   * <p>The client is pinned to HTTP/1.1: the JDK client defaults to HTTP/2, which against the
   * backend's TLS connector would negotiate h2 through ALPN, and over plain {@code http://} would
   * offer {@code Upgrade: h2c} on every request — neither of which the replaced Reactor Netty
   * client did. Idle pooled connections are closed after the JDK's {@code
   * jdk.httpclient.keepalive.timeout} (30&nbsp;s by default), well inside Tomcat's 60&nbsp;s
   * keep-alive on the backend, so the client never reuses a connection the server has already
   * dropped — the stale-connection failure a pool without idle eviction invites (ING-PERF-01).
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
   * The Keycloak client's TLS context, with its OWN trust set.
   *
   * <p><strong>It must not reuse {@link #backendSslContext()}.</strong> That one installs the
   * {@code backend-trust} bundle as the <em>only</em> trust anchor, which is right for the
   * self-signed {@code https://backend:11261} and catastrophic here: pinned to the backend's
   * certificate, this client cannot validate Keycloak's publicly-trusted one, so the TLS handshake
   * fails and the client-credentials grant dies as a transport error with no HTTP status to explain
   * it. That is exactly how it failed on 2026-08-04 — every send answered "An unexpected error
   * occurred."
   *
   * <p>The trust set mirrors {@link KeycloakTrustSupport}, which the JWKS decoder already uses: the
   * JVM's default anchors plus {@code keycloak-trust} when that bundle is configured (an internal,
   * self-signed Keycloak), with hostname verification left ON. {@code dev}/{@code test} keep the
   * accept-everything trust for the local stack's ephemeral certificate.
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
   * Trust manager for the token endpoint: the JVM's default anchors <em>plus</em> the pinned {@code
   * keycloak-trust} bundle when one is configured.
   *
   * <p><strong>Additive on purpose, and this is the whole fix.</strong> Choosing one or the other
   * gets it wrong whichever way you choose, because the right answer depends on where the token URI
   * points — the public host needs the public CAs, an internal one needs the pinned certificate —
   * and nothing in the SSL configuration knows which was configured. Both previous attempts picked
   * a single anchor and broke the other case: first the backend's truststore, then the pinned
   * Keycloak one, each failing against the public certificate with {@code PKIX path building
   * failed}.
   *
   * <p>Trusting both is not a weakening: the pinned certificate is one the operator deliberately
   * placed in the bundle, and the default anchors are what every other publicly-trusted call in the
   * JVM already uses. Hostname verification stays on either way: the additive manager is a plain
   * {@link X509TrustManager}, which JSSE wraps in one that checks the peer's name, and the defaults
   * alone are the JDK's own extended manager, which checks it itself.
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
   * Accepts a chain that <em>either</em> anchor set validates.
   *
   * <p>Package-private so a test can drive it with two throwaway anchor sets and assert the
   * property that both shipped bugs violated: adding one anchor set must never remove the other.
   * Deliberately a plain {@link X509TrustManager}, not an extended one, so JSSE keeps verifying the
   * hostname around it.
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
   * but performs no hostname check.
   *
   * <p>Being an {@link X509ExtendedTrustManager}, it is handed the TLS engine (or socket) by JSSE,
   * which is where endpoint identification would happen; it passes only the chain on to {@code
   * delegate}'s two-argument check, which never looks at a hostname. Package-private so a test can
   * prove both halves against a real TLS server: a pinned-but-misnamed certificate is accepted, an
   * unpinned one is still refused.
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
   * A trust manager that accepts every certificate — the {@code dev}/{@code test} posture for the
   * local stack's ephemeral certificates, which the replaced client took from Netty's {@code
   * InsecureTrustManagerFactory}. Never installed outside those two profiles (see {@link
   * #isDevOrTest()}).
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
