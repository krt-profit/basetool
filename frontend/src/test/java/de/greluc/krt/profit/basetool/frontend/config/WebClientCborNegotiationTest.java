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
import static org.mockito.Mockito.mock;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.time.Duration;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Verifies that the frontend requests CBOR from the backend while still writing JSON request bodies
 * (ADR-0161).
 */
class WebClientCborNegotiationTest {

  private MockWebServer server;

  @BeforeEach
  void startServer() throws Exception {
    server = new MockWebServer();
    server.start();
  }

  @AfterEach
  void stopServer() throws Exception {
    server.shutdown();
  }

  @Test
  @DisplayName("a backend read asks for CBOR first and JSON second")
  void readsAskForCborFirst() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(200).setBody("[]"));

    buildBackendClient(AppHttpProperties.BackendCodec.CBOR)
        .get()
        .uri(server.url("/api/v1/job-types").uri())
        .retrieve()
        .bodyToMono(String.class)
        .block(Duration.ofSeconds(10));

    RecordedRequest request = server.takeRequest();

    assertThat(request.getHeader("Accept")).isEqualTo("application/cbor, application/json");
  }

  @Test
  @DisplayName("the JSON setting puts the client back exactly where it was")
  void theJsonSettingRestoresTheOldHeader() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(200).setBody("[]"));

    buildBackendClient(AppHttpProperties.BackendCodec.JSON)
        .get()
        .uri(server.url("/api/v1/job-types").uri())
        .retrieve()
        .bodyToMono(String.class)
        .block(Duration.ofSeconds(10));

    assertThat(server.takeRequest().getHeader("Accept")).isEqualTo("application/json");
  }

  @Test
  @DisplayName("a write still goes out as JSON, even with CBOR on")
  void writesStayJson() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

    buildBackendClient(AppHttpProperties.BackendCodec.CBOR)
        .post()
        .uri(server.url("/api/v1/missions").uri())
        .bodyValue(Map.of("name", "probe"))
        .retrieve()
        .bodyToMono(String.class)
        .block(Duration.ofSeconds(10));

    RecordedRequest request = server.takeRequest();

    assertThat(request.getHeader("Content-Type")).startsWith("application/json");
    assertThat(request.getBody().readUtf8()).isEqualTo("{\"name\":\"probe\"}");
  }

  /**
   * Builds the real {@code webClient} bean for a given codec, with real Resilience4j registries.
   *
   * @param codec the setting under test
   * @return the built client
   */
  private static WebClient buildBackendClient(AppHttpProperties.BackendCodec codec) {
    return WebClientTestSupport.config(AppHttpProperties.BackendProtocol.H2, codec)
        .webClient(
            mock(OAuth2AuthorizedClientManager.class),
            CircuitBreakerRegistry.ofDefaults(),
            RetryRegistry.ofDefaults(),
            TimeLimiterRegistry.of(
                io.github.resilience4j.timelimiter.TimeLimiterConfig.custom()
                    .timeoutDuration(Duration.ofSeconds(20))
                    .build()),
            BulkheadRegistry.ofDefaults());
  }
}
