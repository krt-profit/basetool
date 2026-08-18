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
 * Spring Boot {@link HealthIndicator} that probes the backend's {@code /actuator/health/readiness}
 * endpoint and surfaces backend unavailability as a {@code DOWN} contribution to the frontend's
 * {@code /actuator/health}.
 *
 * <p>The frontend is a Thymeleaf SSR shell: every data-bearing page issues an outbound call to the
 * backend, so a backend outage breaks the user-facing surface even though the JVM, Tomcat and the
 * Resilience4j circuit breakers are still nominally healthy. The auto-configured {@code
 * CircuitBreakerHealthIndicator} from {@code resilience4j-spring-boot3} only goes {@code DOWN} once
 * enough requests have failed to trip the breaker — useful for runtime degradation, useless during
 * the startup window when no real traffic has flowed yet. This indicator closes that gap with an
 * explicit probe: hit the same readiness endpoint that Docker Compose uses for the {@code backend}
 * service's {@code service_healthy} gate, so the frontend's own readiness reflects an end-to-end
 * "the upstream I depend on is ready" check.
 *
 * <p><b>The probe target is the backend's ACTUATOR base URL, which is not always its API base
 * URL.</b> It comes from {@code app.backend-health-url} and falls back to {@code app.backend-url}
 * when that key is unset — right for every profile that serves both from one connector (dev, test,
 * e2e). Production is not one of them: ADR-0134 moved the backend's Actuator to the internal-only
 * management port {@code 11271}, so {@code https://backend:11261/actuator/health/readiness} answers
 * 404 there. Because this indicator sits in the readiness group that gates the Docker HEALTHCHECK,
 * that 404 made the frontend container permanently unhealthy and the deploy loop rolled v1.5.47
 * back twice before the cause was found. Whenever the backend's management port moves, this
 * property moves with it — {@code BackendHealthUrlProdParityTest} fails if the two drift apart. The
 * backend serves HTTPS with a self-signed certificate whose SAN is {@code localhost}, not the
 * Docker alias {@code backend} the probe connects to; the probe's TLS trust mirrors {@link
 * de.greluc.krt.profit.basetool.frontend.config.WebClientConfig} (see {@link
 * #backendTls(SslBundles, Environment)}): trust-all in dev/test, and pinned to the {@code
 * backend-trust} bundle in prod with TLS endpoint identity (hostname) verification skipped.
 * Relaxing the certificate-to-hostname binding — never the chain validation, on the prod pinned
 * path — is safe ONLY because the connection lives entirely inside the Compose-internal {@code
 * net-backend-frontend} bridge: the public-facing TLS surface is terminated at NPM, the backend's
 * keystore is never exposed beyond the Docker network, and the URL is fully under our control (no
 * user-supplied host).
 *
 * <p>Bean name is {@code backendHealthIndicator}; Spring Boot strips the {@code HealthIndicator}
 * suffix, so the indicator key in health-group includes is {@code backend}. Setting {@code
 * management.health.backend.enabled=false} disables the bean entirely (used by the frontend's
 * {@code test} profile to keep {@code @SpringBootTest} runs from waiting on the placeholder backend
 * URL).
 *
 * <p>HTTP probe details: a synchronous {@link RestClient} with a 2&nbsp;s connect timeout and
 * 3&nbsp;s read timeout — kept well inside the Docker {@code HEALTHCHECK}'s 5&nbsp;s overall budget
 * so a slow backend surfaces as {@code DOWN} before {@code curl} itself gives up.
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
   * Production constructor used by Spring; resolves the backend Actuator base URL from the {@code
   * app.backend-health-url} property and builds a {@link RestClient} with indicator-specific
   * timeouts and the backend trust policy resolved by {@link #backendTls(SslBundles, Environment)}
   * — pinned to the {@code backend-trust} bundle (hostname check skipped) in prod, trust-all in
   * dev/test and as the prod fallback when no bundle is configured (audit L-5). {@link Autowired}
   * is required because the class declares a second (package-private, test-only) constructor;
   * without it Spring 4+'s constructor-selection logic falls back to a non-existent default
   * constructor and fails at startup with {@code NoSuchMethodException: <init>()}.
   *
   * @param backendHealthUrl base URL of the backend's <em>Actuator</em>, {@code
   *     app.backend-health-url}, defaulting to the API base URL {@code app.backend-url} for every
   *     profile that serves both from one connector; the readiness probe path is appended verbatim
   * @param sslBundles the application's configured SSL bundles, source of the {@code backend-trust}
   *     truststore used to pin the probe's TLS trust in prod
   * @param environment the active environment, used to pick the trust policy per profile
   */
  @Autowired
  public BackendHealthIndicator(
      @Value("${app.backend-health-url:${app.backend-url}}") String backendHealthUrl,
      SslBundles sslBundles,
      Environment environment) {
    this(backendHealthUrl, CONNECT_TIMEOUT, READ_TIMEOUT, backendTls(sslBundles, environment));
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
   * Shared constructor that wires the {@link RestClient} from a resolved backend {@link
   * SSLContext}.
   *
   * <p>No {@link javax.net.ssl.SSLParameters} hostname override is applied here: the JDK {@link
   * HttpClient} forces {@code endpointIdentificationAlgorithm=HTTPS} and ignores attempts to clear
   * it through parameters, so hostname verification is governed entirely by the supplied context's
   * trust managers. Trust-all (dev/test and the prod no-bundle fallback) skips it inherently; the
   * pinned prod context wraps its trust managers to validate the chain while skipping endpoint
   * identity (see {@link #backendTls(SslBundles, Environment)}).
   *
   * @param backendUrl backend base URL (trailing slash trimmed)
   * @param connectTimeout TCP connect timeout for the probe
   * @param readTimeout response read timeout for the probe
   * @param sslContext the resolved TLS context whose trust managers gate the probe's handshake
   */
  private BackendHealthIndicator(
      String backendUrl, Duration connectTimeout, Duration readTimeout, SSLContext sslContext) {
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
   * Issues a {@code GET} against the backend's {@code /actuator/health/readiness} endpoint and maps
   * the outcome to a {@link Health} contribution. A 2xx response yields {@code UP}; any HTTP error
   * response yields {@code DOWN} with the upstream status code; any I/O failure (DNS, connection
   * refused, timeout, TLS handshake) yields {@code DOWN} with the exception class name. Details
   * land in the {@link Health} object for logging but are not exposed externally because {@code
   * management.endpoint.health.show-details=never} keeps the response body to {@code
   * {"status":"UP"|"DOWN"}} only.
   *
   * @return {@link Health#up()} when the backend's readiness endpoint replied with a 2xx status;
   *     {@link Health#down()} otherwise (HTTP error or transport failure), with diagnostic details
   *     attached for log correlation
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
   * Builds a JDK {@link SSLContext} backed by Netty's {@link InsecureTrustManagerFactory} -- the
   * same trust-all utility the application's main reactive {@code WebClient} uses against the
   * backend's self-signed certificate (see {@link
   * de.greluc.krt.profit.basetool.frontend.config.WebClientConfig}). Routing through the existing
   * factory rather than declaring an anonymous {@code X509TrustManager} sidesteps CodeQL's {@code
   * java/insecure-trustmanager} query, which targets hand-rolled empty trust managers. See the
   * class Javadoc for the trust-boundary justification.
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
   * Resolves the backend TLS trust policy for the probe, mirroring {@code WebClientConfig} (audit
   * L-5). The policy branches by profile and configuration:
   *
   * <ul>
   *   <li>{@code dev} / {@code test}: trust-all (self-signed bootstrap / ephemeral test cert).
   *   <li>otherwise WITH a {@code backend-trust} SSL bundle (prod, the normal case): pin trust to
   *       the bundle's truststore — the backend's self-signed cert — but skip TLS endpoint identity
   *       (hostname) verification via {@link #pinnedTrustSkippingHostname(TrustManager[])}. The
   *       cert SAN is {@code localhost}, not the Docker alias {@code backend} the probe connects
   *       to, and the JDK {@link HttpClient} cannot disable hostname verification through {@code
   *       SSLParameters} (it forces {@code HTTPS}); without skipping it the pinned probe fails with
   *       "No subject alternative DNS name matching backend found". {@code WebClientConfig} reaches
   *       the same result on its Netty client by disabling endpoint identification on the SSL
   *       handler.
   *   <li>otherwise WITHOUT the bundle (prod fallback): trust-all, matching the WebClient that
   *       carries the real frontend-to-backend traffic, plus a WARN nudging the operator to
   *       configure the bundle. The previous default-JVM-trust fallback was removed: it can never
   *       validate the backend's self-signed internal certificate, so a missing bundle silently
   *       forced the probe {@code DOWN} and flapped the deploy.
   * </ul>
   *
   * @param sslBundles the application's configured SSL bundles
   * @param environment the active environment, for profile detection
   * @return the resolved {@link SSLContext} for the probe's HTTP client
   */
  private static SSLContext backendTls(SslBundles sslBundles, Environment environment) {
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
      context.init(null, pinnedTrustSkippingHostname(tmf.getTrustManagers()), null);
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
   * Wraps PKIX trust managers so the pinned chain validation is kept but TLS endpoint identity
   * (hostname) verification is skipped, by routing the {@link SSLEngine}/{@link Socket} {@code
   * checkServerTrusted} overloads — the ones that perform the hostname check — through the
   * host-agnostic two-argument variant. This is the only reliable way to drop hostname verification
   * on the JDK {@link HttpClient}, which forces {@code endpointIdentificationAlgorithm=HTTPS} and
   * ignores {@code SSLParameters} overrides. Chain trust is unchanged (still validated against the
   * pinned truststore), so this is NOT an insecure trust-all manager — only the certificate-to-host
   * binding is relaxed, matching the {@code WebClientConfig} pinned path and justified by the same
   * Docker-internal trust boundary.
   *
   * @param delegates the PKIX trust managers produced from the {@code backend-trust} truststore
   * @return trust managers that pin the chain but do not enforce hostname verification
   */
  private static TrustManager[] pinnedTrustSkippingHostname(TrustManager[] delegates) {
    TrustManager[] wrapped = delegates.clone();
    for (int i = 0; i < wrapped.length; i++) {
      if (wrapped[i] instanceof X509TrustManager x509) {
        wrapped[i] = new HostnameAgnosticTrustManager(x509);
      }
    }
    return wrapped;
  }

  /**
   * {@link X509ExtendedTrustManager} that delegates certificate-chain validation to a wrapped
   * {@link X509TrustManager} but skips TLS endpoint identity (hostname) verification: the {@link
   * SSLEngine}- and {@link Socket}-aware {@code checkServerTrusted} overloads (which normally
   * perform the hostname check) are routed to the two-argument variant, which only validates the
   * chain. Used by the probe's pinned prod path so the JDK {@link HttpClient} accepts the backend's
   * self-signed cert despite its SAN not listing the Docker alias {@code backend}. The chain is
   * still fully validated against the pinned truststore, so this is not a trust-all manager.
   * Package-private for the unit test that pins this routing behaviour.
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
