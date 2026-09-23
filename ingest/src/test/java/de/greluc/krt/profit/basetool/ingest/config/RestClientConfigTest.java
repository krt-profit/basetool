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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.ingest.logging.BackendCallLoggingInterceptor;
import de.greluc.krt.profit.basetool.ingest.service.ServiceAccountTokenProvider;
import de.greluc.krt.profit.basetool.ingest.support.TestLoggingProperties;
import de.greluc.krt.profit.basetool.ingest.support.TestProperties;
import de.greluc.krt.profit.basetool.ingest.support.TestSslBundles;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.security.KeyStore;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ssl.DefaultSslBundleRegistry;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Covers the profile-gated TLS trust of the gateway's two outbound clients (audit finding M-13)
 * after the move from WebClient to {@code RestClient} on the JDK client (ADR-0204), against a real
 * HTTPS server.
 *
 * <p>The certificates are throwaways minted in memory for {@code backend} — deliberately NOT for
 * {@code localhost}, the name the tests dial — so every case separates the two checks the old
 * Reactor Netty client made independently: whether the chain is trusted, and whether the hostname
 * is verified. The backend relay must accept a pinned-but-misnamed certificate (the service-alias
 * cert has no matching SAN) and still refuse an unpinned one; the Keycloak client must refuse the
 * misnamed one even though its chain is pinned, because it verifies the hostname.
 */
class RestClientConfigTest {

  /** A certificate for the docker service alias, which the tests reach as {@code localhost}. */
  private static final HeldCertificate BACKEND_CERT =
      new HeldCertificate.Builder().addSubjectAlternativeName("backend").build();

  /** A certificate whose name matches what the tests dial. */
  private static final HeldCertificate LOCALHOST_CERT =
      new HeldCertificate.Builder().addSubjectAlternativeName("localhost").build();

  private static IngestProperties properties(String backendBaseUrl, boolean verifyHostname) {
    return TestProperties.ingest(
        "backend-base-url",
        backendBaseUrl,
        "max-payload-bytes",
        String.valueOf(4096L),
        "verify-backend-hostname",
        String.valueOf(verifyHostname));
  }

  private static RestClientConfig config(
      String backendBaseUrl, String[] activeProfiles, SslBundles sslBundles) {
    return config(backendBaseUrl, activeProfiles, sslBundles, false);
  }

  private static RestClientConfig config(
      String backendBaseUrl,
      String[] activeProfiles,
      SslBundles sslBundles,
      boolean verifyHostname) {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles(activeProfiles);
    return new RestClientConfig(
        properties(backendBaseUrl, verifyHostname),
        TestProperties.serviceAccount(),
        environment,
        sslBundles,
        ObservationRegistry.NOOP,
        new BackendCallLoggingInterceptor(TestLoggingProperties.defaults()));
  }

  private static KeyStore trustStoreWith(HeldCertificate certificate) throws Exception {
    KeyStore store = KeyStore.getInstance("PKCS12");
    store.load(null, null);
    store.setCertificateEntry("pinned", certificate.certificate());
    return store;
  }

  private static MockWebServer httpsServer(HeldCertificate certificate) throws Exception {
    MockWebServer server = new MockWebServer();
    HandshakeCertificates serverCertificates =
        new HandshakeCertificates.Builder().heldCertificate(certificate).build();
    server.useHttps(serverCertificates.sslSocketFactory(), false);
    server.enqueue(new MockResponse().setBody("{}"));
    server.start();
    return server;
  }

  private static String httpsUrl(MockWebServer server) {
    return "https://localhost:" + server.getPort() + "/";
  }

  @Test
  void theRelayAcceptsThePinnedCertificateWithoutAHostnameCheck() throws Exception {
    try (MockWebServer backend = httpsServer(BACKEND_CERT)) {
      RestClient client =
          config(
                  httpsUrl(backend),
                  new String[] {"prod"},
                  TestSslBundles.withTrustStore("backend-trust", trustStoreWith(BACKEND_CERT)))
              .backendRestClient();

      assertThat(client.get().uri("/api/v1/ping").retrieve().body(String.class)).isEqualTo("{}");
    }
  }

