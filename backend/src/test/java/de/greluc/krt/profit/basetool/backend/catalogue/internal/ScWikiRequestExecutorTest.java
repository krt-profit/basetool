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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.greluc.krt.profit.basetool.backend.support.BoundProperties;
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
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Exercises shared HTTP pacing, upstream cooldowns and cancellation independently of row decoding.
 */
class ScWikiRequestExecutorTest {

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
        RestClient.builder().baseUrl(properties.apiUrl()).build(), properties);
  }
}
