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
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Verifies the backend relay forwards the caller's bearer and the locale / correlation headers to
 * the correct backend paths and returns the backend body verbatim (REQ-INGEST-001, REQ-OBS-*).
 */
class BackendImportClientTest {

  private MockWebServer backend;
  private BackendImportClient client;

  @BeforeEach
  void setUp() throws Exception {
    backend = new MockWebServer();
    backend.start();
    WebClient webClient = WebClient.builder().baseUrl(backend.url("/").toString()).build();
    client =
        new BackendImportClient(
            webClient, CircuitBreakerRegistry.ofDefaults(), TestLoggingProperties.defaults());
  }

  @AfterEach
  void tearDown() throws Exception {
    backend.shutdown();
  }

  private static RefineryExtractDto sampleExtract() {
    RefineryExtractGoodDto good =
        new RefineryExtractGoodDto(0, "Iron", 0, 1, 1, Boolean.TRUE, null, null);
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
            List.of(),
            List.of(good));
    return new RefineryExtractDto(1, "extractor", "1.0", "model", null, "de", List.of(order));
  }

  @Test
  void shouldForwardRefineryExtractWithBearerAndHeaders() throws Exception {
    // Given
    backend.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json")
            .setBody("{\"goodsMatched\":1}"));

    // When
    String body = client.forwardRefineryExtract("tok-123", "de", "cid-9", sampleExtract());

    // Then
    assertThat(body).isEqualTo("{\"goodsMatched\":1}");
    RecordedRequest request = backend.takeRequest();
    assertThat(request.getMethod()).isEqualTo("POST");
    assertThat(request.getPath()).isEqualTo("/api/v1/refinery-orders/import-extract");
    assertThat(request.getHeader("Authorization")).isEqualTo("Bearer tok-123");
    assertThat(request.getHeader("Accept-Language")).isEqualTo("de");
    assertThat(request.getHeader("X-Correlation-Id")).isEqualTo("cid-9");
    assertThat(request.getHeader("Content-Type")).contains("application/json");
  }

  @Test
  void shouldOmitTheOptionalRelayHeadersWhenTheyAreAbsentOrBlank() throws Exception {
    // Given: the controller passes through whatever the caller sent, so a missing Accept-Language
    // and a whitespace-only correlation id both have to end up as "header not set" rather than as
    // an empty header the backend would then have to defend against.
    backend.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json")
            .setBody("{}"));

    // When
    client.forwardRefineryExtract("tok-123", "   ", "  ", sampleExtract());

    // Then
    RecordedRequest request = backend.takeRequest();
    assertThat(request.getHeader("Authorization")).isEqualTo("Bearer tok-123");
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
    client.forwardBlueprintPreview("tok-abc", "en", "cid-3", "{}".getBytes(StandardCharsets.UTF_8));

    // Then
    RecordedRequest request = backend.takeRequest();
    assertThat(request.getHeader("Accept-Language")).isEqualTo("en");
    assertThat(request.getHeader("X-Correlation-Id")).isEqualTo("cid-3");
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
    String body = client.forwardBlueprintPreview("tok-abc", null, null, json);

    // Then
    assertThat(body).isEqualTo("{\"total\":3}");
    RecordedRequest request = backend.takeRequest();
    assertThat(request.getMethod()).isEqualTo("POST");
    assertThat(request.getPath()).isEqualTo("/api/v1/personal-blueprints/import/preview");
    assertThat(request.getHeader("Authorization")).isEqualTo("Bearer tok-abc");
    assertThat(request.getHeader("Content-Type")).contains("multipart/form-data");
    String sent = request.getBody().readUtf8();
    assertThat(sent).contains("name=\"file\"").contains("blueprints");
  }
}
