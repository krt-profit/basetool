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

package de.greluc.krt.profit.basetool.ingest.service;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.ingest.model.dto.RefineryExtractDto;
import de.greluc.krt.profit.basetool.ingest.model.dto.RefineryExtractGoodDto;
import de.greluc.krt.profit.basetool.ingest.model.dto.RefineryExtractImageDto;
import de.greluc.krt.profit.basetool.ingest.model.dto.RefineryExtractOrderDto;
import de.greluc.krt.profit.basetool.ingest.support.TestLoggingProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.nio.charset.StandardCharsets;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Verifies the backend relay calls the correct backend paths as the gateway itself — its own bearer
 * plus the on-behalf-of header naming the caller (ADR-0129) — forwards the locale / correlation
 * headers, and returns the backend body verbatim (REQ-INGEST-001, REQ-OBS-*).
 */
class BackendImportClientTest {

  private MockWebServer backend;
  private BackendImportClient client;

  /**
   * Stands in for the gateway's own identity. The relay no longer forwards the caller's token
   * (ADR-0129), so every outbound call now needs one — a fixed value keeps the assertions readable
   * and makes a leaked CALLER token immediately visible as "not this string".
   */
  private final ServiceAccountTokenProvider serviceAccountTokenProvider =
      org.mockito.Mockito.mock(ServiceAccountTokenProvider.class);

  @BeforeEach
  void setUp() throws Exception {
    org.mockito.Mockito.when(serviceAccountTokenProvider.currentToken())
        .thenReturn("gateway-token");
    backend = new MockWebServer();
    backend.start();
    WebClient webClient = WebClient.builder().baseUrl(backend.url("/").toString()).build();
    client =
        new BackendImportClient(
            webClient,
            serviceAccountTokenProvider,
            CircuitBreakerRegistry.ofDefaults(),
            TestLoggingProperties.defaults());
  }

  @AfterEach
  void tearDown() throws Exception {
    MDC.clear();
    backend.shutdown();
  }

  private static RefineryExtractDto sampleExtract() {
    RefineryExtractGoodDto good =
        new RefineryExtractGoodDto(0, "Iron", 0, 1, 1, Boolean.TRUE, null, null);
    RefineryExtractImageDto image =
        new RefineryExtractImageDto("shot.png", 1920, 1080, "vlm", null);
    RefineryExtractOrderDto order =
        new RefineryExtractOrderDto(
            "SETUP",
            Boolean.TRUE,
            null,
            "ARC-L1",
            "Dinyx Solventation",
            null,
            null,
            null,
            null,
            null,
            List.of(image),
            List.of(good));
    return new RefineryExtractDto(1, "extractor", "1.0", "model", null, "de", List.of(order));
  }

  @Test
  void shouldCallTheBackendAsTheGatewayNamingTheCaller() throws Exception {
    // Given
    backend.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json")
            .setBody("{\"goodsMatched\":1}"));

    // When
    MDC.put("correlationId", "cid-9");
    String body = client.forwardRefineryExtract("caller-sub", "de", sampleExtract());

