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

import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Proves the W3C trace-context propagation on the ingest&rarr;backend relay hop (REQ-OBS-009, epic
 * #936 Phase 1b): with tracing enabled, a request through the hand-built {@code backendWebClient}
 * (wired to the observation registry in {@link WebClientConfig}) carries a {@code traceparent}
 * header to the (mocked) backend. OTLP export stays off — no exporter, no network export; the
 * propagation path alone is under test.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
      "management.opentelemetry.enabled=true",
      "management.tracing.export.otlp.enabled=false"
    })
class MonitoringTracingPropagationTest {

  private static MockWebServer mockBackend;

  @Autowired private WebClient backendWebClient;

  @MockitoBean private JwtDecoder jwtDecoder;

  @BeforeAll
  static void startServer() throws Exception {
    mockBackend = new MockWebServer();
    mockBackend.start();
  }

  @AfterAll
  static void stopServer() throws Exception {
    mockBackend.shutdown();
  }

  /**
   * Points the gateway's backend base URL at the mock server before the context binds {@code
   * IngestProperties}.
   *
   * @param registry the dynamic property registry of the test context
   */
  @DynamicPropertySource
  static void backendUrl(DynamicPropertyRegistry registry) {
    registry.add("app.ingest.backend-base-url", () -> mockBackend.url("/").toString());
  }

  @Test
  void shouldPropagateTraceparentHeaderOnBackendRelayCall() throws Exception {
    // Given
    mockBackend.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

    // When
    backendWebClient.get().uri("/api/v1/settings").retrieve().toBodilessEntity().block();
    RecordedRequest recorded = mockBackend.takeRequest(10, TimeUnit.SECONDS);

    // Then: the instrumented client injected the W3C trace context (version-traceId-spanId-flags).
    assertThat(recorded).isNotNull();
    assertThat(recorded.getHeader("traceparent"))
        .as("backend relay call must carry the W3C traceparent header")
        .isNotBlank()
        .matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");
  }
}
