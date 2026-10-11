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

package de.greluc.krt.profit.basetool.frontend.kernel.backend;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Drives a typed backend client against a {@link MockWebServer} through a real {@link
 * BackendApiClient} whose {@code WebClient} carries the production codecs, so a test pins the exact
 * verb, path, query and body a client method sends (plan F3).
 */
public final class BackendClientHarness implements AutoCloseable {

  /** The production codec limit of {@code WebClientConfig}. */
  private static final int MAX_IN_MEMORY_BYTES = 64 * 1024 * 1024;

  /** The stub backend. */
  private final MockWebServer server;

  /** The real kernel client over the stub backend. */
  private final BackendApiClient backendApiClient;

  private BackendClientHarness(MockWebServer server, BackendApiClient backendApiClient) {
    this.server = server;
    this.backendApiClient = backendApiClient;
  }

  /**
   * Starts a stub backend and builds a {@link BackendApiClient} that sends to it.
   *
   * @return the running harness
   */
  @NotNull
  public static BackendClientHarness start() {
    MockWebServer server = new MockWebServer();
    try {
      server.start();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    ExchangeStrategies strategies =
        ExchangeStrategies.builder()
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_BYTES))
            .build();
    WebClient webClient =
        WebClient.builder()
            .exchangeStrategies(strategies)
            .baseUrl(server.url("/").toString())
            .build();
    BackendApiClient client =
        new BackendApiClient(
            webClient, webClient, new SimpleMeterRegistry(), new NoOpCacheManager());
    return new BackendClientHarness(server, client);
  }

  /**
   * Returns the kernel client the typed client under test is built on.
   *
   * @return the client sending to the stub backend
   */
  @NotNull
  public BackendApiClient backendApiClient() {
    return backendApiClient;
  }

  /**
   * Queues a {@code 200} JSON answer.
   *
   * @param json the response body
   */
  public void answerJson(@NotNull String json) {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(json));
  }

  /** Queues a bodiless {@code 204} answer. */
  public void answerEmpty() {
    server.enqueue(new MockResponse().setResponseCode(204));
  }

  /**
   * Queues a {@code 200} answer with a binary body.
   *
   * @param contentType the response content type
   * @param body the response bytes
   */
  public void answerBytes(@NotNull String contentType, byte @NotNull [] body) {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", contentType)
            .setBody(new okio.Buffer().write(body)));
  }

  /**
   * Takes the next request the client sent and checks its verb and path with query.
   *
   * @param method the expected HTTP method
   * @param pathAndQuery the expected raw path and query, exactly as sent
   * @return the request, for further checks
   */
  @NotNull
  public RecordedRequest expect(@NotNull String method, @NotNull String pathAndQuery) {
    RecordedRequest request = take();
    assertThat(request.getMethod()).as("method").isEqualTo(method);
    assertThat(request.getPath()).as("path and query").isEqualTo(pathAndQuery);
    return request;
  }

  /**
   * Takes the next request and checks its verb, path with query and body.
   *
   * @param method the expected HTTP method
   * @param pathAndQuery the expected raw path and query, exactly as sent
   * @param body the expected body, exactly as sent, or {@code null} for an empty body
   * @return the request, for further checks
   */
  @NotNull
  public RecordedRequest expect(
      @NotNull String method, @NotNull String pathAndQuery, @Nullable String body) {
    RecordedRequest request = expect(method, pathAndQuery);
    assertThat(request.getBody().readString(StandardCharsets.UTF_8))
        .as("body")
        .isEqualTo(body == null ? "" : body);
    return request;
  }

  /**
   * Takes the next request the client sent.
   *
   * @return the request
   */
  @NotNull
  private RecordedRequest take() {
    try {
      RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
      assertThat(request).as("a request reached the stub backend").isNotNull();
      return request;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** Stops the stub backend. */
  @Override
  public void close() {
    try {
      server.shutdown();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
