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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.greluc.krt.profit.basetool.frontend.config.AppHttpProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for {@link DataExportProxyController} (REQ-SEC-058, ADR-0185) against a {@link
 * MockWebServer} backend.
 *
 * <p>Asserts that the member's own endpoints relay no user id, that no download filename carries a
 * handle (the admin variant carries the subject id), and that backend statuses are relayed.
 */
class DataExportProxyControllerTest {

  private static final UUID SUBJECT = UUID.fromString("11111111-2222-3333-4444-555555555555");
  private static final byte[] DOCUMENT = "{\"subject\":{}}".getBytes(StandardCharsets.UTF_8);

  private MockWebServer server;
  private DataExportProxyController controller;

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    WebClient webClient = WebClient.builder().baseUrl(server.url("/").toString()).build();
    controller =
        new DataExportProxyController(
            webClient,
            new AppHttpProperties(
                Duration.ofSeconds(3),
                Duration.ofSeconds(5),
                Duration.ofSeconds(120),
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                AppHttpProperties.BackendProtocol.H2,
                20,
                AppHttpProperties.BackendCodec.CBOR,
                false));
  }

  @AfterEach
  void tearDown() throws Exception {
    try {
      server.shutdown();
    } catch (Exception ignored) {
    }
  }

  @Test
  void json_streamsTheOwnExportFromTheIdFreeBackendPath() throws Exception {
    server.enqueue(okResponse("application/json"));

    ResponseEntity<byte[]> response = controller.json();

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertArrayEquals(DOCUMENT, response.getBody());
    assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
    assertEquals("/api/v1/users/me/export", takePath());
  }

  @Test
  void pdf_streamsTheOwnExportFromTheIdFreeBackendPath() throws Exception {
    server.enqueue(okResponse("application/pdf"));

    ResponseEntity<byte[]> response = controller.pdf();

    assertEquals(MediaType.APPLICATION_PDF, response.getHeaders().getContentType());
    assertEquals("/api/v1/users/me/export/pdf", takePath());
  }

  @Test
  void theOwnDownloadFilenameCarriesNoIdentifierAtAll() throws Exception {
    server.enqueue(okResponse("application/json"));

    String disposition = disposition(controller.json());

    assertTrue(
        disposition.contains("filename=\"datenauskunft.json\""),
        "unexpected disposition: " + disposition);
    assertFalse(
        disposition.contains(SUBJECT.toString()), "the member's own filename needs no identifier");
  }

  @Test
  void adminPdf_relaysTheSubjectIdAndNamesTheFileAfterIt() throws Exception {
    server.enqueue(okResponse("application/pdf"));

    String disposition = disposition(controller.adminPdf(SUBJECT));

    assertEquals("/api/v1/admin/users/" + SUBJECT + "/export/pdf", takePath());
    assertTrue(
        disposition.contains("filename=\"datenauskunft-" + SUBJECT + ".pdf\""),
        "unexpected disposition: " + disposition);
  }

  @Test
  void adminJson_relaysTheSubjectIdAndNamesTheFileAfterIt() throws Exception {
    server.enqueue(okResponse("application/json"));

    String disposition = disposition(controller.adminJson(SUBJECT));

    assertEquals("/api/v1/admin/users/" + SUBJECT + "/export", takePath());
    assertTrue(
        disposition.contains("filename=\"datenauskunft-" + SUBJECT + ".json\""),
        "unexpected disposition: " + disposition);
  }

  @Test
  void aBackendErrorStatusIsRelayedRatherThanFlattenedTo500() {
    server.enqueue(new MockResponse().setResponseCode(404).setBody("no export"));

    ResponseStatusException ex = assertThrows(ResponseStatusException.class, controller::json);

    assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
  }

  @Test
  void aBackendForbiddenIsRelayedOnTheAdminPath() {
    server.enqueue(new MockResponse().setResponseCode(403));

    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> controller.adminPdf(SUBJECT));

    assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
  }

  @Test
  void anUnreachableBackendIs500AndNotAnUnhandledException() throws Exception {
    server.shutdown();

    ResponseStatusException ex = assertThrows(ResponseStatusException.class, controller::pdf);

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.getStatusCode());
  }

  /**
   * Builds a successful backend response carrying the stand-in document.
   *
   * @param contentType the backend's content type
   * @return the enqueueable response
   */
  private static MockResponse okResponse(String contentType) {
    return new MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", contentType)
        .setBody(new String(DOCUMENT, StandardCharsets.UTF_8));
  }

  /**
   * Reads the path the proxy actually requested.
   *
   * @return the recorded request path
   * @throws InterruptedException if the wait is interrupted
   */
  private String takePath() throws InterruptedException {
    RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
    assertNotNull(request, "the proxy made no backend request");
    assertEquals("GET", request.getMethod());
    return request.getPath();
  }

  /**
   * Reads the {@code Content-Disposition} the proxy put on its own response.
   *
   * @param response the proxy response
   * @return the header value
   */
  private static String disposition(ResponseEntity<byte[]> response) {
    String value = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
    assertNotNull(value, "the download carries no Content-Disposition");
    return value;
  }
}
