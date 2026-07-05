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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import de.greluc.krt.profit.basetool.frontend.model.dto.P4kImportJobDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.CacheDomain;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
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
 * Unit tests for {@link AdminP4kImportPageController}. {@link MockWebServer} stands in for the
 * backend so the real WebClient chains (multipart upload, JSON list / get / apply) are exercised
 * end to end, and the backend-error relay is verified.
 */
class AdminP4kImportPageControllerTest {

  private MockWebServer server;
  private AdminP4kImportPageController controller;
  private BackendApiClient backendApiClient;

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    WebClient webClient = WebClient.builder().baseUrl(server.url("/").toString()).build();
    backendApiClient = mock(BackendApiClient.class);
    controller = new AdminP4kImportPageController(webClient, backendApiClient);
  }

  @AfterEach
  void tearDown() throws Exception {
    try {
      server.shutdown();
    } catch (Exception ignored) {
      // already shut down
    }
  }

  private static MultipartFile catalog() {
    return new MockMultipartFile(
        "file", "p4k.json", "application/json", "{\"items\":[]}".getBytes(StandardCharsets.UTF_8));
  }

  private static String jobJson(String id, String kind, String status) {
    return "{\"id\":\""
        + id
        + "\",\"kind\":\""
        + kind
        + "\",\"status\":\""
        + status
        + "\",\"seedNew\":false,\"sourceFilename\":\"p4k.json\",\"fileSizeBytes\":12,"
        + "\"previewJobId\":null,\"result\":null,\"errorMessage\":null,"
        + "\"createdAt\":\"2026-06-05T19:00:00Z\",\"startedAt\":null,\"finishedAt\":null}";
  }

  @Test
  void enqueuePreview_proxiesMultipartAndReturnsAcceptedJob() throws Exception {
    UUID id = UUID.randomUUID();
    server.enqueue(
        new MockResponse()
            .setResponseCode(202)
            .setHeader("Content-Type", "application/json")
            .setBody(jobJson(id.toString(), "PREVIEW", "PENDING")));

    ResponseEntity<P4kImportJobDto> response = controller.enqueuePreview(catalog());

    assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
    assertNotNull(response.getBody());
    assertEquals(id.toString(), response.getBody().id());
    assertEquals("PREVIEW", response.getBody().kind());
    assertEquals("PENDING", response.getBody().status());

    RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
    assertNotNull(req);
    assertEquals("POST", req.getMethod());
    assertEquals("/api/v1/admin/import/p4k/jobs", req.getPath());
    assertTrue(req.getHeader("Content-Type").startsWith(MediaType.MULTIPART_FORM_DATA_VALUE));
    assertTrue(req.getBody().readUtf8().contains("filename=\"p4k.json\""));
  }

  @Test
  void listJobs_returnsParsedList() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "["
                    + jobJson(UUID.randomUUID().toString(), "PREVIEW", "SUCCEEDED")
                    + ","
                    + jobJson(UUID.randomUUID().toString(), "APPLY", "RUNNING")
                    + "]"));

    List<P4kImportJobDto> jobs = controller.listJobs();

    assertEquals(2, jobs.size());
    assertEquals("SUCCEEDED", jobs.get(0).status());
    assertEquals("APPLY", jobs.get(1).kind());

    RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
    assertNotNull(req);
    assertEquals("GET", req.getMethod());
    assertEquals("/api/v1/admin/import/p4k/jobs", req.getPath());
  }

  @Test
  void getJob_returnsParsedJob() throws Exception {
    UUID id = UUID.randomUUID();
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(jobJson(id.toString(), "PREVIEW", "SUCCEEDED")));

    P4kImportJobDto job = controller.getJob(id);

    assertEquals(id.toString(), job.id());
    assertEquals("SUCCEEDED", job.status());

    RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
    assertNotNull(req);
    assertEquals("GET", req.getMethod());
    assertEquals("/api/v1/admin/import/p4k/jobs/" + id, req.getPath());
  }

  @Test
  void applyJob_proxiesWithSeedFlagAndReturnsAcceptedJob() throws Exception {
    UUID id = UUID.randomUUID();
    server.enqueue(
        new MockResponse()
            .setResponseCode(202)
            .setHeader("Content-Type", "application/json")
            .setBody(jobJson(UUID.randomUUID().toString(), "APPLY", "PENDING")));

    ResponseEntity<P4kImportJobDto> response = controller.applyJob(id, true);

    assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
    assertNotNull(response.getBody());
    assertEquals("APPLY", response.getBody().kind());

    RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
    assertNotNull(req);
    assertEquals("POST", req.getMethod());
    assertEquals("/api/v1/admin/import/p4k/jobs/" + id + "/apply?seedNew=true", req.getPath());

    // An apply rewrites the master-data catalogues the frontend caches, so the affected domains are
    // evicted at apply-enqueue time (F6).
    verify(backendApiClient)
        .evict(
            CacheDomain.MATERIAL,
            CacheDomain.MANUFACTURER,
            CacheDomain.SHIP_TYPE,
            CacheDomain.ITEM_CATALOG);
  }

  @Test
  void enqueuePreview_onBackend400_propagatesAsBadRequest() {
    server.enqueue(new MockResponse().setResponseCode(400).setBody("bad catalog"));

    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> controller.enqueuePreview(catalog()));

    assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
  }
}
