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

package de.greluc.krt.profit.basetool.frontend.catalogue.web;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.P4kImportChangeDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.P4kImportRunDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.P4kReviewApplyRequest;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.P4kReviewBatchMember;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.P4kReviewBatchRequest;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.P4kReviewCandidate;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.P4kReviewCaseDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.P4kReviewGroupDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.P4kReviewOptions;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.P4kReviewResolveRequest;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.P4kReviewWorkspaceDetailDto;
import de.greluc.krt.profit.basetool.frontend.identity.model.UserDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The P4K admin page over MVC (REQ-P4K-015, REQ-P4K-016): the run list with the admins' names, a
 * report filtered by kind and change type and paged, and a review case resolved or refused as
 * stale. Each list is a JSON proxy the page swaps in place.
 */
@SpringBootTest
class AdminP4kImportPageControllerMvcTest {

  private static final String BASE = "/api/v1/catalog/admin/import/p4k";
  private static final String WORKSPACE = "/admin/p4k-import/review-workspace";
  private static final UUID TARGET = UUID.fromString("00000000-0000-4000-8000-0000000000a4");
  private static final String REPORT = "/changes?kind={kind}&type={type}&page={page}&size={size}";
  private static final UUID RUN = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
  private static final UUID ADMIN = UUID.fromString("00000000-0000-4000-8000-0000000000a2");
  private static final UUID CASE = UUID.fromString("00000000-0000-4000-8000-0000000000a3");

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  private static <T> PageResponse<T> page(List<T> rows, int page, int totalPages) {
    return new PageResponse<>(rows, page, 25, rows.size(), totalPages, List.of());
  }