  @Test
  void withHostnameVerificationTheRelayRefusesAPinnedButMisnamedCertificate() throws Exception {
    // REQ-SEC-070 / ING-SEC-04: once one internal CA signs every service the pinned anchor
    // vouches for all of them, so the name has to be checked. Same pinned chain as the default
    // case above, verification on: refused.
    try (MockWebServer backend = httpsServer(BACKEND_CERT)) {
      RestClient client =
          config(
                  httpsUrl(backend),
                  new String[] {"prod"},
                  TestSslBundles.withTrustStore("backend-trust", trustStoreWith(BACKEND_CERT)),
                  true)
              .backendRestClient();

      assertThatThrownBy(() -> client.get().uri("/api/v1/ping").retrieve().body(String.class))
          .isInstanceOf(ResourceAccessException.class);
    }
  }

  @Test
  void withHostnameVerificationTheRelayAcceptsAPinnedCorrectlyNamedCertificate() throws Exception {
    // ...and the matching name is accepted, so the refusal above was the name and nothing else.
    try (MockWebServer backend = httpsServer(LOCALHOST_CERT)) {
      RestClient client =
          config(
                  httpsUrl(backend),
                  new String[] {"prod"},
                  TestSslBundles.withTrustStore("backend-trust", trustStoreWith(LOCALHOST_CERT)),
                  true)
              .backendRestClient();

      assertThat(client.get().uri("/api/v1/ping").retrieve().body(String.class)).isEqualTo("{}");
    }
  }

  @Test
  void underDevAndTestHostnameVerificationChangesNothing() throws Exception {
    try (MockWebServer backend = httpsServer(BACKEND_CERT)) {
      RestClient client =
          config(httpsUrl(backend), new String[] {"dev"}, new DefaultSslBundleRegistry(), true)
              .backendRestClient();

      assertThat(client.get().uri("/api/v1/ping").retrieve().body(String.class)).isEqualTo("{}");
    }
  }

  @Test
  void theRelayStillRefusesACertificateThatIsNotPinned() throws Exception {
    HeldCertificate other =
        new HeldCertificate.Builder().addSubjectAlternativeName("backend").build();
    try (MockWebServer backend = httpsServer(BACKEND_CERT)) {
      RestClient client =
          config(
                  httpsUrl(backend),
                  new String[] {"prod"},
                  TestSslBundles.withTrustStore("backend-trust", trustStoreWith(other)))
              .backendRestClient();

      assertThatThrownBy(() -> client.get().uri("/api/v1/ping").retrieve().body(String.class))
          .isInstanceOf(ResourceAccessException.class);
    }
  }

  @Test
  void withoutABundleTheRelayFallsBackToTheJvmTrustStore() throws Exception {
    // A publicly-trusted / corporate-CA backend cert needs no pin. A self-signed one is therefore
    // refused here — the fallback is the JVM's anchors, not "trust anything".
    try (MockWebServer backend = httpsServer(LOCALHOST_CERT)) {
      RestClient client =
          config(httpsUrl(backend), new String[] {"prod"}, new DefaultSslBundleRegistry())
              .backendRestClient();

      assertThatThrownBy(() -> client.get().uri("/api/v1/ping").retrieve().body(String.class))
          .isInstanceOf(ResourceAccessException.class);
    }
  }

  @Test
  void underDevAndTestTheRelayTrustsTheEphemeralCertificate() throws Exception {
    for (String profile : new String[] {"dev", "test"}) {
      try (MockWebServer backend = httpsServer(BACKEND_CERT)) {
        RestClient client =
            config(httpsUrl(backend), new String[] {profile}, new DefaultSslBundleRegistry())
                .backendRestClient();

        assertThat(client.get().uri("/api/v1/ping").retrieve().body(String.class))
            .as("profile %s", profile)
            .isEqualTo("{}");
      }
    }
  }