    // Then
    assertThat(body).isEqualTo("{\"goodsMatched\":1}");
    RecordedRequest request = backend.takeRequest();
    assertThat(request.getMethod()).isEqualTo("POST");
    assertThat(request.getPath()).isEqualTo("/api/v1/refinery-orders/import-extract");
    assertThat(request.getHeader("Authorization")).isEqualTo("Bearer gateway-token");
    assertThat(request.getHeader(BackendImportClient.ON_BEHALF_OF_HEADER)).isEqualTo("caller-sub");
    assertThat(request.getHeader("Accept-Language")).isEqualTo("de");
    assertThat(request.getHeader("X-Correlation-Id")).isEqualTo("cid-9");
    assertThat(request.getHeader("Content-Type")).contains("application/json");
  }

  @Test
  void shouldOmitTheOptionalRelayHeadersWhenTheyAreAbsentOrBlank() throws Exception {
    // Given: a missing Accept-Language and an unset MDC correlation id both have to end up as
    // "header not set" rather than as an empty header the backend would then have to defend
    // against.
    backend.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json")
            .setBody("{}"));

    // When
    client.forwardRefineryExtract("caller-sub", "   ", sampleExtract());

    // Then
    RecordedRequest request = backend.takeRequest();
    assertThat(request.getHeader("Authorization")).isEqualTo("Bearer gateway-token");
    assertThat(request.getHeader(BackendImportClient.ON_BEHALF_OF_HEADER)).isEqualTo("caller-sub");
    assertThat(request.getHeader("Accept-Language")).isNull();
    assertThat(request.getHeader("X-Correlation-Id")).isNull();
  }

  @Test
  void shouldRelayTheCorrelationIdOnTheBlueprintPathToo() throws Exception {
    // Given
    backend.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json")
            .setBody("{}"));

    // When
    MDC.put("correlationId", "cid-3");
    client.forwardBlueprintPreview("caller-sub", "en", "{}".getBytes(StandardCharsets.UTF_8));

    // Then
    RecordedRequest request = backend.takeRequest();
    assertThat(request.getHeader("Accept-Language")).isEqualTo("en");
    assertThat(request.getHeader("X-Correlation-Id")).isEqualTo("cid-3");
  }

  @Test
  void shouldTakeTheCorrelationIdFromTheMdcRatherThanFromTheInboundHeader() throws Exception {
    // Given: the security fix. CorrelationIdFilter validates the inbound header and, when it fails,
    // puts a freshly minted id in the MDC — the relay must carry THAT one. Forwarding the raw
    // header
    // instead let unvalidated internet input be copied onto the internal backend call, and it split
    // one request across two different correlation ids in the two modules' logs (REQ-OBS-002).
    backend.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json")
            .setBody("{}"));
    MDC.put("correlationId", "sanitised-id");

    // When
    client.forwardRefineryExtract("caller-sub", "de", sampleExtract());

    // Then: exactly the MDC value, and only one such header on the wire.
    RecordedRequest request = backend.takeRequest();
    assertThat(request.getHeaders().values("X-Correlation-Id")).containsExactly("sanitised-id");
  }

  @Test
  void shouldDropAnAcceptLanguageThatIsNotAPlainLanguageRange() throws Exception {
    // Given: a CRLF-bearing locale is the header-injection shape. It must never reach the outbound
    // request — not even to be rejected by the transport, which would surface as an opaque 500.
    backend.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json")
            .setBody("{}"));

    // When
    client.forwardRefineryExtract("caller-sub", "de\r\nX-Injected: evil", sampleExtract());

    // Then: the locale is dropped entirely and no smuggled header exists.
    RecordedRequest request = backend.takeRequest();
    assertThat(request.getHeader("Accept-Language")).isNull();
    assertThat(request.getHeader("X-Injected")).isNull();
  }

  @Test
  void shouldDropAnOverlongAcceptLanguage() throws Exception {
    // Given: a real Accept-Language is a handful of ranges; a 500-character one is not content
    // negotiation, so it is dropped rather than copied onto the internal call.
    backend.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json")
            .setBody("{}"));

    // When
    client.forwardRefineryExtract("caller-sub", "de,".repeat(200), sampleExtract());

    // Then
    RecordedRequest request = backend.takeRequest();
    assertThat(request.getHeader("Accept-Language")).isNull();
  }

  @Test
  void shouldKeepAWellFormedWeightedAcceptLanguage() throws Exception {
    // Given: the guard must not be so strict that it breaks real content negotiation — a q-weighted
    // multi-range header is exactly what a browser-adjacent client sends.
    backend.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json")
            .setBody("{}"));

    // When
    client.forwardRefineryExtract("caller-sub", "de-DE,de;q=0.9,en;q=0.8,*;q=0.5", sampleExtract());

    // Then
    RecordedRequest request = backend.takeRequest();
    assertThat(request.getHeader("Accept-Language")).isEqualTo("de-DE,de;q=0.9,en;q=0.8,*;q=0.5");
  }

  @Test
  void shouldForwardBlueprintPreviewAsMultipart() throws Exception {
    // Given
    backend.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json")
            .setBody("{\"total\":3}"));
    byte[] json = "{\"blueprints\":[]}".getBytes(StandardCharsets.UTF_8);

    // When
    String body = client.forwardBlueprintPreview("caller-sub", null, json);

    // Then
    assertThat(body).isEqualTo("{\"total\":3}");
    RecordedRequest request = backend.takeRequest();
    assertThat(request.getMethod()).isEqualTo("POST");
    assertThat(request.getPath()).isEqualTo("/api/v1/personal-blueprints/import/preview");
    assertThat(request.getHeader("Authorization")).isEqualTo("Bearer gateway-token");
    assertThat(request.getHeader(BackendImportClient.ON_BEHALF_OF_HEADER)).isEqualTo("caller-sub");
    assertThat(request.getHeader("Content-Type")).contains("multipart/form-data");
    String sent = request.getBody().readUtf8();
    assertThat(sent).contains("name=\"file\"").contains("blueprints");
  }
}
