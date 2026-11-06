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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;

import de.greluc.krt.profit.basetool.backend.support.BoundProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Exercises shared HTTP pacing, upstream cooldowns and cancellation independently of row decoding.
 */
class ScWikiRequestExecutorTest {

  @Test
  void missingResourceIsExplicitAndNeverRetried() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      server.enqueue(new MockResponse().setResponseCode(404).setBody("missing source resource"));
      ScWikiRequestExecutor requests = executor(server, Map.of("max-attempts", 3));

      ScWikiRequestExecutor.Response response = requests.getResult("/api/items/{id}", "missing");

      assertTrue(response.missing());
      assertNull(response.body());
      assertEquals(1, server.getRequestCount());
    }
  }

  @Test
  void successfulRepresentationUsesTheDeclaredCharset() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      String text = "Rückmeldung";
      server.enqueue(
          new MockResponse()
              .setHeader("Content-Type", "text/plain;charset=ISO-8859-1")
              .setBody(new Buffer().write(text.getBytes(StandardCharsets.ISO_8859_1))));
      ScWikiRequestExecutor requests = executor(server, Map.of());

      ScWikiRequestExecutor.Response response = requests.getResult("/api/items");

      assertFalse(response.missing());
      assertEquals(text, response.body());
      assertEquals(1, server.getRequestCount());
    }
  }

  @Test
  void redirectIsRejectedWithoutFollowingOrLosingResponseDiagnostics() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      String text = "Rückmeldung";
      byte[] body = text.getBytes(StandardCharsets.ISO_8859_1);
      server.enqueue(
          new MockResponse()
              .setResponseCode(307)
              .setHeader("Location", "/redirected")
              .setHeader("Content-Type", "text/plain;charset=ISO-8859-1")
              .setHeader("ETag", "\"redirect-version\"")
              .setBody(new Buffer().write(body)));
      ScWikiRequestExecutor requests = executor(server, Map.of("max-attempts", 3));

      RestClientResponseException failure =
          assertThrows(RestClientResponseException.class, () -> requests.getResult("/api/items"));

      assertEquals(307, failure.getStatusCode().value());
      assertArrayEquals(body, failure.getResponseBodyAsByteArray());
      assertEquals(text, failure.getResponseBodyAsString());
      assertNotNull(failure.getResponseHeaders());
      assertEquals("/redirected", failure.getResponseHeaders().getFirst("Location"));
      assertEquals("\"redirect-version\"", failure.getResponseHeaders().getETag());
      assertEquals(
          StandardCharsets.ISO_8859_1, failure.getResponseHeaders().getContentType().getCharset());
      assertEquals(1, server.getRequestCount());
    }
  }

  @Test
  void responseHandlerRetainsCustomReasonAndUndecodableOriginalBytes() {
    URI uri = URI.create("https://wiki.example/api/items");
    byte[] body = {(byte) 0xc3, 0x28};
    MockClientHttpResponse response =
        spy(new MockClientHttpResponse(body, HttpStatusCode.valueOf(302)));
    doReturn("Source representation moved").when(response).getStatusText();
    response.getHeaders().set("Content-Type", "text/plain;charset=UTF-8");
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(method(HttpMethod.GET))
        .andRespond(request -> {
          assertEquals(uri, request.getURI());
          return response;
        });
    ScWikiProperties properties =
        BoundProperties.bind(ScWikiProperties.class, Map.of("api-url", "https://wiki.example"));
    ScWikiRequestExecutor requests = new ScWikiRequestExecutor(builder.build(), properties);

    RestClientResponseException failure =
        assertThrows(RestClientResponseException.class, () -> requests.getResult(uri.toString()));

    assertEquals(302, failure.getStatusCode().value());
    assertEquals("Source representation moved", failure.getStatusText());
    assertArrayEquals(body, failure.getResponseBodyAsByteArray());
    assertEquals("\ufffd(", failure.getResponseBodyAsString());
    server.verify();
  }

  @Test
  void bodylessNotModifiedIsRejectedInsteadOfBecomingAnAbsentOrSuccessfulResource()
      throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      server.enqueue(new MockResponse().setResponseCode(304).setHeader("ETag", "\"v1\""));
      ScWikiRequestExecutor requests = executor(server, Map.of("max-attempts", 3));

      RestClientResponseException failure =
          assertThrows(RestClientResponseException.class, () -> requests.getResult("/api/items"));

      assertEquals(304, failure.getStatusCode().value());
      assertEquals("", failure.getResponseBodyAsString());
      assertNotNull(failure.getResponseHeaders());
      assertEquals("\"v1\"", failure.getResponseHeaders().getETag());
      assertEquals(1, server.getRequestCount());
    }
  }

  @Test
  void concurrentCallersShareOneRequestStartBudget() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      ConcurrentLinkedQueue<Long> arrivals = new ConcurrentLinkedQueue<>();
      server.setDispatcher(
          new Dispatcher() {
            @Override
            @NotNull
            public MockResponse dispatch(@NotNull RecordedRequest request) {
              arrivals.add(System.nanoTime());
              return new MockResponse().setBody("{}");
            }
          });
      server.start();
      ScWikiRequestExecutor requests = executor(server, Map.of("requests-per-second", 10));
      CountDownLatch start = new CountDownLatch(1);
      long started = System.nanoTime();
      try (var pool = Executors.newFixedThreadPool(4)) {
        List<java.util.concurrent.Future<?>> pending = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
          pending.add(
              pool.submit(() -> {
                start.await();
                return requests.get("/api/items");
              }));
        }
        start.countDown();
        for (var future : pending) {
          future.get(5, TimeUnit.SECONDS);
        }
      }

      List<Long> received = List.copyOf(arrivals);
      assertEquals(4, received.size());
      assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= 280);
    }
  }

  @Test
  void anHttpDateRetryAfterProtectsSubsequentCallersWithoutBlockingBeyondTheLimit()
      throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      String retryAfter =
          ZonedDateTime.now(ZoneOffset.UTC)
              .plusMinutes(1)
              .format(DateTimeFormatter.RFC_1123_DATE_TIME);
      server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", retryAfter));
      ScWikiRequestExecutor requests =
          executor(server, Map.of("max-retry-delay-millis", 10, "retry-delay-millis", 1));

      assertThrows(RestClientResponseException.class, () -> requests.get("/api/items"));
      assertThrows(IllegalStateException.class, () -> requests.get("/api/vehicles"));
      assertEquals(1, server.getRequestCount());
    }
  }

  @Test
  void interruptedCallersNeverDispatchAnotherRequest() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      ScWikiRequestExecutor requests = executor(server, Map.of());
      try {
        Thread.currentThread().interrupt();
        assertThrows(IllegalStateException.class, () -> requests.get("/api/items"));
        assertTrue(Thread.currentThread().isInterrupted());
        assertEquals(0, server.getRequestCount());
      } finally {
        Thread.interrupted();
      }
    }
  }

  private static ScWikiRequestExecutor executor(
      MockWebServer server, Map<String, Object> overrides) {
    Map<String, Object> config = new java.util.HashMap<>(overrides);
    config.put("api-url", server.url("/").toString());
    ScWikiProperties properties = BoundProperties.bind(ScWikiProperties.class, config);
    return new ScWikiRequestExecutor(
        RestClient.builder()
            .requestFactory(
                new JdkClientHttpRequestFactory(
                    HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()))
            .baseUrl(properties.apiUrl())
            .build(),
        properties);
  }
}
