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

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.model.dto.ImportIssueCode;
import de.greluc.krt.profit.basetool.frontend.model.dto.ImportIssueDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ImportIssueSeverity;
import de.greluc.krt.profit.basetool.frontend.model.dto.ImportSuggestionDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.RefineryGoodDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.RefineryImportDraftDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.RefineryOrderDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.RefineryOrderStatus;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * MVC tests for the #591 in-place screenshot-import twin {@code
 * RefineryOrderPageController.importExtractAjax}. Drives the real Thymeleaf render of the {@code
 * refinery-orders-create :: refineryImportFormBody} fragment so a render-time 500 (which pure
 * controller tests miss — it has bitten this project before) cannot slip through, and asserts that
 * every branch returns the fragment inline (never a redirect) and that the file-picker chrome stays
 * OUTSIDE the swapped fragment.
 */
@SpringBootTest
class RefineryImportAjaxControllerTest {

  private static final UUID MATERIAL_ID = UUID.randomUUID();
  private static final UUID SUGGESTION_ID = UUID.randomUUID();
  private static final String FRAGMENT = "refinery-orders-create :: refineryImportFormBody";

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    // The goods-row selects render from the cached material catalog; the matched row keeps its
    // pre-selected option and the unmatched row offers the suggested material as a chip.
    PageResponse<MaterialDto> materials =
        new PageResponse<>(
            List.of(
                material(MATERIAL_ID, "Stileron (Raw)"), material(SUGGESTION_ID, "Aluminum (Raw)")),
            0,
            1000,
            2,
            1,
            Collections.emptyList());
    when(backendApiClient.getCached(eq("/api/v1/materials?size=1000"), anyTypeRef(), eq(true)))
        .thenReturn(materials);
  }

  @Test
  void importExtractAjax_happyPath_rendersFormFragmentWithoutPickerChrome() throws Exception {
    RefineryGoodDto matched =
        new RefineryGoodDto(
            null, material(MATERIAL_ID, "Stileron (Raw)"), 957, null, 448, 618, null);
    RefineryGoodDto unmatched = new RefineryGoodDto(null, null, 300, null, 140, 0, null);
    ImportIssueDto unmatchedIssue =
        new ImportIssueDto(
            "goods[1].inputMaterial",
            "ALUMINIUM (ORE)",
            ImportIssueCode.UNMATCHED_MATERIAL,
            ImportIssueSeverity.WARNING,
            0.95,
            List.of(new ImportSuggestionDto(SUGGESTION_ID, "Aluminum (Raw)", 0.889)));
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
            null,
            null,
            Instant.parse("2026-06-01T19:39:01Z"),
            1258L,
            48928.0,
            null,
            null,
            null,
            null,
            List.of(matched, unmatched),
            RefineryOrderStatus.OPEN,
            null,
            null,
            null);
    when(backendApiClient.post(
            eq("/api/v1/refinery-orders/import-extract"), any(), eq(RefineryImportDraftDto.class)))
        .thenReturn(
            new RefineryImportDraftDto(order, List.of(locationIssue, unmatchedIssue), 1, 2, 0));

    mockMvc
        .perform(
            multipart("/refinery-orders/import")
                .file(upload("{\"schemaVersion\":1}"))
                .header("X-Requested-With", "XMLHttpRequest")
                .with(oidcLogin())
                .with(csrf()))
        .andExpect(status().isOk())
        .andExpect(view().name(FRAGMENT))
        // banner with counters + the order-level finding
        .andExpect(content().string(containsString("data-testid=\"refinery-import-banner\"")))
        // both goods rows render their input-material selects
        .andExpect(content().string(containsString("inputMaterialId_0")))
        .andExpect(content().string(containsString("inputMaterialId_1")))
        // the unmatched row carries the inline flag block + the one-click suggestion chip
        .andExpect(content().string(containsString("data-testid=\"refinery-import-row-flags-1\"")))
        .andExpect(content().string(containsString("data-testid=\"refinery-import-suggestion-1\"")))
        .andExpect(content().string(containsString("data-material-id=\"" + SUGGESTION_ID + "\"")))
        // the swapped fragment must NOT carry the file-picker chrome — it stays OUTSIDE the
        // fragment so its delegated triggers survive every swap
        .andExpect(content().string(not(containsString("data-testid=\"refinery-import-button\""))))
        .andExpect(content().string(not(containsString("id=\"refineryImportForm\""))));

    verify(backendApiClient)
        .post(
            eq("/api/v1/refinery-orders/import-extract"), any(), eq(RefineryImportDraftDto.class));
  }

  @Test
  void importExtractAjax_invalidFile_rendersErrorFragmentWithoutBackendCall() throws Exception {
    mockMvc
        .perform(
            multipart("/refinery-orders/import")
                .file(
                    new MockMultipartFile(
                        "file",
                        "shot.png",
                        "image/png",
                        "not json".getBytes(StandardCharsets.UTF_8)))
                .header("X-Requested-With", "XMLHttpRequest")
                .with(oidcLogin())
                .with(csrf()))
        .andExpect(status().isOk())
        .andExpect(view().name(FRAGMENT))
        .andExpect(content().string(containsString("data-testid=\"refinery-import-error\"")));

    verify(backendApiClient, never())
        .post(
            eq("/api/v1/refinery-orders/import-extract"), any(), eq(RefineryImportDraftDto.class));
  }

  @Test
  void importExtractAjax_backendProblemWithDetail_rendersVerbatimDetailInline() throws Exception {
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

    mockMvc
        .perform(
            multipart("/refinery-orders/import")
                .file(upload("{\"schemaVersion\":2}"))
                .header("X-Requested-With", "XMLHttpRequest")
                .locale(Locale.GERMAN)
                .with(oidcLogin())
                .with(csrf()))
        .andExpect(status().isOk())
        .andExpect(view().name(FRAGMENT))
        .andExpect(content().string(containsString("data-testid=\"refinery-import-error\"")))
        .andExpect(
            content()
                .string(
                    containsString(
                        "Die Extract-Datei verwendet eine nicht unterstützte Schema-Version.")));
  }

  @Test
  void importExtractAjax_nullDraft_rendersGenericErrorFragment() throws Exception {
    when(backendApiClient.post(
            eq("/api/v1/refinery-orders/import-extract"), any(), eq(RefineryImportDraftDto.class)))
        .thenReturn(null);

    mockMvc
        .perform(
            multipart("/refinery-orders/import")
                .file(upload("{\"schemaVersion\":1}"))
                .header("X-Requested-With", "XMLHttpRequest")
                .with(oidcLogin())
                .with(csrf()))
        .andExpect(status().isOk())
        .andExpect(view().name(FRAGMENT))
        .andExpect(content().string(containsString("data-testid=\"refinery-import-error\"")));
  }

  private static MockMultipartFile upload(String json) {
    return new MockMultipartFile(
        "file", "extract.json", "application/json", json.getBytes(StandardCharsets.UTF_8));
  }

  private static MaterialDto material(UUID id, String name) {
    return new MaterialDto(
        id, name, "RAW", "SCU", null, null, null, false, false, false, false, false, false, true,
        0L);
  }
}
