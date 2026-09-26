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

package de.greluc.krt.profit.basetool.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Pins the wire behaviour of the backend's {@code RestClient} on the JDK client (ADR-0204): the
 * read timeout is enforced, it speaks plain HTTP/1.1, and each builder is independent.
 */
class RestClientConfigTest {

  /**
   * A response whose headers arrive after the read timeout fails the call as a transport error
   * ({@link ResourceAccessException}) instead of hanging the scheduler thread.
   *
   * @throws Exception if the mock server cannot be started
   */
  @Test
  void aResponseSlowerThanTheReadTimeoutFailsAsATransportError() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(new MockResponse().setBody("{}").setHeadersDelay(3, TimeUnit.SECONDS));
      server.start();
      RestClient client =
          RestClient.builder()
              .requestFactory(
                  RestClientConfig.jdkRequestFactory(Duration.ofSeconds(5), Duration.ofMillis(300)))
              .baseUrl(server.url("/").toString())
              .build();

      assertThatThrownBy(() -> client.get().uri("/slow").retrieve().body(String.class))
          .isInstanceOf(ResourceAccessException.class);
    }
  }

  /**
   * The JDK client defaults to HTTP/2 and would offer {@code Upgrade: h2c} on every plain-HTTP
   * request; the configured client is pinned to HTTP/1.1, so the request line says so and no
   * upgrade is offered.
   *
   * @throws Exception if the mock server cannot be started
   */
  @Test
  void theClientSpeaksHttp11WithoutAnUpgradeOffer() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(new MockResponse().setBody("ok"));
      server.start();
      RestClient client =
          new RestClientConfig()
              .restClientBuilder(ObservationRegistry.NOOP)
              .baseUrl(server.url("/").toString())
              .build();

      assertThat(client.get().uri("/ping").retrieve().body(String.class)).isEqualTo("ok");

      RecordedRequest recorded = server.takeRequest(5, TimeUnit.SECONDS);
      assertThat(recorded).isNotNull();
      assertThat(recorded.getRequestLine()).endsWith("HTTP/1.1");
      assertThat(recorded.getHeader("Upgrade")).isNull();
    }
  }

  /**
   * Two builders from the configuration are independent: a base URL set on one does not appear on
   * the next, which is what lets {@code UexClient}, {@code ScWikiClient} and {@code
   * KeycloakService} each bind their own host.
   *
   * @throws Exception if the mock server cannot be started
   */
  @Test
  void eachBuilderIsIndependent() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(new MockResponse().setBody("ok"));
      server.start();
      RestClientConfig config = new RestClientConfig();
      RestClient.Builder first = config.restClientBuilder(ObservationRegistry.NOOP);
      first.baseUrl("http://first.invalid");
      RestClient second =
          config
              .restClientBuilder(ObservationRegistry.NOOP)
              .baseUrl(server.url("/").toString())
              .build();

      assertThat(second.get().uri("/ping").retrieve().body(String.class)).isEqualTo("ok");
      assertThat(first).isNotSameAs(config.restClientBuilder(ObservationRegistry.NOOP));
    }
  }
}
