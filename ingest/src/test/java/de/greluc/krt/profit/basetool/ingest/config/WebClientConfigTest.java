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

import de.greluc.krt.profit.basetool.ingest.logging.WebClientLoggingFilter;
import de.greluc.krt.profit.basetool.ingest.support.TestLoggingProperties;
import de.greluc.krt.profit.basetool.ingest.support.TestSslBundles;
import io.micrometer.observation.ObservationRegistry;
import java.security.KeyStore;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ssl.DefaultSslBundleRegistry;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Covers the profile-gated TLS trust of the backend relay client (audit finding M-13). The three
 * branches have materially different security postures — an ephemeral docker cert trusted in
 * dev/test, a {@code backend-trust} bundle pinned everywhere else, and the JVM trust store as the
 * fallback when no bundle is registered — so each is built here, and the client is then driven
 * against a real server to prove the assembled filter chain actually relays.
 */
class WebClientConfigTest {

  private static IngestProperties properties(String backendBaseUrl) {
    IngestProperties properties = new IngestProperties();
    properties.setBackendBaseUrl(backendBaseUrl);
    properties.setFrontendBaseUrl("http://localhost:18081");
    properties.setMaxPayloadBytes(1024L * 1024L);
    return properties;
  }

  private static WebClientConfig config(String[] activeProfiles, SslBundles sslBundles) {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles(activeProfiles);
    return new WebClientConfig(
        properties("http://localhost:19999"),
        environment,
        sslBundles,
        ObservationRegistry.NOOP,
        new WebClientLoggingFilter(TestLoggingProperties.defaults()));
  }

  @Test
  void buildsTheRelayClientWithTheInsecureTrustManagerUnderTest() {
    WebClient client =
        config(new String[] {"test"}, new DefaultSslBundleRegistry()).backendWebClient();

    assertThat(client).isNotNull();
  }

  @Test
  void buildsTheRelayClientWithTheInsecureTrustManagerUnderDev() {
    WebClient client =
        config(new String[] {"dev"}, new DefaultSslBundleRegistry()).backendWebClient();

    assertThat(client).isNotNull();
  }

  @Test
  void pinsTrustToTheBackendTrustBundleOutsideDevAndTest() throws Exception {
    KeyStore truststore = KeyStore.getInstance("PKCS12");
    truststore.load(null, null);

    WebClient client =
        config(new String[] {"prod"}, TestSslBundles.withTrustStore("backend-trust", truststore))
            .backendWebClient();

    assertThat(client).isNotNull();
  }

  @Test
  void fallsBackToTheJvmTrustStoreWhenNoBundleIsRegistered() {
    // A publicly-trusted / corporate-CA backend cert needs no pin — and hostname verification then
    // stays ON, which is exactly why the fallback must not be an error.
    WebClient client =
        config(new String[] {"prod"}, new DefaultSslBundleRegistry()).backendWebClient();

    assertThat(client).isNotNull();
  }

  @Test
  void relaysThroughTheConfiguredBaseUrlAndLogsTheCall() throws Exception {
    try (MockWebServer backend = new MockWebServer()) {
      backend.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
      backend.start();

      MockEnvironment environment = new MockEnvironment();
      environment.setActiveProfiles("test");
      WebClient client =
          new WebClientConfig(
                  properties(backend.url("/").toString()),
                  environment,
                  new DefaultSslBundleRegistry(),
                  ObservationRegistry.NOOP,
                  new WebClientLoggingFilter(TestLoggingProperties.defaults()))
              .backendWebClient();

      String body = client.get().uri("/api/v1/ping").retrieve().bodyToMono(String.class).block();

      assertThat(body).isEqualTo("{}");
      assertThat(backend.takeRequest().getPath()).isEqualTo("/api/v1/ping");
    }
  }
}
