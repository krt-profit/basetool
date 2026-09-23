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

package de.greluc.krt.profit.basetool.frontend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.logging.ActiveSquadronRelayFilter;
import de.greluc.krt.profit.basetool.frontend.logging.ClientIpRelayFilter;
import de.greluc.krt.profit.basetool.frontend.logging.UserLocaleRelayFilter;
import de.greluc.krt.profit.basetool.frontend.logging.WebClientLoggingFilter;
import io.micrometer.observation.ObservationRegistry;
import java.security.KeyStore;
import java.time.Duration;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.ssl.SslStoreBundle;
import org.springframework.core.env.Environment;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Pins the frontend→backend TLS hop's hostname check on the pinned prod path (REQ-SEC-070,
 * ADR-0211), against a real HTTPS server.
 *
 * <p>Two certificates, both pinned in turn: one naming {@code localhost} — the host the tests dial
 * — and one naming only {@code backend}. With {@code app.http.verify-backend-hostname} off (the
 * default, and every deployment's behaviour before the per-service certificates) the pinned chain
 * alone decides, so a misnamed certificate is accepted. With it on, the misnamed one is refused and
 * the correctly named one still accepted — so the refusal is the name and nothing else. That
 * difference is the point of the per-service certificates: once one internal CA signs every
 * service, the chain vouches for all of them and only the name tells the backend apart.
 */
class BackendHostnameVerificationTest {

  /** A certificate for the docker service alias, which the tests reach as {@code localhost}. */
  private static final HeldCertificate BACKEND_CERT =
      new HeldCertificate.Builder().addSubjectAlternativeName("backend").build();

  /** A certificate whose name matches what the tests dial. */
  private static final HeldCertificate LOCALHOST_CERT =
      new HeldCertificate.Builder().addSubjectAlternativeName("localhost").build();

  @Test
  void byDefaultThePinnedChainAloneDecides() throws Exception {
    try (MockWebServer backend = httpsServer(BACKEND_CERT)) {
      assertThat(get(client(BACKEND_CERT, false), backend)).isEqualTo("ok");
    }
  }

  @Test
  void withVerificationOnAMisnamedCertificateIsRefused() throws Exception {
    try (MockWebServer backend = httpsServer(BACKEND_CERT)) {
      WebClient client = client(BACKEND_CERT, true);

      assertThatThrownBy(() -> get(client, backend)).isInstanceOf(RuntimeException.class);
    }
  }

  @Test
  void withVerificationOnACorrectlyNamedCertificateIsAccepted() throws Exception {
    try (MockWebServer backend = httpsServer(LOCALHOST_CERT)) {
      assertThat(get(client(LOCALHOST_CERT, true), backend)).isEqualTo("ok");
    }
  }

  /**
   * Calls the server once through the client.
   *
   * @param client the client under test.
   * @param server the server.
   * @return the response body.
   */
  private static String get(WebClient client, MockWebServer server) {
    return client
        .get()
        .uri("https://localhost:" + server.getPort() + "/ping")
        .retrieve()
        .bodyToMono(String.class)
        .block(Duration.ofSeconds(10));
  }

  /**
   * An HTTPS server presenting the given certificate and answering twice.
   *
   * @param certificate the server certificate.
   * @return the started server.
   * @throws Exception when the server cannot start.
   */
  private static MockWebServer httpsServer(HeldCertificate certificate) throws Exception {
    MockWebServer server = new MockWebServer();
    server.useHttps(
        new HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(),
        false);
    server.enqueue(new MockResponse().setBody("ok"));
    server.enqueue(new MockResponse().setBody("ok"));
    server.start();
    return server;
  }

  /**
   * The real {@link WebClientConfig} under the {@code prod} profile, its {@code backend-trust}
   * bundle pinning the given certificate — the path production takes.
   *
   * @param pinned the certificate the bundle pins.
   * @param verifyHostname {@code app.http.verify-backend-hostname}.
   * @return the backend WebClient (the streaming one: it needs no OAuth2 wiring and shares the
   *     trust logic of every other backend client).
   * @throws Exception when the truststore cannot be built.
   */
  private static WebClient client(HeldCertificate pinned, boolean verifyHostname) throws Exception {
    ExchangeFilterFunction passthrough = (request, next) -> next.exchange(request);
    WebClientLoggingFilter logging = mock(WebClientLoggingFilter.class);
    when(logging.correlationIdPropagation()).thenReturn(passthrough);
    when(logging.callLogging()).thenReturn(passthrough);
    ActiveSquadronRelayFilter squadron = mock(ActiveSquadronRelayFilter.class);
    when(squadron.relayActiveSquadron()).thenReturn(passthrough);
    UserLocaleRelayFilter locale = mock(UserLocaleRelayFilter.class);
    when(locale.relayUserLocale()).thenReturn(passthrough);
    Environment environment = mock(Environment.class);
    when(environment.getActiveProfiles()).thenReturn(new String[] {"prod"});

    return new WebClientConfig(
            new AppBackendProperties("https://backend:11261"),
            new AppHttpProperties(
                Duration.ofSeconds(3),
                Duration.ofSeconds(5),
                Duration.ofSeconds(120),
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                AppHttpProperties.BackendProtocol.HTTP11,
                20,
                AppHttpProperties.BackendCodec.JSON,
                verifyHostname),
            logging,
            squadron,
            locale,
            new ClientIpRelayFilter(),
            environment,
            bundles(pinned),
            ObservationRegistry.NOOP)
        .sseWebClient();
  }

  /**
   * An {@link SslBundles} whose {@code backend-trust} bundle pins exactly one certificate.
   *
   * @param pinned the certificate.
   * @return the bundles.
   * @throws Exception when the truststore cannot be built.
   */
  private static SslBundles bundles(HeldCertificate pinned) throws Exception {
    KeyStore truststore = KeyStore.getInstance("PKCS12");
    truststore.load(null, null);
    truststore.setCertificateEntry("pinned", pinned.certificate());
    SslBundles bundles = mock(SslBundles.class);
    when(bundles.getBundle("backend-trust"))
        .thenReturn(SslBundle.of(SslStoreBundle.of(null, null, truststore)));
    return bundles;
  }
}
