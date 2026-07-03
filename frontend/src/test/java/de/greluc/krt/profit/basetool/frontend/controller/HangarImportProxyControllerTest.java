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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for {@link HangarImportProxyController}. The controller is a multipart-pass-through to
 * two backend ship-import endpoints — the canonical {@code /api/v1/hangar/import/ships} and the
 * deprecated alias {@code /api/v1/hangar/import/fleetview}. The deprecated alias forwards to the
 * matching backend alias so existing automation does not break before the sunset date. Coverage:
 * happy paths for both endpoints, every exception branch on the deprecated path (the shared
 * forwarding plumbing means re-running each negative case for the new path would be redundant), and
 * the filename-fallback when the upload has no original filename.
 *
 * <p>{@link MockWebServer} stands in for the backend so the real WebClient fluent chain (URI /
 * content-type / multipart body / bodyToMono) is exercised.
 */
// Most tests here deliberately exercise the deprecated-for-removal importFleetview proxy alias;
// referencing it triggers expected [removal] warnings, hence the class-level suppression.
@SuppressWarnings("removal")
class HangarImportProxyControllerTest {

  private MockWebServer server;
  private HangarImportProxyController controller;

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    WebClient webClient = WebClient.builder().baseUrl(server.url("/").toString()).build();
    controller = new HangarImportProxyController(webClient);
  }

  @AfterEach
  void tearDown() throws Exception {
    try {
      server.shutdown();
    } catch (Exception ignored) {
      // already shut down in tests that simulate a connection failure
    }
  }

  @Test
  void importShips_happyPath_proxiesMultipartToCanonicalBackendPath() throws Exception {
    // Given a backend that accepts the upload and replies with a JSON summary
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"importedCount\":5}"));

    MultipartFile file =
        new MockMultipartFile(
            "file", "shiplist.json", "application/json", "[]".getBytes(StandardCharsets.UTF_8));

    // When
    ResponseEntity<Map<?, ?>> result = controller.importShips(file);

    // Then
    assertEquals(HttpStatus.OK, result.getStatusCode());
    assertEquals(5, result.getBody().get("importedCount"));

    // The new canonical proxy must hit the new backend path, NOT the deprecated alias.
    RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
    assertNotNull(req);
    assertEquals("POST", req.getMethod());
    assertEquals("/api/v1/hangar/import/ships", req.getPath());
    String contentType = req.getHeader("Content-Type");
    assertNotNull(contentType);
    assertTrue(
        contentType.startsWith(MediaType.MULTIPART_FORM_DATA_VALUE),
        "Content-Type must be multipart/form-data, was: " + contentType);
    String body = req.getBody().readUtf8();
    assertTrue(body.contains("filename=\"shiplist.json\""));
  }

  @Test
  void importFleetview_happyPath_proxiesMultipartToBackend() throws Exception {
    // Given a backend that accepts the upload and replies with a JSON summary
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"imported\":3,\"skipped\":1}"));

    MultipartFile file =
        new MockMultipartFile(
            "file",
            "fleetview.json",
            "application/json",
            "{\"ships\":[]}".getBytes(StandardCharsets.UTF_8));

    // When
    ResponseEntity<Map<?, ?>> result = controller.importFleetview(file);

    // Then
    assertEquals(HttpStatus.OK, result.getStatusCode());
    Map<?, ?> body = result.getBody();
    assertNotNull(body);
    assertEquals(3, body.get("imported"));
    assertEquals(1, body.get("skipped"));

    // The request must be a POST to /api/v1/hangar/import/fleetview with
    // multipart content-type carrying the file under name "file".
    RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
    assertNotNull(req);
    assertEquals("POST", req.getMethod());
    assertEquals("/api/v1/hangar/import/fleetview", req.getPath());
    String contentType = req.getHeader("Content-Type");
    assertNotNull(contentType);
    assertTrue(
        contentType.startsWith(MediaType.MULTIPART_FORM_DATA_VALUE),
        "Content-Type must be multipart/form-data, was: " + contentType);
    String body2 = req.getBody().readUtf8();
    assertTrue(body2.contains("name=\"file\""));
    assertTrue(body2.contains("filename=\"fleetview.json\""));
    assertTrue(body2.contains("{\"ships\":[]}"));
  }

  @Test
  void importFleetview_withoutOriginalFilename_fallsBackToShiplistJson() throws Exception {
    // Given a MultipartFile that returns null from getOriginalFilename().
    // MockMultipartFile normalises a null-constructor-arg into the empty
    // string ("") which the controller does NOT treat as "no filename",
    // so we override the getter directly to force the real null branch.
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"imported\":0}"));

    MultipartFile file =
        new MockMultipartFile(
            "file", "anything.json", "application/json", "{}".getBytes(StandardCharsets.UTF_8)) {
          @Override
          public String getOriginalFilename() {
            return null;
          }
        };

    // When
    controller.importFleetview(file);

    // Then — the fallback filename "shiplist.json" must be used so the
    // backend's Content-Disposition parsing doesn't see an empty filename.
    // (The fallback is format-neutral now that the proxy handles both
    // Fleetview and HangarXPLOR Shiplist uploads through the same plumbing.)
    RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
    assertNotNull(req);
    assertTrue(
        req.getBody().readUtf8().contains("filename=\"shiplist.json\""),
        "Filename fallback must default to 'shiplist.json' when the upload has no name");
  }

  @Test
  void importFleetview_onBackend400_propagatesAsBadRequest() {
    server.enqueue(new MockResponse().setResponseCode(400).setBody("Invalid JSON"));

    MultipartFile file =
        new MockMultipartFile(
            "file", "broken.json", "application/json", "garbage".getBytes(StandardCharsets.UTF_8));

    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> controller.importFleetview(file));

    assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
  }

  @Test
  void importFleetview_onBackend500_propagatesAs500() {
    server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));

    MultipartFile file =
        new MockMultipartFile(
            "file", "fleetview.json", "application/json", "{}".getBytes(StandardCharsets.UTF_8));

    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> controller.importFleetview(file));

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.getStatusCode());
  }

  @Test
  void importFleetview_onFileGetBytesIoException_wrapsAs500() {
    // Given a MultipartFile whose getBytes() throws — simulating a torn upload.
    MultipartFile broken =
        new MockMultipartFile("file", "x.json", "application/json", new byte[] {1, 2, 3}) {
          @Override
          public byte[] getBytes() throws IOException {
            throw new IOException("disk full");
          }
        };

    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> controller.importFleetview(broken));

    assertEquals(
        HttpStatus.INTERNAL_SERVER_ERROR,
        ex.getStatusCode(),
        "Generic IO failures must be wrapped as 500 — never leak through with raw stack trace");
  }

  @Test
  void importFleetview_onConnectionFailure_wrapsAs500() throws Exception {
    // Given — backend unreachable
    server.shutdown();

    MultipartFile file =
        new MockMultipartFile(
            "file", "fleetview.json", "application/json", "{}".getBytes(StandardCharsets.UTF_8));

    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> controller.importFleetview(file));

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.getStatusCode());
  }
}
