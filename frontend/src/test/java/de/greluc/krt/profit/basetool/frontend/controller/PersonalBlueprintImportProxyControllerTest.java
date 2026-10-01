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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.model.dto.BlueprintImportApplyRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.BlueprintImportPreviewDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BlueprintImportResolutionDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BlueprintImportResultDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BlueprintImportStatus;
import de.greluc.krt.profit.basetool.frontend.model.dto.HandoffKind;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.service.IngestHandoffService;
import de.greluc.krt.profit.basetool.frontend.support.RealBackendApiClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
 * Unit tests for {@link PersonalBlueprintImportProxyController}. {@link MockWebServer} stands in
 * for the backend so the real WebClient multipart chain on the preview path is exercised; the JSON
 * apply path mocks {@link BackendApiClient}.
 */
class PersonalBlueprintImportProxyControllerTest {

  private MockWebServer server;
  private BackendApiClient backendApiClient;
  private IngestHandoffService ingestHandoffService;
  private PersonalBlueprintImportProxyController controller;

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    WebClient webClient = WebClient.builder().baseUrl(server.url("/").toString()).build();
    backendApiClient = RealBackendApiClient.mockExecutingOver(webClient);
    ingestHandoffService = mock(IngestHandoffService.class);
    StaticMessageSource messages = new StaticMessageSource();
    messages.addMessage(
        PersonalBlueprintImportProxyController.EMPTY_KEY, Locale.getDefault(), "Leere Datei.");
    messages.addMessage(
        PersonalBlueprintImportProxyController.TOO_LARGE_KEY, Locale.getDefault(), "Zu gross.");
    controller =
        new PersonalBlueprintImportProxyController(
            backendApiClient, ingestHandoffService, messages);
  }

  /**
   * Reads a field of a problem body.
   *
   * @param response the refusal
   * @param field the field name
   * @return its value
   */
  private static Object field(ResponseEntity<Object> response, String field) {
    return ((Map<?, ?>) response.getBody()).get(field);
  }

  @AfterEach
  void tearDown() throws Exception {
    try {
      server.shutdown();
    } catch (Exception ignored) {
    }
  }

  @Test
  void preview_proxiesMultipartAndParsesPreview() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"total\":2,\"matched\":1,\"matchedByAlias\":0,\"suggested\":1,\"unmatched\":0,"
                    + "\"alreadyOwned\":0,\"entries\":["
                    + "{\"externalName\":\"Arclight Pistol\",\"status\":\"MATCHED\","
                    + "\"productKey\":\"arclight pistol\",\"productName\":\"Arclight Pistol\","
                    + "\"outputItemId\":null,\"suggestedAcquiredAt\":null,\"suggestions\":[]},"
                    + "{\"externalName\":\"Calico Legs Tacticl\",\"status\":\"SUGGESTED\","
                    + "\"productKey\":null,\"productName\":null,\"outputItemId\":null,"
                    + "\"suggestedAcquiredAt\":null,\"suggestions\":[{\"productKey\":"
                    + "\"calico legs tactical\",\"productName\":\"Calico Legs Tactical\","
                    + "\"score\":0.9}]}]}"));

    MultipartFile file =
        new MockMultipartFile(
            "file",
            "scmdb.json",
            "application/json",
            "{\"blueprints\":[]}".getBytes(StandardCharsets.UTF_8));

    BlueprintImportPreviewDto preview =
        (BlueprintImportPreviewDto) controller.preview(file).getBody();

    assertNotNull(preview);
    assertEquals(2, preview.total());
    assertEquals(1, preview.matched());
    assertEquals(1, preview.suggested());
    assertEquals(2, preview.entries().size());
    assertEquals(BlueprintImportStatus.MATCHED, preview.entries().get(0).status());
    assertEquals(
        "calico legs tactical", preview.entries().get(1).suggestions().get(0).productKey());

    RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
    assertNotNull(req);
    assertEquals("POST", req.getMethod());
    assertEquals("/api/v1/personal-blueprints/import/preview", req.getPath());
    assertTrue(req.getHeader("Content-Type").startsWith(MediaType.MULTIPART_FORM_DATA_VALUE));
    assertTrue(req.getBody().readUtf8().contains("filename=\"scmdb.json\""));
  }

  @Test
  void preview_onBackend400_relaysTheBackendsLocalisedDetail() {
    server.enqueue(
        new MockResponse()
            .setResponseCode(400)
            .setHeader("Content-Type", "application/problem+json")
            .setBody(
                "{\"status\":400,\"code\":\"BAD_REQUEST\","
                    + "\"detail\":\"Die Datei ist kein lesbares JSON.\","
                    + "\"correlationId\":\"cid-7\"}"));

    MultipartFile file =
        new MockMultipartFile(
            "file", "broken.json", "application/json", "garbage".getBytes(StandardCharsets.UTF_8));

    ResponseEntity<Object> refused = controller.preview(file);

    assertEquals(HttpStatus.BAD_REQUEST, refused.getStatusCode());
    assertEquals(MediaType.APPLICATION_PROBLEM_JSON, refused.getHeaders().getContentType());
    assertEquals("BAD_REQUEST", field(refused, "code"));
    assertEquals("Die Datei ist kein lesbares JSON.", field(refused, "detail"));
    assertEquals("cid-7", field(refused, "correlationId"));
  }

  @Test
  void preview_onABackendAnswerWithoutAProblem_relaysTheStatusWithoutADetail() {
    server.enqueue(new MockResponse().setResponseCode(400).setBody("Invalid JSON"));

    MultipartFile file =
        new MockMultipartFile(
            "file", "broken.json", "application/json", "garbage".getBytes(StandardCharsets.UTF_8));

    ResponseEntity<Object> refused = controller.preview(file);

    assertEquals(HttpStatus.BAD_REQUEST, refused.getStatusCode());
    assertEquals("VALIDATION_FAILED", field(refused, "code"));
    assertNull(field(refused, "detail"));
  }

  @Test
  void preview_refusesAnEmptyUploadWithItsLocalisedMessageUnread() {
    MultipartFile file =
        new MockMultipartFile("file", "empty.json", "application/json", new byte[0]);

    ResponseEntity<Object> refused = controller.preview(file);

    assertEquals(HttpStatus.BAD_REQUEST, refused.getStatusCode());
    assertEquals("Leere Datei.", field(refused, "detail"));
    assertEquals(0, server.getRequestCount());
  }

  @Test
  void preview_refusesAnOversizedUploadWithItsLocalisedMessageUnread() {
    MultipartFile file =
        new MockMultipartFile(
            "file",
            "big.json",
            "application/json",
            new byte[(int) PersonalBlueprintImportProxyController.MAX_EXPORT_BYTES + 1]);

    ResponseEntity<Object> refused = controller.preview(file);

    assertEquals(HttpStatus.CONTENT_TOO_LARGE, refused.getStatusCode());
    assertEquals("UPLOAD_TOO_LARGE", field(refused, "code"));
    assertEquals("Zu gross.", field(refused, "detail"));
    assertEquals(0, server.getRequestCount());
  }

  @Test
  void apply_wrapsResolutionsInRequestAndRelays() {
    when(backendApiClient.post(
            eq("/api/v1/personal-blueprints/import/apply"),
            any(BlueprintImportApplyRequest.class),
            eq(BlueprintImportResultDto.class)))
        .thenReturn(new BlueprintImportResultDto(2, 1, 0, 0, 0));

    BlueprintImportResultDto result =
        (BlueprintImportResultDto)
            controller
                .apply(
                    List.of(
                        new BlueprintImportResolutionDto(
                            "Arclight Pistol", "arclight pistol", null, "imported")))
                .getBody();

    assertNotNull(result);
    assertEquals(2, result.added());
    assertEquals(1, result.aliasesLearned());
  }

  @Test
  void apply_onABackendRefusal_relaysItsLocalisedDetail() {
    when(backendApiClient.post(any(), any(), eq(BlueprintImportResultDto.class)))
        .thenThrow(
            new BackendServiceException(
                "Backend returned 400",
                null,
                400,
                "BAD_REQUEST",
                "cid-9",
                List.of(),
                "Die Auswahl ist ungueltig."));

    ResponseEntity<Object> refused =
        controller.apply(List.of(new BlueprintImportResolutionDto("x", "k", null, null)));

    assertEquals(HttpStatus.BAD_REQUEST, refused.getStatusCode());
    assertEquals("BAD_REQUEST", field(refused, "code"));
    assertEquals("Die Auswahl ist ungueltig.", field(refused, "detail"));
  }

  @Test
  void apply_onAnUnexpectedError_answersAnEmpty500() {
    when(backendApiClient.post(any(), any(), eq(BlueprintImportResultDto.class)))
        .thenThrow(new RuntimeException("boom"));

    ResponseEntity<Object> failed =
        controller.apply(List.of(new BlueprintImportResolutionDto("x", "k", null, null)));

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, failed.getStatusCode());
    assertNull(failed.getBody());
  }

  @Test
  void staged_onHit_returnsTheConsumedPreview() {
    var principal = mock(org.springframework.security.oauth2.core.oidc.user.OidcUser.class);
    when(principal.getSubject()).thenReturn("sub-123");
    BlueprintImportPreviewDto preview = mock(BlueprintImportPreviewDto.class);
    when(ingestHandoffService.consume(
            eq("sub-123"),
            eq("handoff-abc"),
            eq(HandoffKind.BLUEPRINT),
            eq(BlueprintImportPreviewDto.class)))
        .thenReturn(Optional.of(preview));

    assertSame(preview, controller.staged("handoff-abc", principal));
  }

  @Test
  void staged_onMiss_throws404() {
    var principal = mock(org.springframework.security.oauth2.core.oidc.user.OidcUser.class);
    when(principal.getSubject()).thenReturn("sub-123");
    when(ingestHandoffService.consume(
            eq("sub-123"),
            eq("expired-id"),
            eq(HandoffKind.BLUEPRINT),
            eq(BlueprintImportPreviewDto.class)))
        .thenReturn(Optional.empty());

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class, () -> controller.staged("expired-id", principal));

    assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
  }
}
