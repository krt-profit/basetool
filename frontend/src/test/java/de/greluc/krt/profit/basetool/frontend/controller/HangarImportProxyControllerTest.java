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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
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
@SuppressWarnings("removal")
class HangarImportProxyControllerTest {

  private static final String TOO_LARGE_MESSAGE = "The file is larger than 8 MB.";

  private MockWebServer server;
  private HangarImportProxyController controller;

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    WebClient webClient = WebClient.builder().baseUrl(server.url("/").toString()).build();
    StaticMessageSource messages = new StaticMessageSource();
    messages.addMessage("hangar.import.error.tooLarge", Locale.getDefault(), TOO_LARGE_MESSAGE);
    controller = new HangarImportProxyController(webClient, messages);
  }

  @AfterEach
  void tearDown() throws Exception {
    try {
      server.shutdown();
    } catch (Exception ignored) {
    }
  }

  @Test
  void importShips_happyPath_proxiesMultipartToCanonicalBackendPath() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"importedCount\":5}"));

    MultipartFile file =
        new MockMultipartFile(
            "file", "shiplist.json", "application/json", "[]".getBytes(StandardCharsets.UTF_8));

    ResponseEntity<Map<?, ?>> result = controller.importShips(file);

    assertEquals(HttpStatus.OK, result.getStatusCode());
    assertEquals(5, result.getBody().get("importedCount"));

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

    ResponseEntity<Map<?, ?>> result = controller.importFleetview(file);

    assertEquals(HttpStatus.OK, result.getStatusCode());
    Map<?, ?> body = result.getBody();
    assertNotNull(body);
    assertEquals(3, body.get("imported"));
    assertEquals(1, body.get("skipped"));

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

    controller.importFleetview(file);

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
  void importFleetview_onFileReadIoException_wrapsAs500() {
    MultipartFile broken =
        new MockMultipartFile("file", "x.json", "application/json", new byte[] {1, 2, 3}) {
          @Override
          public InputStream getInputStream() throws IOException {
            throw new IOException("disk full");
          }

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
    server.shutdown();

    MultipartFile file =
        new MockMultipartFile(
            "file", "fleetview.json", "application/json", "{}".getBytes(StandardCharsets.UTF_8));

    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> controller.importFleetview(file));

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.getStatusCode());
  }

  @Test
  void importShips_oneByteOverTheCap_isRefusedWith413BeforeAnyBackendRequest() throws Exception {
    MultipartFile oversized =
        new MockMultipartFile(
            "file",
            "huge.json",
            "application/json",
            new byte[(int) HangarImportProxyController.MAX_IMPORT_BYTES + 1]) {
          @Override
          public byte[] getBytes() {
            throw new AssertionError("an oversized upload must not be read");
          }

          @Override
          public InputStream getInputStream() {
            throw new AssertionError("an oversized upload must not be read");
          }
        };

    ResponseEntity<Map<?, ?>> result = controller.importShips(oversized);

    assertEquals(HttpStatus.CONTENT_TOO_LARGE, result.getStatusCode());
    Map<?, ?> body = result.getBody();
    assertNotNull(body);
    assertEquals("UPLOAD_TOO_LARGE", body.get("code"));
    assertEquals(413, body.get("status"));
    assertEquals(TOO_LARGE_MESSAGE, body.get("detail"), "hangar.js shows the detail field");
    assertEquals(0, server.getRequestCount(), "no backend request may be made");
  }

  @Test
  void importShips_exactlyAtTheCap_isStreamedToTheBackendWhole() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"importedCount\":0}"));
    byte[] content = new byte[(int) HangarImportProxyController.MAX_IMPORT_BYTES];
    Arrays.fill(content, (byte) 'x');
    MultipartFile atCap = new MockMultipartFile("file", "big.json", "application/json", content);

    ResponseEntity<Map<?, ?>> result = controller.importShips(atCap);

    assertEquals(HttpStatus.OK, result.getStatusCode());
    RecordedRequest req = server.takeRequest(5, TimeUnit.SECONDS);
    assertNotNull(req);
    assertEquals("/api/v1/hangar/import/ships", req.getPath());
    assertTrue(
        req.getBodySize() >= HangarImportProxyController.MAX_IMPORT_BYTES,
        "the whole file must be forwarded, body was " + req.getBodySize() + " bytes");
  }
}
