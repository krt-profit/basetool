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

package de.greluc.krt.profit.basetool.frontend;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPOutputStream;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Verifies the gzip content-negotiation wired into the non-streaming backend connector by {@link
 * de.greluc.krt.profit.basetool.frontend.config.WebClientConfig}.
 *
 * <p>{@code HttpClient.compress(true)} is applied only on the regular request/response connector
 * ({@code connector(false)}), which backs both {@code webClient} and {@code termsDocumentClient}.
 * Two properties must hold:
 *
 * <ul>
 *   <li>the non-streaming client advertises {@code Accept-Encoding: gzip} and transparently
 *       decompresses a {@code Content-Encoding: gzip} response body — if compression were not
 *       enabled the gzipped bytes would reach the String decoder verbatim and the assertion on the
 *       decoded payload would fail;
 *   <li>the streaming client ({@code sseWebClient}, backed by {@code connector(true)}) does NOT
 *       advertise gzip — per-event gzip on an SSE relay would only buffer the stream, so the
 *       compression flag is deliberately omitted there.
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(
    properties = {
      "app.http.connect-timeout=500ms",
      "app.http.response-timeout=2s",
      "app.http.read-timeout=2s",
      "app.http.write-timeout=2s"
    })
class WebClientCompressionTest {

  private static final String GZIP_PATH = "/api/v1/compress-probe";
  private static final String SSE_PATH = "/api/v1/stream-probe";
  private static final String PAYLOAD = "{\"value\":\"the-quick-brown-fox-compresses-cleanly\"}";

  private static final Map<String, String> acceptEncodingByPath = new ConcurrentHashMap<>();
  private static MockWebServer server;
  private static byte[] gzippedPayload;

  @Autowired private WebClient termsDocumentClient;

  @Autowired private WebClient sseWebClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  @MockitoBean private OAuth2AuthorizedClientRepository authorizedClientRepository;

  @BeforeAll
  static void startServer() throws IOException {
    gzippedPayload = gzip(PAYLOAD);
    server = new MockWebServer();
    server.start(0);

    Dispatcher dispatcher =
        new Dispatcher() {
          @Override
          public MockResponse dispatch(RecordedRequest request) {
            String path = request.getPath();
            acceptEncodingByPath.put(
                path == null ? "" : path, String.valueOf(request.getHeader("Accept-Encoding")));
            if (GZIP_PATH.equals(path)) {
              return new MockResponse()
                  .setResponseCode(200)
                  .addHeader("Content-Type", "application/json")
                  .addHeader("Content-Encoding", "gzip")
                  .setBody(new Buffer().write(gzippedPayload));
            }
            if (SSE_PATH.equals(path)) {
              return new MockResponse()
                  .setResponseCode(200)
                  .addHeader("Content-Type", "text/plain")
                  .setBody("plain-body");
            }
            return new MockResponse().setResponseCode(404);
          }
        };
    server.setDispatcher(dispatcher);
  }

  @DynamicPropertySource
  static void registerProps(DynamicPropertyRegistry registry) {
    registry.add("app.backend-url", () -> "http://localhost:" + server.getPort());
  }

  @AfterAll
  static void stopServer() throws IOException {
    if (server != null) {
      server.shutdown();
    }
  }

  @Test
  void nonStreamingClient_AdvertisesGzip_AndDecompressesTransparently() {
    String body =
        termsDocumentClient.get().uri(GZIP_PATH).retrieve().bodyToMono(String.class).block();

    assertThat(body)
        .as("gzipped response body must be transparently decompressed back to the original payload")
        .isEqualTo(PAYLOAD);
    assertThat(acceptEncodingByPath.get(GZIP_PATH))
        .as("non-streaming client must advertise gzip so the backend can compress the response")
        .contains("gzip");
  }

  @Test
  void streamingClient_DoesNotAdvertiseGzip() {
    sseWebClient.get().uri(SSE_PATH).retrieve().bodyToMono(String.class).block();

    String acceptEncoding = acceptEncodingByPath.get(SSE_PATH);
    assertThat(acceptEncoding == null || !acceptEncoding.contains("gzip"))
        .as(
            "SSE relay connector must not request gzip (per-event gzip would only buffer the"
                + " stream)")
        .isTrue();
  }

  /**
   * Gzip-compresses the given text into the wire bytes a compression-enabled backend would return.
   *
   * @param text the payload to compress
   * @return the gzip-encoded bytes
   * @throws IOException if the in-memory gzip stream fails (never, for a byte array)
   */
  private static byte[] gzip(String text) throws IOException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (GZIPOutputStream gos = new GZIPOutputStream(bos)) {
      gos.write(text.getBytes(StandardCharsets.UTF_8));
    }
    return bos.toByteArray();
  }
}