  @Test
  void theKeycloakClientVerifiesTheHostnameEvenForAPinnedCertificate() throws Exception {
    // Pinned chain, wrong name: refused, because the token endpoint keeps hostname verification.
    try (MockWebServer keycloak = httpsServer(BACKEND_CERT)) {
      RestClient client =
          config(
                  "http://localhost:1/",
                  new String[] {"prod"},
                  TestSslBundles.withTrustStore(
                      KeycloakTrustSupport.KEYCLOAK_TRUST_BUNDLE, trustStoreWith(BACKEND_CERT)))
              .keycloakRestClient();

      assertThatThrownBy(() -> client.get().uri(httpsUrl(keycloak)).retrieve().body(String.class))
          .isInstanceOf(ResourceAccessException.class);
    }
    // Pinned chain, matching name: accepted — so the refusal above was the hostname and nothing
    // else.
    try (MockWebServer keycloak = httpsServer(LOCALHOST_CERT)) {
      RestClient client =
          config(
                  "http://localhost:1/",
                  new String[] {"prod"},
                  TestSslBundles.withTrustStore(
                      KeycloakTrustSupport.KEYCLOAK_TRUST_BUNDLE, trustStoreWith(LOCALHOST_CERT)))
              .keycloakRestClient();

      assertThat(client.get().uri(httpsUrl(keycloak)).retrieve().body(String.class))
          .isEqualTo("{}");
    }
  }

  @Test
  void theTokenGrantIsBoundedByTheConfiguredTimeout() throws Exception {
    try (MockWebServer keycloak = new MockWebServer()) {
      keycloak.enqueue(
          new MockResponse()
              .setHeader("Content-Type", "application/json")
              .setBody("{\"access_token\":\"late\",\"expires_in\":300}")
              .setHeadersDelay(3, TimeUnit.SECONDS));
      keycloak.start();
      ServiceAccountProperties serviceAccount =
          TestProperties.serviceAccount(
              "token-uri",
              keycloak.url("/token").toString(),
              "client-id",
              "basetool-ingest-gateway",
              "client-secret",
              "s3cret",
              "timeout-millis",
              "300");
      MockEnvironment environment = new MockEnvironment();
      environment.setActiveProfiles("test");
      RestClient keycloakClient =
          new RestClientConfig(
                  properties("http://localhost:1/", false),
                  serviceAccount,
                  environment,
                  new DefaultSslBundleRegistry(),
                  ObservationRegistry.NOOP,
                  new BackendCallLoggingInterceptor(TestLoggingProperties.defaults()))
              .keycloakRestClient();
      ServiceAccountTokenProvider provider =
          new ServiceAccountTokenProvider(
              serviceAccount, keycloakClient, new SimpleMeterRegistry());

      long start = System.nanoTime();
      assertThatThrownBy(provider::currentToken)
          .isInstanceOf(ServiceAccountTokenProvider.ServiceAccountTokenException.class);
      assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
          .as("the grant must give up at timeout-millis, not wait for the slow answer")
          .isLessThan(2_500L);
    }
  }

  @Test
  void aBackendResponsePastThePayloadCapFailsTheRelay() throws Exception {
    try (MockWebServer backend = new MockWebServer()) {
      backend.enqueue(new MockResponse().setBody("x".repeat(4097)));
      backend.start();
      RestClient client =
          config(backend.url("/").toString(), new String[] {"test"}, new DefaultSslBundleRegistry())
              .backendRestClient();

      assertThatThrownBy(() -> client.get().uri("/api/v1/big").retrieve().body(String.class))
          .isInstanceOf(RestClientException.class);
    }
  }

  @Test
  void relaysThroughTheConfiguredBaseUrlOverHttp11() throws Exception {
    try (MockWebServer backend = new MockWebServer()) {
      backend.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
      backend.start();
      RestClient client =
          config(backend.url("/").toString(), new String[] {"test"}, new DefaultSslBundleRegistry())
              .backendRestClient();

      String body = client.get().uri("/api/v1/ping").retrieve().body(String.class);

      assertThat(body).isEqualTo("{}");
      RecordedRequest recorded = backend.takeRequest(5, TimeUnit.SECONDS);
      assertThat(recorded).isNotNull();
      assertThat(recorded.getPath()).isEqualTo("/api/v1/ping");
      // Pinned to HTTP/1.1, as the replaced Reactor Netty client spoke: no h2c upgrade offer.
      assertThat(recorded.getRequestLine()).endsWith("HTTP/1.1");
      assertThat(recorded.getHeader("Upgrade")).isNull();
    }
  }
}
