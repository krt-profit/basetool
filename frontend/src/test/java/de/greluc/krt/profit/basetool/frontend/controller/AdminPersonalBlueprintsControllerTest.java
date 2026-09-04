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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.model.dto.BlueprintImportPreviewDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BlueprintImportResultDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PersonalBlueprintBatchCreateRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.PersonalBlueprintBatchResultDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PersonalBlueprintBulkDeleteResultDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
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
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

/**
 * Unit tests for {@link AdminPersonalBlueprintsPageController}: every write proxies to the admin
 * backend surface with the target {@code sub} from the path. {@link MockWebServer} exercises the
 * multipart import-preview chain; the JSON paths mock {@link BackendApiClient}.
 */
class AdminPersonalBlueprintsControllerTest {

  /**
   * The target member, a Keycloak {@code sub} — which Keycloak issues, and the backend binds, as a
   * UUID.
   */
  private static final UUID TARGET = UUID.fromString("11111111-2222-3333-4444-555555555555");

  private MockWebServer server;
  private BackendApiClient backendApiClient;
  private AdminPersonalBlueprintsPageController controller;

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    WebClient webClient = WebClient.builder().baseUrl(server.url("/").toString()).build();
    backendApiClient = mock(BackendApiClient.class);
    controller = new AdminPersonalBlueprintsPageController(backendApiClient, webClient);
  }

  @AfterEach
  void tearDown() throws Exception {
    try {
      server.shutdown();
    } catch (Exception ignored) {
      // already shut down
    }
  }

  @Test
  void addSelected_relaysToAdminBatchEndpointForTargetUser() {
    when(backendApiClient.post(
            contains("/api/v1/admin/personal-blueprints/" + TARGET + "/batch"),
            any(PersonalBlueprintBatchCreateRequest.class),
            eq(PersonalBlueprintBatchResultDto.class)))
        .thenReturn(new PersonalBlueprintBatchResultDto(2, 0, 0));

    PersonalBlueprintBatchResultDto result = controller.addSelected(TARGET, List.of("a", "b"));

    assertEquals(2, result.added());
  }

  @Test
  void updateNote_relaysToAdminItemEndpointAndRedirectsToUser() {
    UUID id = UUID.randomUUID();
    RedirectAttributesModelMap flash = new RedirectAttributesModelMap();

    String view = controller.updateNote(TARGET, id, "note", null, 1L, flash);

    assertEquals("redirect:/admin/personal-blueprints?userSub=" + TARGET, view);
    verify(backendApiClient)
        .put(contains("/api/v1/admin/personal-blueprints/items/" + id), any(), any());
    assertEquals(
        "personalInventory.blueprints.toast.noteUpdated",
        flash.getFlashAttributes().get("successToast"));
  }

  @Test
  void delete_relaysToAdminItemEndpointAndRedirectsToUser() {
    UUID id = UUID.randomUUID();
    RedirectAttributesModelMap flash = new RedirectAttributesModelMap();

    String view = controller.delete(TARGET, id, flash);

    assertEquals("redirect:/admin/personal-blueprints?userSub=" + TARGET, view);
    verify(backendApiClient)
        .delete(contains("/api/v1/admin/personal-blueprints/items/" + id), eq(Void.class));
  }

  @Test
  void deleteAllUsers_relaysToAdminPurgeEndpointAndRedirects() {
    when(backendApiClient.delete(
            eq("/api/v1/admin/personal-blueprints"),
            eq(PersonalBlueprintBulkDeleteResultDto.class)))
        .thenReturn(new PersonalBlueprintBulkDeleteResultDto(3));
    RedirectAttributesModelMap flash = new RedirectAttributesModelMap();

    String view = controller.deleteAllUsers(flash);

    assertEquals("redirect:/admin/personal-blueprints", view);
    verify(backendApiClient)
        .delete(
            eq("/api/v1/admin/personal-blueprints"),
            eq(PersonalBlueprintBulkDeleteResultDto.class));
    assertEquals(
        "admin.personalInventory.blueprints.purge.toast.done",
        flash.getFlashAttributes().get("successToast"));
  }

  @Test
  void deleteAllUsersAjax_relaysToAdminPurgeEndpointAndReturnsCount() {
    when(backendApiClient.delete(
            eq("/api/v1/admin/personal-blueprints"),
            eq(PersonalBlueprintBulkDeleteResultDto.class)))
        .thenReturn(new PersonalBlueprintBulkDeleteResultDto(8));

    ResponseEntity<Object> response = controller.deleteAllUsersAjax();

    assertEquals(200, response.getStatusCode().value());
    assertEquals(new PersonalBlueprintBulkDeleteResultDto(8), response.getBody());
  }

  @Test
  void previewImport_forwardsMultipartToAdminBackendPath() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"total\":0,\"matched\":0,\"matchedByAlias\":0,\"suggested\":0,\"unmatched\":0,"
                    + "\"alreadyOwned\":0,\"entries\":[]}"));

    MultipartFile file =
        new MockMultipartFile(
            "file",
            "scmdb.json",
            "application/json",
            "{\"blueprints\":[]}".getBytes(StandardCharsets.UTF_8));

    BlueprintImportPreviewDto preview = controller.previewImport(TARGET, file);

    assertEquals(0, preview.total());
    RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
    assertEquals("/api/v1/admin/personal-blueprints/" + TARGET + "/import/preview", req.getPath());
  }

  @Test
  void applyImport_relaysToAdminApplyEndpoint() {
    when(backendApiClient.post(
            contains("/api/v1/admin/personal-blueprints/" + TARGET + "/import/apply"),
            any(),
            eq(BlueprintImportResultDto.class)))
        .thenReturn(new BlueprintImportResultDto(1, 1, 0, 0, 0));

    BlueprintImportResultDto result = controller.applyImport(TARGET, List.of());

    assertEquals(1, result.added());
    assertEquals(1, result.aliasesLearned());
  }

  @Test
  void applyImport_onBackendError_wrapsAs500() {
    when(backendApiClient.post(any(), any(), eq(BlueprintImportResultDto.class)))
        .thenThrow(new RuntimeException("boom"));

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class, () -> controller.applyImport(TARGET, List.of()));

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.getStatusCode());
  }
}
