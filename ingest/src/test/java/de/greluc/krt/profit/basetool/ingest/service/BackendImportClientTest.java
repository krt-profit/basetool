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
import de.greluc.krt.profit.basetool.ingest.support.TestProperties;
import de.greluc.krt.profit.basetool.ingest.web.GlobalExceptionHandler;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies the backend relay calls the correct backend paths as the gateway itself — its own bearer
 * plus the on-behalf-of header naming the caller (ADR-0129) — forwards the locale / correlation
 * headers, and returns the backend body verbatim (REQ-INGEST-001, REQ-OBS-*).
 */
class BackendImportClientTest {

  private MockWebServer backend;
  private BackendImportClient client;

  /**
   * Stands in for the gateway's own identity (ADR-0129) with a fixed value, so a forwarded caller
   * token would stand out in the assertions.
   */
  private final ServiceAccountTokenProvider serviceAccountTokenProvider =
      org.mockito.Mockito.mock(ServiceAccountTokenProvider.class);

  @BeforeEach
  void setUp() throws Exception {
    org.mockito.Mockito.when(serviceAccountTokenProvider.currentToken())
        .thenReturn("gateway-token");
    backend = new MockWebServer();
    backend.start();
    RestClient restClient = RestClient.builder().baseUrl(backend.url("/").toString()).build();
    client =
        new BackendImportClient(
            restClient,
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

  /**
   * Over real HTTP, a backend {@code 401} on the gateway's cached token surfaces as a {@link
   * RestClientResponseException}, becomes a {@code 502}, invalidates the cache, and the next relay
   * carries a freshly minted token.
   */
  @Test
  void aBackendAuthRefusalInvalidatesTheTokenSoTheNextRelayCarriesAFreshOne() throws Exception {
    try (MockWebServer keycloak = new MockWebServer()) {
      keycloak.enqueue(tokenAnswer("refused-token"));
      keycloak.enqueue(tokenAnswer("fresh-token"));
      keycloak.start();
      ServiceAccountTokenProvider realProvider =
          new ServiceAccountTokenProvider(
              TestProperties.serviceAccount(
                  "token-uri",
                  keycloak.url("/token").toString(),
                  "client-id",
                  "basetool-ingest-gateway",
                  "client-secret",
                  "s3cret"),
              RestClient.create(),
              new SimpleMeterRegistry());
      BackendImportClient relay =
          new BackendImportClient(
              RestClient.builder().baseUrl(backend.url("/").toString()).build(),
              realProvider,
              CircuitBreakerRegistry.ofDefaults(),
              TestLoggingProperties.defaults());
      GlobalExceptionHandler handler =
          new GlobalExceptionHandler(
              JsonMapper.builder().build(),
              new SimpleMeterRegistry(),
              TestLoggingProperties.defaults(),
              realProvider);
      backend.enqueue(new MockResponse().setResponseCode(401));
      backend.enqueue(
          new MockResponse()
              .setResponseCode(200)
              .addHeader("Content-Type", "application/json")
              .setBody("{\"goodsMatched\":1}"));

      RestClientResponseException refused =
          org.junit.jupiter.api.Assertions.assertThrows(
              RestClientResponseException.class,
              () -> relay.forwardRefineryExtract("user-1", null, sampleExtract()));
      assertThat(handler.handleBackendResponse(refused).getStatus()).isEqualTo(502);
      relay.forwardRefineryExtract("user-1", null, sampleExtract());

      assertThat(backend.takeRequest().getHeader("Authorization"))
          .isEqualTo("Bearer refused-token");
      assertThat(backend.takeRequest().getHeader("Authorization")).isEqualTo("Bearer fresh-token");
      assertThat(keycloak.getRequestCount()).isEqualTo(2);
    }
  }

  private static MockResponse tokenAnswer(String accessToken) {
    return new MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody("{\"access_token\":\"" + accessToken + "\",\"expires_in\":300}");
  }

  @Test
  void shouldCallTheBackendAsTheGatewayNamingTheCaller() throws Exception {
    backend.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json")
            .setBody("{\"goodsMatched\":1}"));

    MDC.put("correlationId", "cid-9");
    String body = client.forwardRefineryExtract("caller-sub", "de", sampleExtract());

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
    backend.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json")
            .setBody("{}"));

    client.forwardRefineryExtract("caller-sub", "   ", sampleExtract());

    RecordedRequest request = backend.takeRequest();
    assertThat(request.getHeader("Authorization")).isEqualTo("Bearer gateway-token");
    assertThat(request.getHeader(BackendImportClient.ON_BEHALF_OF_HEADER)).isEqualTo("caller-sub");
    assertThat(request.getHeader("Accept-Language")).isNull();
    assertThat(request.getHeader("X-Correlation-Id")).isNull();
  }

  @Test
  void shouldRelayTheCorrelationIdOnTheBlueprintPathToo() throws Exception {
    backend.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json")
            .setBody("{}"));

    MDC.put("correlationId", "cid-3");
    client.forwardBlueprintPreview("caller-sub", "en", "{}".getBytes(StandardCharsets.UTF_8));

    RecordedRequest request = backend.takeRequest();
    assertThat(request.getHeader("Accept-Language")).isEqualTo("en");
    assertThat(request.getHeader("X-Correlation-Id")).isEqualTo("cid-3");
  }

  @Test
  void shouldTakeTheCorrelationIdFromTheMdcRatherThanFromTheInboundHeader() throws Exception {
    backend.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json")
            .setBody("{}"));
    MDC.put("correlationId", "sanitised-id");

    client.forwardRefineryExtract("caller-sub", "de", sampleExtract());

    RecordedRequest request = backend.takeRequest();
    assertThat(request.getHeaders().values("X-Correlation-Id")).containsExactly("sanitised-id");
  }

  @Test
  void shouldDropAnAcceptLanguageThatIsNotAPlainLanguageRange() throws Exception {
    backend.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json")
            .setBody("{}"));

    client.forwardRefineryExtract("caller-sub", "de\r\nX-Injected: evil", sampleExtract());

    RecordedRequest request = backend.takeRequest();
    assertThat(request.getHeader("Accept-Language")).isNull();
    assertThat(request.getHeader("X-Injected")).isNull();
  }

  @Test
  void shouldDropAnOverlongAcceptLanguage() throws Exception {
    backend.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json")
            .setBody("{}"));

    client.forwardRefineryExtract("caller-sub", "de,".repeat(200), sampleExtract());

    RecordedRequest request = backend.takeRequest();
    assertThat(request.getHeader("Accept-Language")).isNull();
  }

  @Test
  void shouldKeepAWellFormedWeightedAcceptLanguage() throws Exception {
    backend.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json")
            .setBody("{}"));

    client.forwardRefineryExtract("caller-sub", "de-DE,de;q=0.9,en;q=0.8,*;q=0.5", sampleExtract());

    RecordedRequest request = backend.takeRequest();
    assertThat(request.getHeader("Accept-Language")).isEqualTo("de-DE,de;q=0.9,en;q=0.8,*;q=0.5");
  }

  @Test
  void shouldForwardBlueprintPreviewAsMultipart() throws Exception {
    backend.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json")
            .setBody("{\"total\":3}"));
    byte[] json = "{\"blueprints\":[]}".getBytes(StandardCharsets.UTF_8);

    String body = client.forwardBlueprintPreview("caller-sub", null, json);

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
