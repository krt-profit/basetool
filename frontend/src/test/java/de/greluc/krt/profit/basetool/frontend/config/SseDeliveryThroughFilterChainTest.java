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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Bytes written into an {@link SseEmitter} must reach a socket through the frontend's real servlet
 * filter chain (FE-PERF-03) — the frontend twin of the backend's test of the same name (#1653).
 *
 * <p>The frontend relays the notification stream to the browser ({@code GET
 * /notifications/stream}). Until 2026-09-22 a {@link
 * org.springframework.web.filter.ShallowEtagHeaderFilter} sat on {@code /*} in front of it, which
 * buffers a response until it completes; the relay only escaped because Spring MVC's emitter
 * handler opts each streaming request out of that buffer. {@link EtagConfig} now limits the filter
 * to the cacheable routes, and this test pins the property that matters regardless of which
 * component would break it: a streaming response's first frame arrives at a client while the stream
 * is still open.
 *
 * <p>The stream under test is a test-only endpoint under {@code /sm/**}, a {@code permitAll} path,
 * because the real relay needs an OAuth2 session and an upstream backend stream that a test cannot
 * open over a real port. Every servlet filter registered for all paths runs in front of it exactly
 * as it runs in front of the relay; the relay's own first write is the same request-thread {@code
 * ready} comment (ADR-0113).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SseDeliveryThroughFilterChainTest {

  /** The test stream's path; {@code /sm/**} is {@code permitAll} in {@link SecurityConfig}. */
  private static final String STREAM_PATH = "/sm/test-sse-stream";

  /** How long a first frame may take before the stream counts as swallowed. */
  private static final Duration FIRST_FRAME_BUDGET = Duration.ofSeconds(15);

  /** The application connector the stream is opened against. */
  @Value("${local.server.port}")
  private int port;

  /** Replaced so the context starts without a reachable Keycloak. */
  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  /** The test stream endpoint, whose open emitters are completed after each test. */
  @Autowired private TestStreamController streamController;

  private final HttpClient http =
      HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

  /** Contributes the test-only streaming endpoint to the application context. */
  @TestConfiguration
  static class StreamEndpointConfig {

    /**
     * Registers the test stream controller.
     *
     * @return the controller serving {@link #STREAM_PATH}
     */
    @Bean
    TestStreamController testStreamController() {
      return new TestStreamController();
    }
  }

  /** Opens an SSE stream that sends one comment frame and then stays open, like the relay. */
  @RestController
  static class TestStreamController {

    /** The emitters opened and not yet completed; drained by {@link #completeAll()}. */
    private final List<SseEmitter> open = new CopyOnWriteArrayList<>();

    /**
     * Completes every stream this controller opened, so none of them runs into its async timeout
     * (and an error log line) after the test that opened it has finished.
     */
    void completeAll() {
      open.forEach(SseEmitter::complete);
      open.clear();
    }

    /**
     * Commits the stream on the request thread with a {@code ready} comment and never completes it,
     * so a buffering component would hold the frame for the stream's whole lifetime.
     *
     * @return the open emitter
     * @throws IOException if the first frame cannot be written
     */
    @GetMapping(value = STREAM_PATH, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @NotNull
    SseEmitter stream() throws IOException {
      SseEmitter emitter = new SseEmitter(Duration.ofMinutes(1).toMillis());
      open.add(emitter);
      emitter.send(SseEmitter.event().comment("ready"));
      return emitter;
    }
  }

  /** Closes the streams the test opened. */
  @AfterEach
  void closeStreams() {
    streamController.completeAll();
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  @DisplayName("a streaming response reaches a real socket while the stream is still open")
  void streamingResponseReachesTheSocketUnbuffered() throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + STREAM_PATH))
            .header("Accept", MediaType.TEXT_EVENT_STREAM_VALUE)
            .GET()
            .build();

    HttpResponse<Stream<String>> response = http.send(request, HttpResponse.BodyHandlers.ofLines());
    assertThat(response.statusCode()).as("status of %s", STREAM_PATH).isEqualTo(200);
    assertThat(response.headers().firstValue("ETag"))
        .as("a stream is never a candidate for an ETag")
        .isEmpty();

    CompletableFuture<List<String>> firstLine =
        CompletableFuture.supplyAsync(
            () -> response.body().filter(line -> !line.isBlank()).limit(1).toList());
    try {
      List<String> lines = firstLine.get(FIRST_FRAME_BUDGET.toMillis(), TimeUnit.MILLISECONDS);
      assertThat(lines).as("first frame of %s", STREAM_PATH).containsExactly(":ready");
    } finally {
      firstLine.cancel(true);
    }
  }
}
