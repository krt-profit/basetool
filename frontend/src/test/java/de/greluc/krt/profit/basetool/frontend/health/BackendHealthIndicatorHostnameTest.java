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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.KeyStore;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.ssl.SslStoreBundle;
import org.springframework.core.env.Environment;

/**
 * Pins the backend health probe's hostname check on the pinned prod path (REQ-SEC-TBD04T): it
 * follows {@code app.http.verify-backend-hostname} exactly as the API client does, so a readiness
 * probe cannot keep the frontend "healthy" against a certificate the API client would refuse.
 */
class BackendHealthIndicatorHostnameTest {

  /** Names only the docker alias; the test dials {@code localhost}. */
  private static final HeldCertificate BACKEND_CERT =
      new HeldCertificate.Builder().addSubjectAlternativeName("backend").build();

  /** Names what the test dials. */
  private static final HeldCertificate LOCALHOST_CERT =
      new HeldCertificate.Builder().addSubjectAlternativeName("localhost").build();

  @Test
  void byDefaultAMisnamedButPinnedCertificateIsUp() throws Exception {
    assertThat(probe(BACKEND_CERT, false)).isEqualTo(Status.UP);
  }

  @Test
  void withVerificationOnAMisnamedCertificateIsDown() throws Exception {
    assertThat(probe(BACKEND_CERT, true)).isEqualTo(Status.DOWN);
  }

  @Test
  void withVerificationOnACorrectlyNamedCertificateIsUp() throws Exception {
    assertThat(probe(LOCALHOST_CERT, true)).isEqualTo(Status.UP);
  }

  /**
   * Probes an HTTPS readiness endpoint that presents {@code certificate}, with the {@code
   * backend-trust} bundle pinning that same certificate, under the {@code prod} profile.
   *
   * @param certificate the server certificate, also the pinned one.
   * @param verifyHostname {@code app.http.verify-backend-hostname}.
   * @return the probe's status.
   * @throws Exception when the server or the truststore cannot be set up.
   */
  private static Status probe(HeldCertificate certificate, boolean verifyHostname)
      throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.useHttps(
          new HandshakeCertificates.Builder()
              .heldCertificate(certificate)
              .build()
              .sslSocketFactory(),
          false);
      server.enqueue(
          new MockResponse()
              .setResponseCode(200)
              .setHeader("Content-Type", "application/json")
              .setBody("{\"status\":\"UP\"}"));
      server.start();
      KeyStore truststore = KeyStore.getInstance("PKCS12");
      truststore.load(null, null);
      truststore.setCertificateEntry("pinned", certificate.certificate());
      SslBundles bundles = mock(SslBundles.class);
      when(bundles.getBundle("backend-trust"))
          .thenReturn(SslBundle.of(SslStoreBundle.of(null, null, truststore)));
      Environment environment = mock(Environment.class);
      when(environment.getActiveProfiles()).thenReturn(new String[] {"prod"});

      return new BackendHealthIndicator(
              "https://localhost:" + server.getPort(), bundles, environment, verifyHostname)
          .health()
          .getStatus();
    }
  }
}
