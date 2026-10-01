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

package de.greluc.krt.profit.basetool.frontend.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.greluc.krt.profit.basetool.frontend.exception.ReauthenticationRequiredException;
import de.greluc.krt.profit.basetool.frontend.metrics.MetricNames;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Proves that {@link BackendApiClient#execute} gives the call shapes the typed verbs do not cover —
 * multipart upload, binary download, bodiless delete, collected flux — the error path of every
 * other backend call: the mapped {@link BackendServiceException} status and RFC 7807 {@code code},
 * the {@code basetool_backend_client_errors_total} increment, and the re-authentication signal.
 */
class BackendApiClientExecuteTest {

  private MockWebServer server;
  private SimpleMeterRegistry meterRegistry;
  private BackendApiClient client;

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    WebClient webClient = WebClient.builder().baseUrl(server.url("/").toString()).build();
    meterRegistry = new SimpleMeterRegistry();
    client = new BackendApiClient(webClient, webClient, meterRegistry, new NoOpCacheManager());
  }

  @AfterEach
  void tearDown() throws Exception {
    server.shutdown();
  }

  /** One row per call shape: its name, HTTP verb, and the call run through {@code execute}. */
  static Stream<Arguments> shapes() {
    return Stream.of(
        shape("multipart POST", HttpMethod.POST, BackendApiClientExecuteTest::multipart),
        shape("binary GET", HttpMethod.GET, BackendApiClientExecuteTest::binaryGet),
        shape("bodiless DELETE", HttpMethod.DELETE, BackendApiClientExecuteTest::bodilessDelete),
        shape("collected flux", HttpMethod.GET, BackendApiClientExecuteTest::collectedFlux));
  }

  private static Arguments shape(
      String name, HttpMethod method, Function<BackendApiClient, Object> call) {
    return Arguments.of(name, method, call);
  }

  private static Object multipart(BackendApiClient client) {
    MultipartBodyBuilder builder = new MultipartBodyBuilder();
    builder.part("file", "{}").contentType(MediaType.APPLICATION_OCTET_STREAM);
    return client.execute(
        HttpMethod.POST,
        "/api/v1/import",
        webClient ->
            webClient
                .post()
                .uri("/api/v1/import")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build())),
        spec -> spec.bodyToMono(Map.class));
  }

  private static Object binaryGet(BackendApiClient client) {
    return client.execute(
        HttpMethod.GET,
        "/api/v1/report",
        webClient -> webClient.get().uri("/api/v1/report"),
        spec -> spec.bodyToMono(byte[].class));
  }

  private static Object bodilessDelete(BackendApiClient client) {
    return client.execute(
        HttpMethod.DELETE,
        "/api/v1/all",
        webClient -> webClient.delete().uri("/api/v1/all"),
        WebClient.ResponseSpec::toBodilessEntity);
  }

  private static Object collectedFlux(BackendApiClient client) {
    return client.execute(
        HttpMethod.GET,
        "/api/v1/jobs",
        webClient -> webClient.get().uri("/api/v1/jobs"),
        spec -> spec.bodyToFlux(Map.class).collectList());
  }

  /** A refused call surfaces its status and problem code and is counted as a 4xx. */
  @ParameterizedTest(name = "{0}: {3} {4}")
  @MethodSource("refusals")
  void aBackendRefusalMapsLikeAnyOtherBackendCall(
      String name,
      HttpMethod method,
      Function<BackendApiClient, Object> call,
      int status,
      String code) {
    server.enqueue(problem(status, code));

    BackendServiceException ex =
        assertThrows(BackendServiceException.class, () -> call.apply(client));

    assertEquals(status, ex.getStatusCode());
    assertEquals(code, ex.getProblemCode());
    assertEquals(
        1.0d,
        meterRegistry
            .counter(
                MetricNames.BACKEND_CLIENT_ERRORS,
                MetricNames.TAG_REASON,
                status >= 500 ? MetricNames.REASON_BACKEND_5XX : MetricNames.REASON_BACKEND_4XX,
                MetricNames.TAG_METHOD,
                method.name())
            .count());
  }

  static Stream<Arguments> refusals() {
    return shapes()
        .flatMap(
            shape ->
                Stream.of(
                        new Object[] {401, "UNAUTHORIZED"},
                        new Object[] {403, "FORBIDDEN"},
                        new Object[] {404, "NOT_FOUND"},
                        new Object[] {409, "CONFLICT"},
                        new Object[] {503, "SERVICE_UNAVAILABLE"})
                    .map(
                        refusal ->
                            Arguments.of(
                                shape.get()[0],
                                shape.get()[1],
                                shape.get()[2],
                                refusal[0],
                                refusal[1])));
  }

  /** A refresh token the OAuth2 client can no longer use becomes the re-authentication signal. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("shapes")
  void anUnusableTokenBecomesTheReauthenticationSignal(
      String name, HttpMethod method, Function<BackendApiClient, Object> call) {
    WebClient failing =
        WebClient.builder()
            .baseUrl(server.url("/").toString())
            .filter(
                (request, next) ->
                    Mono.error(
                        new ClientAuthorizationException(
                            new OAuth2Error("invalid_grant"), "keycloak")))
            .build();
    BackendApiClient reauthClient =
        new BackendApiClient(failing, failing, meterRegistry, new NoOpCacheManager());

    assertThrows(ReauthenticationRequiredException.class, () -> call.apply(reauthClient));
    assertEquals(0, server.getRequestCount(), "the call must not reach the backend");
  }

  /** A refused connection is the same timeout as for every other call, counted as such. */
  @Test
  void anUnreachableBackendIsATimeoutAndCounted() throws Exception {
    server.shutdown();

    BackendServiceException ex =
        assertThrows(BackendServiceException.class, () -> binaryGet(client));

    assertEquals(504, ex.getStatusCode());
    assertEquals(BackendServiceException.CODE_BACKEND_TIMEOUT, ex.getProblemCode());
    assertEquals(
        1.0d,
        meterRegistry
            .counter(
                MetricNames.BACKEND_CLIENT_ERRORS,
                MetricNames.TAG_REASON,
                MetricNames.REASON_TIMEOUT,
                MetricNames.TAG_METHOD,
                "GET")
            .count());
  }

  /** A successful call still decodes the body, bytes and collected flux included. */
  @Test
  void aSuccessfulCallDecodesTheBody() {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/pdf")
            .setBody("%PDF"));
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("[{\"id\":1},{\"id\":2}]"));

    assertArrayEquals("%PDF".getBytes(StandardCharsets.UTF_8), (byte[]) binaryGet(client));
    Object listed = collectedFlux(client);
    assertNotNull(listed);
    assertEquals(2, ((List<?>) listed).size());
  }

  private static MockResponse problem(int status, String code) {
    return new MockResponse()
        .setResponseCode(status)
        .setHeader("Content-Type", "application/problem+json")
        .setBody("{\"status\":" + status + ",\"code\":\"" + code + "\"}");
  }
}
