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

import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
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
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.ssl.NoSuchSslBundleException;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Health indicator that probes the backend's {@code /actuator/health/readiness} endpoint and
 * reports {@code DOWN} when the backend is unavailable, so the frontend's readiness reflects its
 * upstream.
 *
 * <p>The probe targets {@code app.backend-health-url}, the backend's Actuator base URL, falling
 * back to {@code app.backend-url}. TLS trust follows {@link #backendTls(SslBundles, Environment,
 * boolean)}. Probes use a 2&nbsp;s connect and 3&nbsp;s read timeout. The indicator key is {@code
 * backend}; {@code management.health.backend.enabled=false} disables it.
 */
@Component
@ConditionalOnEnabledHealthIndicator("backend")
@Slf4j
public class BackendHealthIndicator implements HealthIndicator {

  /** Path appended to the backend base URL to reach the readiness probe endpoint. */
  static final String READINESS_PATH = "/actuator/health/readiness";

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

  private final String readinessUrl;
  private final RestClient client;

  /**
   * Creates the indicator with the backend Actuator URL, the indicator timeouts and the TLS trust
   * policy from {@link #backendTls(SslBundles, Environment, boolean)}.
   *
   * @param backendHealthUrl base URL of the backend's Actuator ({@code app.backend-health-url},
   *     defaulting to {@code app.backend-url}); the readiness path is appended verbatim
   * @param sslBundles the configured SSL bundles, source of the {@code backend-trust} truststore
   * @param environment the active environment, used to pick the trust policy per profile
   * @param verifyHostname {@code app.http.verify-backend-hostname}: keep hostname verification on
   *     the pinned path (REQ-SEC-070)
   */
  @Autowired
  public BackendHealthIndicator(
      @Value("${app.backend-health-url:${app.backend-url}}") String backendHealthUrl,
      SslBundles sslBundles,
      Environment environment,
      @Value("${app.http.verify-backend-hostname:false}") boolean verifyHostname) {
    this(
        backendHealthUrl,
        CONNECT_TIMEOUT,
        READ_TIMEOUT,
        backendTls(sslBundles, environment, verifyHostname));
  }

  /**
   * Visible-for-testing constructor that lets unit tests inject shorter timeouts and a {@code
   * MockWebServer} URL.
   *
   * @param backendUrl the backend base URL; the readiness probe path is appended verbatim (trailing
   *     slashes are trimmed to keep the resulting URL canonical)
   * @param connectTimeout maximum time the underlying {@link HttpClient} waits to establish the TCP
   *     connection before the probe is treated as {@code DOWN}
   * @param readTimeout maximum time the {@link RestClient} waits for response bytes before the
   *     probe is treated as {@code DOWN}
   */
  BackendHealthIndicator(String backendUrl, Duration connectTimeout, Duration readTimeout) {
    this(backendUrl, connectTimeout, readTimeout, trustAllSslContext());
  }

  /**
   * Builds the {@link RestClient} from a resolved backend {@link SSLContext}, whose trust managers
   * alone govern hostname verification.
   *
   * @param backendUrl backend base URL (trailing slash trimmed)
   * @param connectTimeout TCP connect timeout for the probe
   * @param readTimeout response read timeout for the probe
   * @param sslContext the resolved TLS context whose trust managers gate the probe's handshake
   */
  private BackendHealthIndicator(
      @NotNull String backendUrl,
      Duration connectTimeout,
      Duration readTimeout,
      SSLContext sslContext) {
    String trimmedBaseUrl =
        backendUrl.endsWith("/") ? backendUrl.substring(0, backendUrl.length() - 1) : backendUrl;
    this.readinessUrl = trimmedBaseUrl + READINESS_PATH;
    HttpClient httpClient =
        HttpClient.newBuilder().connectTimeout(connectTimeout).sslContext(sslContext).build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
    factory.setReadTimeout(readTimeout);
    this.client = RestClient.builder().requestFactory(factory).build();
  }

  /**
   * Probes the backend's readiness endpoint: a 2xx yields {@code UP}; an HTTP error yields {@code
   * DOWN} with the status code, and an I/O failure {@code DOWN} with the exception class name.
   *
   * @return {@link Health#up()} when the backend's readiness endpoint replied with a 2xx status;
   *     {@link Health#down()} otherwise, with diagnostic details attached
   */
  @Override
  public @NotNull Health health() {
    try {
      client.get().uri(readinessUrl).retrieve().toBodilessEntity();
      return Health.up().withDetail("endpoint", "backend-readiness").build();
    } catch (RestClientResponseException ex) {
      log.warn(
          "Backend readiness probe returned HTTP {} from {}",
          ex.getStatusCode().value(),
          readinessUrl);
      return Health.down()
          .withDetail("endpoint", "backend-readiness")
          .withDetail("status", ex.getStatusCode().value())
          .build();
    } catch (RestClientException ex) {
      log.warn(
          "Backend readiness probe unreachable at {}: {}", readinessUrl, ex.getClass().getName());
      return Health.down()
          .withDetail("endpoint", "backend-readiness")
          .withDetail("error", ex.getClass().getSimpleName())
          .build();
    }
  }

