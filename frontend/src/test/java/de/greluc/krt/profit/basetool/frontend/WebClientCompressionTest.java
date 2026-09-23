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

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
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
 * Pins that neither backend connector of {@link
 * de.greluc.krt.profit.basetool.frontend.config.WebClientConfig} asks the backend for gzip
 * (BE-PERF-14, ADR-0161 §8.5 amendment 2026-09-23).
 *
 * <p>The frontend→backend hop is a container bridge on one host. Measured on the real embedded
 * Tomcat, gzip on a 210 KB page cost about 1.6 ms of CPU per response across both ends and made the
 * request 1.6–3.2 ms slower, while the byte saving it bought is worth nothing on that hop. So the
 * regular connector ({@code webClient}, {@code termsDocumentClient}) sends no {@code
 * Accept-Encoding}, exactly like the SSE relay connector ({@code sseWebClient}) always did — where
 * per-event gzip would only buffer the stream. A backend that is not asked never compresses.
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

  private static final String PLAIN_PATH = "/api/v1/compress-probe";
  private static final String SSE_PATH = "/api/v1/stream-probe";
  private static final String PAYLOAD = "{\"value\":\"the-quick-brown-fox-compresses-cleanly\"}";

  private static final Map<String, String> acceptEncodingByPath = new ConcurrentHashMap<>();
  private static MockWebServer server;

  @Autowired private WebClient termsDocumentClient;

  @Autowired private WebClient sseWebClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  @MockitoBean private OAuth2AuthorizedClientRepository authorizedClientRepository;

  @BeforeAll
  static void startServer() throws IOException {
    server = new MockWebServer();
    server.start(0);

    Dispatcher dispatcher =
        new Dispatcher() {
          @Override
          public MockResponse dispatch(RecordedRequest request) {
            String path = request.getPath();
            acceptEncodingByPath.put(
                path == null ? "" : path, String.valueOf(request.getHeader("Accept-Encoding")));
            if (PLAIN_PATH.equals(path)) {
              return new MockResponse()
                  .setResponseCode(200)
                  .addHeader("Content-Type", "application/json")
                  .setBody(PAYLOAD);
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
  void nonStreamingClient_DoesNotAdvertiseGzip() {
    String body =
        termsDocumentClient.get().uri(PLAIN_PATH).retrieve().bodyToMono(String.class).block();

    assertThat(body).isEqualTo(PAYLOAD);
    String acceptEncoding = acceptEncodingByPath.get(PLAIN_PATH);
    assertThat(acceptEncoding == null || !acceptEncoding.contains("gzip"))
        .as(
            "the regular connector must not ask for gzip: on the internal hop it costs CPU at both"
                + " ends and buys nothing (BE-PERF-14), and was sent as '%s'",
            acceptEncoding)
        .isTrue();
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
}
