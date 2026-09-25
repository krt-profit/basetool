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

package de.greluc.krt.profit.basetool.frontend.controller;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for {@link HangarDeleteAllProxyController} against a {@link MockWebServer} backend: a
 * 204 is relayed, 4xx and 5xx are rethrown as {@link ResponseStatusException} with the same status,
 * and a network failure becomes a 500.
 */
class HangarDeleteAllProxyControllerTest {

  private MockWebServer server;
  private HangarDeleteAllProxyController controller;

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    WebClient webClient = WebClient.builder().baseUrl(server.url("/").toString()).build();
    controller = new HangarDeleteAllProxyController(webClient);
  }

  @AfterEach
  void tearDown() throws Exception {
    server.shutdown();
  }

  @Test
  void deleteAllShips_onBackend204_returns204() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(204));

    ResponseEntity<Void> result = controller.deleteAllShips();

    assertEquals(HttpStatus.NO_CONTENT, result.getStatusCode());
    RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
    assertNotNull(req);
    assertEquals("DELETE", req.getMethod());
    assertEquals("/api/v1/hangar/ships", req.getPath());
  }

  @Test
  void deleteAllShips_onBackend403_propagatesAsForbidden() {
    server.enqueue(new MockResponse().setResponseCode(403).setBody("Forbidden"));

    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> controller.deleteAllShips());

    assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
  }

  @Test
  void deleteAllShips_onBackend500_propagatesAsInternalServerError() {
    server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));

    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> controller.deleteAllShips());

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.getStatusCode());
  }

  @Test
  void deleteAllShips_onConnectionFailure_wrapsAs500() throws Exception {
    server.shutdown();

    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> controller.deleteAllShips());

    assertEquals(
        HttpStatus.INTERNAL_SERVER_ERROR,
        ex.getStatusCode(),
        "Non-HTTP failures must be re-thrown as a 500 (sanitised — no upstream stack trace)");
    assertTrue(ex.getReason() != null && ex.getReason().toLowerCase().contains("unexpected"));

    server = new MockWebServer();
    server.start();
  }
}