  /**
   * Builds an {@link SSLContext} that trusts every certificate chain, using Netty's {@link
   * InsecureTrustManagerFactory}.
   *
   * @return a TLS {@link SSLContext} whose trust managers accept every certificate chain
   *     unconditionally
   */
  private static SSLContext trustAllSslContext() {
    try {
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, InsecureTrustManagerFactory.INSTANCE.getTrustManagers(), null);
      return context;
    } catch (GeneralSecurityException ex) {
      throw new IllegalStateException("Failed to initialise trust-all SSL context", ex);
    }
  }

  /**
   * Resolves the probe's TLS trust policy, matching {@code WebClientConfig}.
   *
   * <ul>
   *   <li>{@code dev} / {@code test}: trust-all.
   *   <li>Otherwise with a {@code backend-trust} bundle: pinned to its truststore, skipping
   *       hostname verification via {@link #pinnedTrustSkippingHostname(TrustManager[])} unless
   *       {@code verifyHostname} is set (REQ-SEC-070).
   *   <li>Otherwise: trust-all, with a WARN to configure the bundle.
   * </ul>
   *
   * @param sslBundles the application's configured SSL bundles
   * @param environment the active environment, for profile detection
   * @param verifyHostname keep hostname verification on the pinned {@code backend-trust} path
   * @return the resolved {@link SSLContext} for the probe's HTTP client
   */
  static SSLContext backendTls(
      SslBundles sslBundles, @NotNull Environment environment, boolean verifyHostname) {
    List<String> profiles = Arrays.asList(environment.getActiveProfiles());
    if (profiles.contains("dev") || profiles.contains("test")) {
      return trustAllSslContext();
    }
    try {
      SslBundle bundle = sslBundles.getBundle("backend-trust");
      KeyStore truststore = bundle.getStores().getTrustStore();
      TrustManagerFactory tmf =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      tmf.init(truststore);
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(
          null,
          verifyHostname
              ? tmf.getTrustManagers()
              : pinnedTrustSkippingHostname(tmf.getTrustManagers()),
          null);
      return context;
    } catch (NoSuchSslBundleException ignored) {
      log.warn(
          "No 'backend-trust' SSL bundle configured; the backend health probe falls back to a"
              + " trust-all TLS policy (matching WebClientConfig). Configure the 'backend-trust'"
              + " SSL bundle to pin the backend's self-signed certificate — see"
              + " application-prod.yml.");
      return trustAllSslContext();
    } catch (GeneralSecurityException ex) {
      throw new IllegalStateException("Failed to initialise backend-trust SSL context", ex);
    }
  }

  /**
   * Wraps PKIX trust managers in {@link HostnameAgnosticTrustManager}s, which keep the pinned chain
   * validation but skip hostname verification.
   *
   * @param delegates the PKIX trust managers produced from the {@code backend-trust} truststore
   * @return trust managers that pin the chain but do not enforce hostname verification
   */
  private static TrustManager[] pinnedTrustSkippingHostname(@NotNull TrustManager[] delegates) {
    TrustManager[] wrapped = delegates.clone();
    for (int i = 0; i < wrapped.length; i++) {
      if (wrapped[i] instanceof X509TrustManager x509) {
        wrapped[i] = new HostnameAgnosticTrustManager(x509);
      }
    }
    return wrapped;
  }

  /**
   * {@link X509ExtendedTrustManager} that validates the certificate chain through a wrapped {@link
   * X509TrustManager} but skips hostname verification, by routing the {@link SSLEngine}- and {@link
   * Socket}-aware {@code checkServerTrusted} overloads to the two-argument variant.
   */
  static final class HostnameAgnosticTrustManager extends X509ExtendedTrustManager {

    private final X509TrustManager delegate;

    /**
     * Creates a hostname-agnostic wrapper around a pinning {@link X509TrustManager}.
     *
     * @param delegate the PKIX trust manager that performs chain validation against the pinned
     *     {@code backend-trust} truststore
     */
    HostnameAgnosticTrustManager(X509TrustManager delegate) {
      this.delegate = delegate;
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
    public X509Certificate[] getAcceptedIssuers() {
      return delegate.getAcceptedIssuers();
    }
  }
}
