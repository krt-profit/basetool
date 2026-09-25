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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.model.dto.ImportIssueCode;
import de.greluc.krt.profit.basetool.frontend.model.dto.ImportIssueDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ImportIssueSeverity;
import de.greluc.krt.profit.basetool.frontend.model.dto.ImportSuggestionDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.LocationDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.RefineryGoodDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.RefineryImportDraftDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.RefineryOrderDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.RefineryOrderStatus;
import de.greluc.krt.profit.basetool.frontend.model.dto.RefiningMethodDto;
import de.greluc.krt.profit.basetool.frontend.model.form.RefineryOrderForm;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

/**
 * Unit tests for {@link RefineryImportProxyController}: the JSON relay to the backend, the
 * draft-to-form mapping (hours/minutes split, row issues grouped by draft index) and every error
 * branch, against a mocked {@link BackendApiClient}.
 */
class RefineryImportProxyControllerTest {

  private static final UUID MATERIAL_ID = UUID.randomUUID();
  private static final UUID REFINED_ID = UUID.randomUUID();
  private static final UUID LOCATION_ID = UUID.randomUUID();
  private static final UUID METHOD_ID = UUID.randomUUID();

  private BackendApiClient backendApiClient;
  private RefineryImportProxyController controller;
  private RedirectAttributesModelMap redirectAttributes;

  @BeforeEach
  void setUp() {
    backendApiClient = mock(BackendApiClient.class);
    controller = new RefineryImportProxyController(backendApiClient);
    redirectAttributes = new RedirectAttributesModelMap();
  }

  @Test
  void importExtract_happyPath_flashesPrefilledFormAndIssues() {
    MaterialDto raw = material(MATERIAL_ID, "Stileron (Raw)");
    MaterialDto refined = material(REFINED_ID, "Stileron");
    RefineryGoodDto matched = new RefineryGoodDto(null, raw, 957, refined, 448, 618, null);
    RefineryGoodDto unmatched = new RefineryGoodDto(null, null, 300, null, 140, 0, null);
    ImportIssueDto unmatchedIssue =
        new ImportIssueDto(
            "goods[1].inputMaterial",
            "ALUMINIUM (ORE)",
            ImportIssueCode.UNMATCHED_MATERIAL,
            ImportIssueSeverity.WARNING,
            0.95,
            List.of(new ImportSuggestionDto(MATERIAL_ID, "Aluminum (Raw)", 0.889)));
    ImportIssueDto skippedIssue =
        new ImportIssueDto(
            "goods[2]",
            "INERT MATERIALS",
            ImportIssueCode.SKIPPED_REFINE_OFF,
            ImportIssueSeverity.INFO,
            null,
            null);
    ImportIssueDto locationIssue =
        new ImportIssueDto(
            "location",
            null,
            ImportIssueCode.UNRESOLVED_LOCATION,
            ImportIssueSeverity.WARNING,
            null,
            null);
    RefineryOrderDto order =
        new RefineryOrderDto(
            null,
            null,
            new LocationDto(LOCATION_ID, "Levski", null, false, false, 0L),
            null,
            Instant.parse("2026-06-01T19:39:01Z"),
            1258L,
            48928.0,
            null,
            null,
            null,
            new RefiningMethodDto(METHOD_ID, "Ferron Exchange", null, null, null, null, null),
            List.of(matched, unmatched),
            RefineryOrderStatus.OPEN,
            null,
            null,
            null);
    RefineryImportDraftDto draft =
        new RefineryImportDraftDto(
            order, List.of(locationIssue, unmatchedIssue, skippedIssue), 1, 3, 1);
    when(backendApiClient.post(
            eq("/api/v1/refinery-orders/import-extract"), any(), eq(RefineryImportDraftDto.class)))
        .thenReturn(draft);

    String view = controller.importExtract(jsonUpload("{\"schemaVersion\":1}"), redirectAttributes);

    assertThat(view).isEqualTo("redirect:/refinery-orders/create");
    Map<String, Object> flash = flash();
    RefineryOrderForm form = (RefineryOrderForm) flash.get("refineryOrderForm");
    assertThat(form).isNotNull();
    assertThat(form.getLocationId()).isEqualTo(LOCATION_ID);
    assertThat(form.getRefiningMethodId()).isEqualTo(METHOD_ID);
    assertThat(form.getStartedAt()).isEqualTo("2026-06-01T19:39:01Z");
    assertThat(form.getDurationHours()).isEqualTo(20);
    assertThat(form.getDurationMinutes()).isEqualTo(58);
    assertThat(form.getExpenses()).isEqualTo(48928.0);
    assertThat(form.getStatus()).isEqualTo(RefineryOrderStatus.OPEN);
    assertThat(form.getGoods()).hasSize(2);
    assertThat(form.getGoods().get(0).getInputMaterialId()).isEqualTo(MATERIAL_ID);
    assertThat(form.getGoods().get(0).getOutputMaterialId()).isEqualTo(REFINED_ID);
    assertThat(form.getGoods().get(0).getQuality()).isEqualTo(618);
    assertThat(form.getGoods().get(1).getInputMaterialId()).isNull();

    @SuppressWarnings("unchecked")
    Map<String, List<ImportIssueDto>> rowIssues =
        (Map<String, List<ImportIssueDto>>) flash.get("importRowIssues");
    assertThat(rowIssues).containsOnlyKeys("1");
    assertThat(rowIssues.get("1").getFirst().code()).isEqualTo(ImportIssueCode.UNMATCHED_MATERIAL);
    @SuppressWarnings("unchecked")
    List<ImportIssueDto> general = (List<ImportIssueDto>) flash.get("importIssues");
    assertThat(general)
        .extracting(ImportIssueDto::code)
        .containsExactly(ImportIssueCode.UNRESOLVED_LOCATION, ImportIssueCode.SKIPPED_REFINE_OFF);
    assertThat(flash.get("importGoodsMatched")).isEqualTo(1);
    assertThat(flash.get("importGoodsTotal")).isEqualTo(3);
    assertThat(flash.get("importRowsSkipped")).isEqualTo(1);
  }

