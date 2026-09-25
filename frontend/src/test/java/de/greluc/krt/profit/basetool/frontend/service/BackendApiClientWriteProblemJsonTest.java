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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.greluc.krt.profit.basetool.frontend.metrics.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * RFC 7807 mapping and REQ-OBS-011 metric coverage for {@link BackendApiClient}'s write verbs
 * (POST, PUT, PATCH, DELETE): each test asserts the mapped {@link BackendServiceException} status
 * and stable code, and the exact {@code backend_4xx} / {@code backend_5xx} counter incremented.
 * Uses a bare {@link WebClient} without retries against a per-test {@link MockWebServer}.
 */
class BackendApiClientWriteProblemJsonTest {

  private MockWebServer server;
  private SimpleMeterRegistry meterRegistry;
  private BackendApiClient client;

  /**
   * Spins up a fresh {@link MockWebServer} plus a {@link BackendApiClient} wired to a bare {@link
   * WebClient} (no resilience filter) and an empty {@link SimpleMeterRegistry} so each test
   * observes exactly the errors it provokes.
   *
   * @throws Exception if the mock server fails to start
   */
  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    WebClient webClient = WebClient.builder().baseUrl(server.url("/").toString()).build();
    WebClient termsDocumentClient = WebClient.builder().baseUrl(server.url("/").toString()).build();
    meterRegistry = new SimpleMeterRegistry();
    client =
        new BackendApiClient(webClient, termsDocumentClient, meterRegistry, new NoOpCacheManager());
  }

  /**
   * Shuts the mock server down after each test.
   *
   * @throws Exception if the mock server fails to shut down
   */
  @AfterEach
  void tearDown() throws Exception {
    server.shutdown();
  }

  @Test
  void post_ShouldMapOptimisticLock409_andCountBackend4xx() throws Exception {
    server.enqueue(
        problemJson(
            409,
            "{\"type\":\"urn:problem:optimistic-lock\",\"title\":\"Conflict\",\"status\":409,"
                + "\"detail\":\"Entity was updated concurrently\","
                + "\"code\":\"OPTIMISTIC_LOCK\",\"correlationId\":\"corr-409\"}"));

    BackendServiceException ex =
        assertThrows(
            BackendServiceException.class,
            () -> client.post("/api/v1/missions/1/core", "{\"version\":3}", String.class));

    assertEquals(409, ex.getStatusCode());
    assertEquals("OPTIMISTIC_LOCK", ex.getProblemCode());
    assertEquals("corr-409", ex.getCorrelationId());
    assertEquals("Entity was updated concurrently", ex.getReadableErrorMessage());

    RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
    assertEquals("POST", req.getMethod());

    assertEquals(1.0d, count(MetricNames.REASON_BACKEND_4XX, "POST"));
    assertNull(
        findCounter(MetricNames.REASON_BACKEND_5XX, "POST"),
        "a 4xx write must not increment the backend_5xx counter");
  }

  @Test
  void post_Should503Problem_countBackend5xx() {
    server.enqueue(
        problemJson(
            503,
            "{\"type\":\"urn:problem:service-unavailable\",\"title\":\"Service"
                + " Unavailable\",\"status\":503,\"detail\":\"Downstream degraded\","
                + "\"code\":\"SERVICE_UNAVAILABLE\",\"correlationId\":\"corr-503\"}"));

    BackendServiceException ex =
        assertThrows(
            BackendServiceException.class,
            () -> client.post("/api/v1/missions/1/core", "{\"version\":3}", String.class));

    assertEquals(503, ex.getStatusCode());
    assertEquals(BackendServiceException.CODE_SERVICE_UNAVAILABLE, ex.getProblemCode());

    assertEquals(1.0d, count(MetricNames.REASON_BACKEND_5XX, "POST"));
    assertNull(
        findCounter(MetricNames.REASON_BACKEND_4XX, "POST"),
        "a 5xx write must not increment the backend_4xx counter");
  }

  @Test
  void put_ShouldMapConflict409_andCountBackend4xx() {
    server.enqueue(
        problemJson(
            409,
            "{\"type\":\"urn:problem:bank-conflict\",\"title\":\"Conflict\",\"status\":409,"
                + "\"detail\":\"Booking already settled\","
                + "\"code\":\"BANK_BOOKING_CONFLICT\",\"correlationId\":\"corr-put\"}"));

    BackendServiceException ex =
        assertThrows(
            BackendServiceException.class,
            () -> client.put("/api/v1/bank/bookings/1", "{\"version\":7}", String.class));

    assertEquals(409, ex.getStatusCode());
    assertEquals("BANK_BOOKING_CONFLICT", ex.getProblemCode());
    assertEquals(1.0d, count(MetricNames.REASON_BACKEND_4XX, "PUT"));
  }

  @Test
  void patch_ShouldMapValidation400_andCountBackend4xx() {
    server.enqueue(
        problemJson(
            400,
            "{\"type\":\"urn:problem:validation\",\"title\":\"Bad Request\",\"status\":400,"
                + "\"detail\":\"Validation failed\",\"code\":\"VALIDATION_FAILED\","
                + "\"correlationId\":\"corr-patch\","
                + "\"fieldErrors\":[{\"field\":\"amount\",\"message\":\"must be positive\"}]}"));

    BackendServiceException ex =
        assertThrows(
            BackendServiceException.class,
            () -> client.patch("/api/v1/things/1", "{\"amount\":-1}", String.class));

    assertEquals(400, ex.getStatusCode());
    assertEquals("VALIDATION_FAILED", ex.getProblemCode());
    assertEquals(1, ex.getFieldErrors().size());
    assertEquals(1.0d, count(MetricNames.REASON_BACKEND_4XX, "PATCH"));
  }

  @Test
  void delete_ShouldMapLocked423_andCountBackend4xx() {
    server.enqueue(
        problemJson(
            423,
            "{\"type\":\"urn:problem:locked\",\"title\":\"Locked\",\"status\":423,"
                + "\"detail\":\"Resource frozen\",\"code\":\"LOCKED\","
                + "\"correlationId\":\"corr-del\"}"));

    BackendServiceException ex =
        assertThrows(
            BackendServiceException.class, () -> client.delete("/api/v1/things/1", String.class));

    assertEquals(423, ex.getStatusCode());
    assertEquals("LOCKED", ex.getProblemCode());
    assertEquals(1.0d, count(MetricNames.REASON_BACKEND_4XX, "DELETE"));
  }

  @Test
  void get_Should503Problem_countBackend5xx() {
    server.enqueue(
        problemJson(
            503,
            "{\"type\":\"urn:problem:service-unavailable\",\"title\":\"Service"
                + " Unavailable\",\"status\":503,\"detail\":\"Downstream degraded\","
                + "\"code\":\"SERVICE_UNAVAILABLE\",\"correlationId\":\"corr-get-503\"}"));

    BackendServiceException ex =
        assertThrows(
            BackendServiceException.class, () -> client.get("/api/v1/status", String.class));

    assertEquals(503, ex.getStatusCode());
    assertEquals(1.0d, count(MetricNames.REASON_BACKEND_5XX, "GET"));
    assertNull(
        findCounter(MetricNames.REASON_BACKEND_4XX, "GET"),
        "a 5xx GET must not increment the backend_4xx counter");
  }

  private static MockResponse problemJson(int status, String body) {
    return new MockResponse()
        .setResponseCode(status)
        .setHeader("Content-Type", "application/problem+json")
        .setBody(body);
  }

  /**
   * Reads the current value of {@code basetool_backend_client_errors_total} for the given bounded
   * {@code reason} + HTTP {@code method} tag pair, failing the test if the meter is absent.
   *
   * @param reason the bounded failure reason ({@code MetricNames.REASON_*})
   * @param method the HTTP verb tag value
   * @return the counter's current count
   */
  private double count(String reason, String method) {
    return meterRegistry
        .get(MetricNames.BACKEND_CLIENT_ERRORS)
        .tags(
            MetricNames.TAG_REASON, reason,
            MetricNames.TAG_METHOD, method)
        .counter()
        .count();
  }

  /**
   * Looks up the {@code basetool_backend_client_errors_total} counter for the given tag pair,
   * returning {@code null} when no such meter has been registered (used to assert the 4xx/5xx split
   * never touches the opposite reason).
   *
   * @param reason the bounded failure reason ({@code MetricNames.REASON_*})
   * @param method the HTTP verb tag value
   * @return the matching counter, or {@code null} if none exists
   */
  private Counter findCounter(String reason, String method) {
    return meterRegistry
        .find(MetricNames.BACKEND_CLIENT_ERRORS)
        .tags(
            MetricNames.TAG_REASON, reason,
            MetricNames.TAG_METHOD, method)
        .counter();
  }
}