  private static P4kReviewCaseDto reviewCase(String resolution) {
    return new P4kReviewCaseDto(
        CASE.toString(),
        4,
        "SHIP",
        UUID.randomUUID().toString(),
        "SEEDED_NOT_CREATABLE",
        resolution == null ? "OPEN" : "REVIEWED",
        "ANVL_Hornet_F7C_Mk2_AI_Super",
        "Anvil F7C Hornet Mk II",
        null,
        "Hornet AI",
        null,
        null,
        RUN.toString(),
        "2026-11-01T10:00:00Z",
        resolution,
        resolution == null ? null : "2026-11-02T10:00:00Z");
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void thePage_carriesTheRunListTheReportTheReviewListAndTheMassGuardBox() throws Exception {
    String html =
        mockMvc
            .perform(get("/admin/p4k-import").locale(Locale.GERMAN))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .contains(
            "id=\"krt-p4k-runs\"",
            "id=\"krt-p4k-report-kind\"",
            "id=\"krt-p4k-report-type\"",
            "id=\"krt-p4k-report-pager\"",
            "id=\"krt-p4k-cases\"",
            "id=\"krt-p4k-review-kind\"",
            "id=\"krt-p4k-review-readiness\"",
            "id=\"krt-p4k-review-reason\"",
            "id=\"krt-p4k-review-detail\"",
            "id=\"krt-p4k-mark-anyway\"",
            "/js/catalogue/p4k-report.js",
            "Markierung trotzdem anwenden");
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void thePage_labelsTheStarmapCountsKindAndLinkReasons() throws Exception {
    String html =
        mockMvc
            .perform(get("/admin/p4k-import").locale(Locale.GERMAN))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .contains(
            "rowStarmapObjects: \"Sternenkarte\"",
            "'kind.STARMAP_OBJECT': \"Sternenkarten-Objekt\"",
            "'reason.STARMAP_LINK_AMBIGUOUS': \"UEX-Ort passt zu mehreren Sternenkarten-Objekten\"",
            "'reason.STARMAP_LINK_NOT_FOUND': \"UEX-Ort passt zu keinem Sternenkarten-Objekt\"");
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void theRunList_namesTheAdmins() throws Exception {
    P4kImportRunDto run =
        new P4kImportRunDto(
            RUN.toString(),
            "2026-11-01T10:00:00Z",
            "LIVE",
            "sc-alpha-4.4.0",
            "4.4.0",
            12913470L,
            null,
            null,
            ADMIN.toString(),
            List.of());
    when(backendApiClient.get(
            eq(BASE + "/runs?page={page}&size={size}"), anyTypeRef(), eq(0), eq(25)))
        .thenReturn(page(List.of(run), 0, 1));
    UserDto admin = Mockito.mock(UserDto.class);
    when(admin.displayName()).thenReturn("Greluc");
    when(backendApiClient.get("/api/v1/users/{id}", UserDto.class, ADMIN)).thenReturn(admin);

    mockMvc
        .perform(get("/admin/p4k-import/runs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.runs.content[0].id").value(RUN.toString()))
        .andExpect(jsonPath("$.adminNames['" + ADMIN + "']").value("Greluc"));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void theRunList_readsAnAdminWithoutAUserRecordOnce() throws Exception {
    P4kImportRunDto first =
        new P4kImportRunDto(
            RUN.toString(),
            "2026-11-01T10:00:00Z",
            "LIVE",
            "sc-alpha-4.4.0",
            "4.4.0",
            12913470L,
            null,
            null,
            ADMIN.toString(),
            List.of());
    P4kImportRunDto second =
        new P4kImportRunDto(
            UUID.randomUUID().toString(),
            "2026-11-02T10:00:00Z",
            "LIVE",
            "sc-alpha-4.4.0",
            "4.4.0",
            12913471L,
            null,
            null,
            ADMIN.toString(),
            List.of());
    when(backendApiClient.get(
            eq(BASE + "/runs?page={page}&size={size}"), anyTypeRef(), eq(0), eq(25)))
        .thenReturn(page(List.of(first, second), 0, 1));
    when(backendApiClient.get("/api/v1/users/{id}", UserDto.class, ADMIN)).thenReturn(null);

    mockMvc
        .perform(get("/admin/p4k-import/runs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.adminNames['" + ADMIN + "']").doesNotExist());

    Mockito.verify(backendApiClient, Mockito.times(1))
        .get("/api/v1/users/{id}", UserDto.class, ADMIN);
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void aRunsReport_relaysTheFiltersAndThePage() throws Exception {
    P4kImportChangeDto row =
        new P4kImportChangeDto(
            "ITEM",
            "CHANGED",
            UUID.randomUUID().toString(),
            "name_key",
            "old",
            "new",
            "X",
            "Y",
            null,
            false);
    when(backendApiClient.get(
            eq(BASE + "/runs/{id}" + REPORT),
            anyTypeRef(),
            eq(RUN),
            eq("ITEM"),
            eq("CHANGED"),
            eq(1),
            eq(25)))
        .thenReturn(page(List.of(row), 1, 3));

    mockMvc
        .perform(
            get("/admin/p4k-import/runs/" + RUN + "/changes")
                .param("kind", "ITEM")
                .param("type", "CHANGED")
                .param("page", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page").value(1))
        .andExpect(jsonPath("$.content[0].fieldName").value("name_key"));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void aResolution_returnsTheCase_andAStaleOneIsAConflict() throws Exception {
    String resolve = BASE + "/review-cases/{id}/resolve";
    when(backendApiClient.post(
            resolve,
            new P4kReviewResolveRequest("HIDE", null, null, 3L),
            P4kReviewCaseDto.class,
            CASE))
        .thenReturn(reviewCase("HIDE"));
    when(backendApiClient.post(
            resolve,
            new P4kReviewResolveRequest("HIDE", null, null, 2L),
            P4kReviewCaseDto.class,
            CASE))
        .thenThrow(new BackendServiceException("stale", null, 409));

    mockMvc
        .perform(
            post("/admin/p4k-import/review-cases/" + CASE + "/resolve")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"resolution\":\"HIDE\",\"version\":3}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.resolution").value("HIDE"));
    mockMvc
        .perform(
            post("/admin/p4k-import/review-cases/" + CASE + "/resolve")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"resolution\":\"HIDE\",\"version\":2}"))
        .andExpect(status().isConflict());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void theWorkspace_relaysGroupFiltersAndMemberPaging() throws Exception {
    UUID groupId = UUID.fromString("00000000-0000-4000-8000-0000000000a5");
    P4kReviewCaseDto representative = reviewCase(null);
    P4kReviewGroupDto group =
        new P4kReviewGroupDto(
            groupId.toString(),
            "SHIP",
            "SEEDED_NOT_CREATABLE",
            "Hornet AI",
            "CHECK_REQUIRED",
            "ships/anvil",
            101,
            true,
            representative);
    when(backendApiClient.get(
            eq(
                BASE
                    + "/review-workspace?kind={kind}&reason={reason}&readiness={readiness}&page={page}&size={size}"),
            anyTypeRef(),
            eq("SHIP"),
            eq("SEEDED_NOT_CREATABLE"),
            eq("CHECK_REQUIRED"),
            eq(1),
            eq(25)))
        .thenReturn(page(List.of(group), 1, 2));
    when(backendApiClient.get(
            eq(BASE + "/review-workspace/groups/{id}?page={page}&size={size}"),
            anyTypeRef(),
            eq(groupId),
            eq(1),
            eq(100)))
        .thenReturn(new PageResponse<>(List.of(representative), 1, 100, 101, 2, List.of()));

    mockMvc
        .perform(
            get(WORKSPACE)
                .param("kind", "SHIP")
                .param("reason", "SEEDED_NOT_CREATABLE")
                .param("readiness", "CHECK_REQUIRED")
                .param("page", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].count").value(101))
        .andExpect(jsonPath("$.content[0].contextPath").value("ships/anvil"))
        .andExpect(jsonPath("$.content[0].readiness").value("CHECK_REQUIRED"))
        .andExpect(jsonPath("$.content[0].batchable").value(true));
    mockMvc
        .perform(get(WORKSPACE + "/groups/{id}", groupId).param("page", "1").param("size", "100"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].id").value(CASE.toString()))
        .andExpect(jsonPath("$.size").value(100));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void theWorkspace_relaysCandidateEvidenceAndTheConfirmedVersions() throws Exception {
    P4kReviewCaseDto review = reviewCase(null);
    P4kReviewCandidate candidate =
        new P4kReviewCandidate(
            "ATTACH_P4K",
            TARGET.toString(),
            7L,
            null,
            "Hornet AI",
            "ANVL_Hornet_F7C_Mk2_AI_Super",
            null,
            review.guid(),
            List.of(216),
            List.of("EXACT_SCWIKI_UUID", "EXACT_CLASS"));
    when(backendApiClient.get(
            BASE + "/review-workspace/{caseId}", P4kReviewWorkspaceDetailDto.class, CASE))
        .thenReturn(
            new P4kReviewWorkspaceDetailDto(
                review,
                "ships/anvil/hornet",
                new P4kReviewOptions("ACTIONABLE", null, List.of(candidate))));
    when(backendApiClient.post(
            BASE + "/review-workspace/{caseId}/apply",
            new P4kReviewApplyRequest("ATTACH_P4K", TARGET, 7L, null, 4L),
            P4kReviewCaseDto.class,
            CASE))
        .thenReturn(reviewCase("APPLIED"));

    mockMvc
        .perform(get(WORKSPACE + "/{caseId}", CASE))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.recordPath").value("ships/anvil/hornet"))
        .andExpect(jsonPath("$.options.candidates[0].uexIds[0]").value(216))
        .andExpect(jsonPath("$.options.candidates[0].evidence[0]").value("EXACT_SCWIKI_UUID"));
    mockMvc
        .perform(
            post(WORKSPACE + "/{caseId}/apply", CASE)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"action":"ATTACH_P4K","targetId":"%s","targetVersion":7,"version":4}
                    """
                        .formatted(TARGET)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.resolution").value("APPLIED"));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void theWorkspace_relaysOnlyTheSelectedMembersOfABatch() throws Exception {
    UUID groupId = UUID.fromString("00000000-0000-4000-8000-0000000000a5");
    mockMvc
        .perform(
            post(WORKSPACE + "/groups/{id}/resolve", groupId)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"resolution":"KEEP","members":[{"id":"%s","version":4}]}
                    """
                        .formatted(CASE)))
        .andExpect(status().isNoContent());
    verify(backendApiClient)
        .post(
            BASE + "/review-workspace/groups/{id}/resolve",
            new P4kReviewBatchRequest("KEEP", List.of(new P4kReviewBatchMember(CASE, 4L))),
            Void.class,
            groupId);
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void aMember_isRefused() throws Exception {
    mockMvc.perform(get("/admin/p4k-import/review-cases")).andExpect(status().isForbidden());
    mockMvc.perform(get(WORKSPACE)).andExpect(status().isForbidden());
    mockMvc.perform(get(WORKSPACE + "/{caseId}", CASE)).andExpect(status().isForbidden());
    mockMvc
        .perform(
            post(WORKSPACE + "/{caseId}/apply", CASE)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"action":"ATTACH_P4K","targetId":"%s","targetVersion":7,"version":4}
                    """
                        .formatted(TARGET)))
        .andExpect(status().isForbidden());
  }
}