  @Test
  void importExtract_nonJsonUpload_flashesInvalidFileWithoutBackendCall() {
    MultipartFile file =
        new MockMultipartFile(
            "file", "screenshot.png", "image/png", "not json".getBytes(StandardCharsets.UTF_8));

    String view = controller.importExtract(file, redirectAttributes);

    assertThat(view).isEqualTo("redirect:/refinery-orders/create");
    assertThat(flash()).containsEntry("importErrorKey", "refineryImport.error.invalidFile");
    verifyNoInteractions(backendApiClient);
  }

  @Test
  void importExtract_jsonArrayUpload_flashesInvalidFile() {
    String view = controller.importExtract(jsonUpload("[1,2,3]"), redirectAttributes);

    assertThat(view).isEqualTo("redirect:/refinery-orders/create");
    assertThat(flash()).containsEntry("importErrorKey", "refineryImport.error.invalidFile");
    verifyNoInteractions(backendApiClient);
  }

  @Test
  void importExtract_emptyUpload_flashesInvalidFile() {
    MultipartFile file =
        new MockMultipartFile("file", "empty.json", "application/json", new byte[0]);

    String view = controller.importExtract(file, redirectAttributes);

    assertThat(view).isEqualTo("redirect:/refinery-orders/create");
    assertThat(flash()).containsEntry("importErrorKey", "refineryImport.error.invalidFile");
  }

  @Test
  void importExtract_oversizedUpload_flashesInvalidFileWithoutBackendCall() {
    byte[] oversized = new byte[(int) RefineryImportProxyController.MAX_EXTRACT_BYTES + 1];
    MultipartFile file =
        new MockMultipartFile("file", "screenshot.png", "application/json", oversized);

    String view = controller.importExtract(file, redirectAttributes);

    assertThat(view).isEqualTo("redirect:/refinery-orders/create");
    assertThat(flash()).containsEntry("importErrorKey", "refineryImport.error.invalidFile");
    verifyNoInteractions(backendApiClient);
  }

  @Test
  void handleOversizedUpload_flashesInvalidFile() {
    String view = controller.handleOversizedUpload(redirectAttributes);

    assertThat(view).isEqualTo("redirect:/refinery-orders/create");
    assertThat(flash()).containsEntry("importErrorKey", "refineryImport.error.invalidFile");
  }

