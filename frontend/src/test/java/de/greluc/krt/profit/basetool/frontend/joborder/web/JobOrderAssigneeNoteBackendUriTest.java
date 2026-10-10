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

package de.greluc.krt.profit.basetool.frontend.joborder.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import de.greluc.krt.profit.basetool.frontend.joborder.client.JobOrderBackendClient;
import de.greluc.krt.profit.basetool.frontend.joborder.model.AssigneeNoteRequest;
import de.greluc.krt.profit.basetool.frontend.kernel.livesync.LiveSyncLocalBus;
import de.greluc.krt.profit.basetool.frontend.kernel.web.MutationResponseHelper;
import de.greluc.krt.profit.basetool.frontend.support.RealBackendApiClient;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.ui.ConcurrentModel;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Pins the exact backend request the assignee-note writes send through a real {@code
 * BackendApiClient}: method, path, query and body (REQ-SEC-051).
 */
class JobOrderAssigneeNoteBackendUriTest {

  private static final UUID ORDER = UUID.fromString("0f468278-136b-4988-a223-92692f34ca89");
  private static final UUID USER = UUID.fromString("11111111-2222-3333-4444-555555555555");

  private MockWebServer server;
  private JobOrderWriteController controller;

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    WebClient webClient = WebClient.builder().baseUrl(server.url("/").toString()).build();
    controller =
        new JobOrderWriteController(
            new JobOrderBackendClient(RealBackendApiClient.over(webClient)),
            mock(RoleHierarchy.class),
            mock(MutationResponseHelper.class),
            mock(LiveSyncLocalBus.class));
  }

  @AfterEach
  void tearDown() throws Exception {
    server.shutdown();
  }

  @Test
  void setNoteSendsAPutToTheNotePathWithTheBody() throws Exception {
    enqueueOrder();

    controller.setAssigneeNote(
        ORDER, USER, new AssigneeNoteRequest("Abends ab 20 Uhr", 4L), new ConcurrentModel(), null);

    RecordedRequest request = take();
    assertEquals("PUT", request.getMethod());
    assertEquals("/api/v1/orders/" + ORDER + "/assignees/" + USER + "/note", request.getPath());
    assertEquals("{\"note\":\"Abends ab 20 Uhr\",\"version\":4}", request.getBody().readUtf8());
  }

  @Test
  void deleteNoteWithAVersionSendsItAsTheVersionQueryParameter() throws Exception {
    enqueueOrder();

    controller.deleteAssigneeNote(ORDER, USER, 7L, new ConcurrentModel(), null);

    RecordedRequest request = take();
    assertEquals("DELETE", request.getMethod());
    assertEquals(
        "/api/v1/orders/" + ORDER + "/assignees/" + USER + "/note?version=7", request.getPath());
  }

  @Test
  void deleteNoteWithoutAVersionSendsNoQuery() throws Exception {
    enqueueOrder();

    controller.deleteAssigneeNote(ORDER, USER, null, new ConcurrentModel(), null);

    RecordedRequest request = take();
    assertEquals("DELETE", request.getMethod());
    assertEquals("/api/v1/orders/" + ORDER + "/assignees/" + USER + "/note", request.getPath());
  }

  private void enqueueOrder() {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"id\":\"" + ORDER + "\"}"));
  }

  private RecordedRequest take() throws InterruptedException {
    RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
    assertNotNull(request, "the controller sent no backend request");
    return request;
  }
}
