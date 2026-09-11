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
 * What the frontend actually asks the backend for, and what it still sends (ADR-0161 §8.5).
 *
 * <p>{@code ApiCborNegotiationTest} proves the backend answers CBOR when asked. The half that lives
 * here is the asking — and one thing that must <em>not</em> have changed with it. Spring registers
 * the JSON encoder ahead of the CBOR one, so {@code bodyValue} keeps writing JSON request bodies
 * without being told to; that is a property of a framework ordering rather than of anything in this
 * repository, so it is asserted rather than relied on. A write path that silently turned binary
 * would reach every {@code consumes = APPLICATION_JSON_VALUE} endpoint as a 415.
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

    // The order IS the negotiation: Spring serves the first acceptable type it has a converter
    // for. JSON second is not a formality -- it is what keeps a response the backend types itself
    // (an RFC 7807 problem, a PDF export) readable.
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

    // Byte for byte the header this client sent before 2026-09-10, which is what makes
    // `app.http.codec=JSON` a way back rather than a different third behaviour.
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
   * Builds the real {@code webClient} bean for a given codec.
   *
   * <p>The collaborator doubles live in {@link WebClientTestSupport} rather than here: {@code
   * WebClientConfig} takes nine constructor arguments, and a second hand-maintained copy of them
   * meant every future collaborator forced an edit in two places, with a compile error as the only
   * warning.
   *
   * <p>Real Resilience4j registries rather than mocks: the bean wraps every exchange in the {@code
   * backendApi} chain, and a mocked registry would have to reproduce four operators to get one
   * request through. Their defaults let a single fast local call pass untouched.
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