  @Test
  void rowIssues_indexBeyondIntRange_treatedAsUnanchoredInsteadOfThrowing() {
    ImportIssueDto overflow =
        new ImportIssueDto(
            "goods[99999999999999999999].inputMaterial",
            "STILERON (ORE)",
            ImportIssueCode.UNMATCHED_MATERIAL,
            ImportIssueSeverity.WARNING,
            null,
            null);

    Map<String, List<ImportIssueDto>> byRow =
        RefineryImportProxyController.rowIssues(List.of(overflow));
    List<ImportIssueDto> general = RefineryImportProxyController.generalIssues(List.of(overflow));

    assertThat(byRow).isEmpty();
    assertThat(general).containsExactly(overflow);
  }

  @Test
  void importExtract_backendProblemWithoutDetail_flashesGenericError() {
    when(backendApiClient.post(
            eq("/api/v1/refinery-orders/import-extract"), any(), eq(RefineryImportDraftDto.class)))
        .thenThrow(
            new BackendServiceException(
                "400 from backend", null, 400, "BAD_REQUEST", null, Collections.emptyList(), null));

    String view = controller.importExtract(jsonUpload("{\"schemaVersion\":2}"), redirectAttributes);

    assertThat(view).isEqualTo("redirect:/refinery-orders/create");
    assertThat(flash()).containsEntry("importErrorKey", "refineryImport.error.failed");
    assertThat(flash()).doesNotContainKey("importErrorText");
  }

  @Test
  void importExtract_nullDraft_flashesGenericError() {
    when(backendApiClient.post(
            eq("/api/v1/refinery-orders/import-extract"), any(), eq(RefineryImportDraftDto.class)))
        .thenReturn(null);

    String view = controller.importExtract(jsonUpload("{\"schemaVersion\":1}"), redirectAttributes);

    assertThat(view).isEqualTo("redirect:/refinery-orders/create");
    assertThat(flash()).containsEntry("importErrorKey", "refineryImport.error.failed");
    assertThat(flash()).doesNotContainKey("refineryOrderForm");
  }

  @Test
  void importExtract_backendProblem_surfacesLocalizedDetailVerbatim() {
    when(backendApiClient.post(
            eq("/api/v1/refinery-orders/import-extract"), any(), eq(RefineryImportDraftDto.class)))
        .thenThrow(
            new BackendServiceException(
                "400 from backend",
                null,
                400,
                "BAD_REQUEST",
                null,
                Collections.emptyList(),
                "Die Extract-Datei verwendet eine nicht unterstützte Schema-Version."));

    String view = controller.importExtract(jsonUpload("{\"schemaVersion\":2}"), redirectAttributes);

    assertThat(view).isEqualTo("redirect:/refinery-orders/create");
    assertThat(flash())
        .containsEntry(
            "importErrorText",
            "Die Extract-Datei verwendet eine nicht unterstützte Schema-Version.");
    assertThat(flash()).doesNotContainKey("refineryOrderForm");
  }

  @Test
  void importExtract_unexpectedFailure_flashesGenericError() {
    when(backendApiClient.post(
            eq("/api/v1/refinery-orders/import-extract"), any(), eq(RefineryImportDraftDto.class)))
        .thenThrow(new IllegalStateException("boom"));

    String view = controller.importExtract(jsonUpload("{\"schemaVersion\":1}"), redirectAttributes);

    assertThat(view).isEqualTo("redirect:/refinery-orders/create");
    assertThat(flash()).containsEntry("importErrorKey", "refineryImport.error.failed");
  }

  @Test
  void toForm_keepsSeededEmptyRowForEmptyGoodsDraft() {
    RefineryOrderDto order =
        new RefineryOrderDto(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            RefineryOrderStatus.OPEN,
            null,
            null,
            null);

    RefineryOrderForm form = RefineryImportProxyController.toForm(order);

    assertThat(form.getGoods()).hasSize(1);
    assertThat(form.getGoods().getFirst().getInputMaterialId()).isNull();
    assertThat(form.getStartedAt()).isNull();
  }

  private Map<String, Object> flash() {
    return new java.util.HashMap<>(redirectAttributes.getFlashAttributes());
  }

  private static MultipartFile jsonUpload(String json) {
    return new MockMultipartFile(
        "file", "extract.json", "application/json", json.getBytes(StandardCharsets.UTF_8));
  }

  private static MaterialDto material(UUID id, String name) {
    return new MaterialDto(
        id, name, "RAW", "SCU", null, null, null, false, false, false, false, false, false, true,
        0L);
  }
}
