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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.model.PayoutPreference;
import de.greluc.krt.profit.basetool.frontend.model.dto.FinanceType;
import de.greluc.krt.profit.basetool.frontend.model.dto.MissionFinanceEntryDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MissionFinanceSummaryDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MissionListDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OperationDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OperationFinanceSummaryDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OperationPayoutDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OperationPayoutStatusDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OperationPayoutSummaryDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Verifies that the operations index and operation-detail templates render status values through
 * the i18n bundle instead of leaking the raw backend enum strings. The i18n bug was: the
 * create/edit dropdowns went through {@code operation.status.planned} etc., but the displayed
 * status (in the table, the detail box, and the embedded missions table) was rendered as {@code
 * th:text="${op.status}"}, dumping the raw enum name into the page. These tests pin the fix: send
 * an Operation/Mission with status {@code PLANNED}/{@code COMPLETED}/{@code CANCELLED} through the
 * page and assert the rendered HTML contains the German translation, not the raw uppercase enum
 * value.
 */
@SpringBootTest
class OperationPageControllerMvcTest {

  /** German {@code operation.section.refresh.error} text, as both DE bundles spell it. */
  private static final String SECTION_REFRESH_ERROR_DE =
      "Der Abschnitt konnte nicht aktualisiert werden.";

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void operationsList_rendersOperationStatusViaI18n_inGerman() throws Exception {
    OperationDto op =
        new OperationDto(
            UUID.randomUUID(), "Op Alpha", "First op", "PLANNED", null, 0L, null, null, null);
    PageResponse<OperationDto> page =
        new PageResponse<>(List.of(op), 0, 20, 1L, 1, List.of("createdAt,desc"));
    when(backendApiClient.get(startsWith("/api/v1/operations/search?"), anyTypeRef()))
        .thenReturn(page);

    mockMvc
        .perform(get("/operations").locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("GEPLANT")))
        .andExpect(content().string(containsString("status-pill")))
        .andExpect(content().string(containsString("status-planned")))
        .andExpect(content().string(not(containsString(">PLANNED<"))));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void operationsList_rendersOperationStatusViaI18n_inEnglish() throws Exception {
    OperationDto op =
        new OperationDto(
            UUID.randomUUID(), "Op Alpha", "First op", "ACTIVE", null, 0L, null, null, null);
    PageResponse<OperationDto> page =
        new PageResponse<>(List.of(op), 0, 20, 1L, 1, List.of("createdAt,desc"));
    when(backendApiClient.get(startsWith("/api/v1/operations/search?"), anyTypeRef()))
        .thenReturn(page);

    mockMvc
        .perform(get("/operations").locale(Locale.ENGLISH))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("ACTIVE")))
        .andExpect(content().string(containsString("status-active")));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void operationsList_passesMultiWordSearchAsUriVariable() throws Exception {
    when(backendApiClient.get(startsWith("/api/v1/operations/search?"), anyTypeRef(), any()))
        .thenReturn(new PageResponse<>(List.<OperationDto>of(), 0, 20, 0L, 0, List.of()));

    mockMvc
        .perform(get("/operations").param("search", "Widget Alpha").param("fragment", "results"))
        .andExpect(status().isOk());

    ArgumentCaptor<String> uriCaptor = ArgumentCaptor.captor();
    ArgumentCaptor<Object> termCaptor = ArgumentCaptor.captor();
    verify(backendApiClient).get(uriCaptor.capture(), anyTypeRef(), termCaptor.capture());
    assertTrue(uriCaptor.getValue().contains("query={query}"), uriCaptor.getValue());
    assertEquals("Widget Alpha", termCaptor.getValue());
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void operationsList_passesUmlautSearchAsUriVariable_notFormEncoded() throws Exception {
    when(backendApiClient.get(startsWith("/api/v1/operations/search?"), anyTypeRef(), any()))
        .thenReturn(new PageResponse<>(List.<OperationDto>of(), 0, 20, 0L, 0, List.of()));

    String term = "Müller Größe";
    mockMvc
        .perform(get("/operations").param("search", term).param("fragment", "results"))
        .andExpect(status().isOk());

    ArgumentCaptor<String> uriCaptor = ArgumentCaptor.captor();
    ArgumentCaptor<Object> termCaptor = ArgumentCaptor.captor();
    verify(backendApiClient).get(uriCaptor.capture(), anyTypeRef(), termCaptor.capture());
    assertTrue(uriCaptor.getValue().contains("query={query}"), uriCaptor.getValue());
    assertEquals(term, termCaptor.getValue());
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void operationDetail_readOnlyUser_seesDisabledFormAndNoSaveButton() throws Exception {
    UUID opId = UUID.randomUUID();
    stubDetailEndpoints(
        opId, new OperationDto(opId, "Op Read", "ro", "PLANNED", null, 0L, null, null, null));

    mockMvc
        .perform(get("/operations/" + opId).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("id=\"operation-form\"")))
        .andExpect(content().string(containsString("id=\"op-name\"")))
        .andExpect(content().string(containsString("disabled")))
        .andExpect(content().string(not(containsString("form=\"operation-form\""))));
  }

  @Test
  @WithMockUser(roles = "MISSION_MANAGER")
  void operationDetail_missionManager_seesEnabledFormAndSaveButton() throws Exception {
    UUID opId = UUID.randomUUID();
    stubDetailEndpoints(
        opId, new OperationDto(opId, "Op Edit", "rw", "PLANNED", null, 0L, null, null, null));

    mockMvc
        .perform(get("/operations/" + opId).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("id=\"operation-form\"")))
        .andExpect(content().string(containsString("form=\"operation-form\"")));
  }

  private void stubDetailEndpoints(UUID opId, OperationDto operation) {
    when(backendApiClient.get(eq("/api/v1/operations/" + opId), eq(OperationDto.class)))
        .thenReturn(operation);
    when(backendApiClient.get(
            contains("/api/v1/missions/search?operationId=" + opId), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.<MissionListDto>of(), 0, 10, 0L, 0, List.of()));
    when(backendApiClient.get(
            eq("/api/v1/operations/" + opId + "/finance-summary"),
            eq(OperationFinanceSummaryDto.class)))
        .thenReturn(new OperationFinanceSummaryDto(opId, BigDecimal.ZERO, List.of(), false));
    when(backendApiClient.get(
            eq("/api/v1/operations/" + opId + "/payouts"), eq(OperationPayoutSummaryDto.class)))
        .thenReturn(new OperationPayoutSummaryDto(BigDecimal.ZERO, List.of()));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void operationDetail_translatesBothOperationAndMissionStatus() throws Exception {
    UUID opId = UUID.randomUUID();

    OperationDto operation =
        new OperationDto(opId, "Completed Op", "", "COMPLETED", null, 0L, null, null, null);
    when(backendApiClient.get(eq("/api/v1/operations/" + opId), eq(OperationDto.class)))
        .thenReturn(operation);

    MissionListDto mission =
        new MissionListDto(
            UUID.randomUUID(),
            "Aborted Mission",
            null,
            null,
            "CANCELLED",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            0L,
            0L);
    PageResponse<MissionListDto> missionsPage =
        new PageResponse<>(List.of(mission), 0, 10, 1L, 1, List.of("plannedStartTime,asc"));
    when(backendApiClient.get(
            contains("/api/v1/missions/search?operationId=" + opId), anyTypeRef()))
        .thenReturn(missionsPage);

    when(backendApiClient.get(
            eq("/api/v1/operations/" + opId + "/finance-summary"),
            eq(OperationFinanceSummaryDto.class)))
        .thenReturn(new OperationFinanceSummaryDto(opId, BigDecimal.ZERO, List.of(), false));
    when(backendApiClient.get(
            eq("/api/v1/operations/" + opId + "/payouts"), eq(OperationPayoutSummaryDto.class)))
        .thenReturn(new OperationPayoutSummaryDto(BigDecimal.ZERO, List.of()));

    mockMvc
        .perform(get("/operations/" + opId).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("ABGESCHLOSSEN")))
        .andExpect(content().string(containsString("ABGEBROCHEN")))
        .andExpect(content().string(not(containsString(">COMPLETED<"))))
        .andExpect(content().string(not(containsString(">CANCELLED<"))))
        .andExpect(content().string(containsString("status-cancelled")));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void operationDetail_fragmentMissions_rendersOnlyMissionsFragment() throws Exception {
    UUID opId = UUID.randomUUID();
    OperationDto operation =
        new OperationDto(opId, "Op", "", "PLANNED", null, 0L, null, null, null);
    when(backendApiClient.get(eq("/api/v1/operations/" + opId), eq(OperationDto.class)))
        .thenReturn(operation);

    MissionListDto mission =
        new MissionListDto(
            UUID.randomUUID(),
            "Frag Mission",
            null,
            null,
            "PLANNED",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            0L,
            0L);
    when(backendApiClient.get(
            contains("/api/v1/missions/search?operationId=" + opId), anyTypeRef()))
        .thenReturn(
            new PageResponse<>(List.of(mission), 0, 10, 15L, 2, List.of("plannedStartTime,asc")));

    mockMvc
        .perform(get("/operations/" + opId).param("fragment", "missions").locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Frag Mission")))
        .andExpect(content().string(containsString("class=\"pagination\"")))
        .andExpect(content().string(containsString("/operations/" + opId + "?page=1")))
        .andExpect(content().string(not(containsString("id=\"op-missions-results\""))))
        .andExpect(content().string(not(containsString("id=\"col-payout\""))));

    verify(backendApiClient, never())
        .get(
            eq("/api/v1/operations/" + opId + "/finance-summary"),
            eq(OperationFinanceSummaryDto.class));
    verify(backendApiClient, never())
        .get(eq("/api/v1/operations/" + opId + "/payouts"), eq(OperationPayoutSummaryDto.class));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void operationDetail_fragmentOverview_rendersOverviewSection() throws Exception {
    UUID opId = UUID.randomUUID();
    stubDetailEndpoints(
        opId, new OperationDto(opId, "Op Frag", "", "PLANNED", null, 0L, null, null, null));

    mockMvc
        .perform(get("/operations/" + opId).param("fragment", "overview").locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andExpect(view().name("operation-detail :: overviewSection"))
        .andExpect(content().string(containsString("id=\"operation-head-meta\"")))
        .andExpect(content().string(not(containsString("id=\"operation-head-sticky\""))));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void operationDetail_fragmentPayout_rendersPayoutSection_andSkipsFinanceAndMissions()
      throws Exception {
    UUID opId = UUID.randomUUID();
    when(backendApiClient.get(eq("/api/v1/operations/" + opId), eq(OperationDto.class)))
        .thenReturn(new OperationDto(opId, "Op", "", "PLANNED", null, 0L, null, null, null));
    when(backendApiClient.get(
            eq("/api/v1/operations/" + opId + "/payouts"), eq(OperationPayoutSummaryDto.class)))
        .thenReturn(new OperationPayoutSummaryDto(BigDecimal.ZERO, List.of()));

    mockMvc
        .perform(get("/operations/" + opId).param("fragment", "payout").locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andExpect(view().name("operation-detail :: payoutSection"))
        .andExpect(content().string(not(containsString("id=\"pane-op-payout\""))));

    verify(backendApiClient, never())
        .get(
            eq("/api/v1/operations/" + opId + "/finance-summary"),
            eq(OperationFinanceSummaryDto.class));
    verify(backendApiClient, never())
        .get(contains("/api/v1/missions/search?operationId=" + opId), anyTypeRef());
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void operationDetail_fragmentFinance_rendersFinanceSection_andSkipsOperationRead()
      throws Exception {
    UUID opId = UUID.randomUUID();
    when(backendApiClient.get(
            eq("/api/v1/operations/" + opId + "/finance-summary"),
            eq(OperationFinanceSummaryDto.class)))
        .thenReturn(new OperationFinanceSummaryDto(opId, BigDecimal.ZERO, List.of(), false));
    when(backendApiClient.get(
            eq("/api/v1/operations/" + opId + "/payouts"), eq(OperationPayoutSummaryDto.class)))
        .thenReturn(new OperationPayoutSummaryDto(BigDecimal.ZERO, List.of()));
    when(backendApiClient.get(
            contains("/api/v1/missions/search?operationId=" + opId), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.<MissionListDto>of(), 0, 10, 0L, 0, List.of()));

    mockMvc
        .perform(get("/operations/" + opId).param("fragment", "finance").locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andExpect(view().name("operation-detail :: financeSection"))
        .andExpect(content().string(not(containsString("id=\"pane-op-fin\""))));

    verify(backendApiClient, never()).get(eq("/api/v1/operations/" + opId), eq(OperationDto.class));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void operationDetail_fragmentUnknown_rendersFragmentError() throws Exception {
    UUID opId = UUID.randomUUID();

    mockMvc
        .perform(get("/operations/" + opId).param("fragment", "bogus").locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andExpect(view().name("operation-detail :: fragmentError"));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void operationDetail_fragmentBackendFailure_rendersFragmentError() throws Exception {
    UUID opId = UUID.randomUUID();
    when(backendApiClient.get(eq("/api/v1/operations/" + opId), eq(OperationDto.class)))
        .thenThrow(new RuntimeException("backend down"));

    mockMvc
        .perform(get("/operations/" + opId).param("fragment", "payout").locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andExpect(view().name("operation-detail :: fragmentError"))
        .andExpect(content().string(containsString("Abschnitt konnte nicht aktualisiert werden")));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void operationDetail_fullPage_doesNotRenderTheSectionRefreshError() throws Exception {
    UUID opId = UUID.randomUUID();
    stubDetailEndpoints(
        opId, new OperationDto(opId, "Op Inert", "", "PLANNED", null, 0L, null, null, null));

    String body =
        mockMvc
            .perform(get("/operations/" + opId).locale(Locale.GERMAN))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    String renderable =
        body.replaceAll("(?s)<template[^>]*>.*?</template>", "")
            .replaceAll("(?s)<script[^>]*>.*?</script>", "");

    assertFalse(
        renderable.contains(SECTION_REFRESH_ERROR_DE),
        "the fragmentError paragraph must not render on the full operation-detail page");
    assertTrue(
        body.contains(SECTION_REFRESH_ERROR_DE),
        "the fragmentError paragraph must still be present (inert) in the page source");
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void operationDetail_fragmentUnknown_stillRendersTheSectionRefreshError() throws Exception {
    UUID opId = UUID.randomUUID();

    mockMvc
        .perform(get("/operations/" + opId).param("fragment", "bogus").locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andExpect(view().name("operation-detail :: fragmentError"))
        .andExpect(content().string(containsString(SECTION_REFRESH_ERROR_DE)))
        .andExpect(content().string(not(containsString("<template"))))
        .andExpect(content().string(not(containsString("id=\"operation-form\""))));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void operationMissionFinance_rendersEntryBreakdownFragment() throws Exception {
    UUID opId = UUID.randomUUID();
    UUID missionId = UUID.randomUUID();
    MissionFinanceEntryDto entry =
        new MissionFinanceEntryDto(
            UUID.randomUUID(),
            missionId,
            null,
            "Wave5DetailNote",
            FinanceType.INCOME,
            new BigDecimal("500"),
            0L);
    when(backendApiClient.get(
            eq("/api/v1/operations/" + opId + "/finances/" + missionId),
            eq(MissionFinanceSummaryDto.class)))
        .thenReturn(
            new MissionFinanceSummaryDto(
                missionId, "Mission A", new BigDecimal("500"), List.of(entry), List.of()));

    mockMvc
        .perform(get("/operations/" + opId + "/finance/" + missionId).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Wave5DetailNote")))
        .andExpect(content().string(not(containsString("id=\"pane-op-fin\""))));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void operationMissionFinance_backendFailure_rendersErrorFragment() throws Exception {
    UUID opId = UUID.randomUUID();
    UUID missionId = UUID.randomUUID();
    when(backendApiClient.get(
            eq("/api/v1/operations/" + opId + "/finances/" + missionId),
            eq(MissionFinanceSummaryDto.class)))
        .thenThrow(new RuntimeException("backend down"));

    mockMvc
        .perform(get("/operations/" + opId + "/finance/" + missionId).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Details konnten nicht geladen werden")));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void operationDetail_rendersDonationTotals_whenParticipantsDonate() throws Exception {
    UUID opId = UUID.randomUUID();
    stubDetailEndpoints(
        opId, new OperationDto(opId, "Op", "", "COMPLETED", null, 0L, null, null, Boolean.FALSE));
    OperationPayoutDto donor =
        new OperationPayoutDto(
            UUID.randomUUID().toString(),
            "Donor Dan",
            50.0,
            PayoutPreference.DONATE,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            new BigDecimal("350.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            false,
            null,
            null);
    when(backendApiClient.get(
            eq("/api/v1/operations/" + opId + "/payouts"), eq(OperationPayoutSummaryDto.class)))
        .thenReturn(new OperationPayoutSummaryDto(new BigDecimal("350.00"), List.of(donor)));

    mockMvc
        .perform(get("/operations/" + opId).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Spenden gesamt")))
        .andExpect(content().string(containsString("Davon gespendet")))
        .andExpect(content().string(containsString("gespendet")))
        .andExpect(content().string(containsString("350")));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void operationDetail_rendersPreliminaryWarning_whenBackendReportsUnfinishedMissions()
      throws Exception {
    UUID opId = UUID.randomUUID();
    stubDetailEndpoints(
        opId,
        new OperationDto(opId, "Ongoing Op", "", "ACTIVE", null, 0L, null, null, Boolean.TRUE));

    mockMvc
        .perform(get("/operations/" + opId).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("alert-warning")))
        .andExpect(content().string(containsString("Vorläufige Werte")));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void operationDetail_hidesPreliminaryWarning_whenBackendReportsAllMissionsFinished()
      throws Exception {
    UUID opId = UUID.randomUUID();
    stubDetailEndpoints(
        opId,
        new OperationDto(opId, "Closed Op", "", "COMPLETED", null, 0L, null, null, Boolean.FALSE));

    mockMvc
        .perform(get("/operations/" + opId).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("alert-warning"))));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void operationDetail_hidesPreliminaryWarning_whenBackendOmitsFlag() throws Exception {
    UUID opId = UUID.randomUUID();
    stubDetailEndpoints(
        opId, new OperationDto(opId, "Unknown Op", "", "PLANNED", null, 0L, null, null, null));

    mockMvc
        .perform(get("/operations/" + opId).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("alert-warning"))));
  }

  @Test
  @WithMockUser(roles = "MISSION_MANAGER")
  void updatePayoutStatus_missionManager_isForbiddenFromSetting_paidOutFalse() throws Exception {
    UUID opId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();

    mockMvc
        .perform(
            post("/operations/" + opId + "/payouts/paid-out")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"participantKey\":\"" + participantId + "\",\"paidOut\":false}"))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void updatePayoutStatus_officer_canClear_paidOutFalse() throws Exception {
    UUID opId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();

    when(backendApiClient.put(
            eq("/api/v1/operations/" + opId + "/payouts/paid-out"),
            any(),
            eq(OperationPayoutStatusDto.class)))
        .thenReturn(new OperationPayoutStatusDto(participantId.toString(), false, null, null));

    mockMvc
        .perform(
            post("/operations/" + opId + "/payouts/paid-out")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"participantKey\":\"" + participantId + "\",\"paidOut\":false}"))
        .andExpect(status().isOk());
  }

  @Test
  @WithMockUser(roles = "MISSION_MANAGER")
  void updatePayoutStatus_missionManager_canSet_paidOutTrue() throws Exception {
    UUID opId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();

    when(backendApiClient.put(
            eq("/api/v1/operations/" + opId + "/payouts/paid-out"),
            any(),
            eq(OperationPayoutStatusDto.class)))
        .thenReturn(new OperationPayoutStatusDto(participantId.toString(), true, null, null));

    mockMvc
        .perform(
            post("/operations/" + opId + "/payouts/paid-out")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"participantKey\":\"" + participantId + "\",\"paidOut\":true}"))
        .andExpect(status().isOk());
  }

  @Test
  @WithMockUser(roles = "MISSION_MANAGER")
  void updatePayoutStatus_mapsBackend409ToConflict_notServerError() throws Exception {
    UUID opId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();

    when(backendApiClient.put(
            eq("/api/v1/operations/" + opId + "/payouts/paid-out"),
            any(),
            eq(OperationPayoutStatusDto.class)))
        .thenThrow(new BackendServiceException("payout toggle race", null, 409));

    mockMvc
        .perform(
            post("/operations/" + opId + "/payouts/paid-out")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"participantKey\":\"" + participantId + "\",\"paidOut\":true}"))
        .andExpect(status().isConflict());
  }

  @Test
  @WithMockUser(roles = "MISSION_MANAGER")
  void updateOperation_classicForm_maps409ToOptimisticLockingFlash() throws Exception {
    UUID opId = UUID.randomUUID();
    when(backendApiClient.put(eq("/api/v1/operations/" + opId), any(), eq(Void.class)))
        .thenThrow(new BackendServiceException("stale operation version", null, 409));

    mockMvc
        .perform(
            post("/operations/" + opId + "/update")
                .with(csrf())
                .param("name", "Op")
                .param("status", "ACTIVE")
                .param("version", "3"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/operations"))
        .andExpect(flash().attribute("errorMessage", "error.optimistic.locking"));
  }

  @Test
  @WithMockUser(roles = "MISSION_MANAGER")
  void updateOperation_classicForm_mapsOtherErrorsToGenericFlash() throws Exception {
    UUID opId = UUID.randomUUID();
    when(backendApiClient.put(eq("/api/v1/operations/" + opId), any(), eq(Void.class)))
        .thenThrow(new BackendServiceException("backend down", null, 500));

    mockMvc
        .perform(
            post("/operations/" + opId + "/update")
                .with(csrf())
                .param("name", "Op")
                .param("status", "ACTIVE")
                .param("version", "3"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/operations"))
        .andExpect(flash().attribute("errorMessage", "operation.update.error"));
  }

  @Test
  @WithMockUser(roles = "MISSION_MANAGER")
  void operationDetail_missionManager_rendersCanUnsetPaidOutFalse() throws Exception {
    UUID opId = UUID.randomUUID();
    stubDetailEndpoints(
        opId, new OperationDto(opId, "Op", "", "ACTIVE", null, 0L, null, null, null));

    mockMvc
        .perform(get("/operations/" + opId).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-can-unset-paid-out=\"false\"")));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void operationDetail_officer_rendersCanUnsetPaidOutTrue() throws Exception {
    UUID opId = UUID.randomUUID();
    stubDetailEndpoints(
        opId, new OperationDto(opId, "Op", "", "ACTIVE", null, 0L, null, null, null));

    mockMvc
        .perform(get("/operations/" + opId).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-can-unset-paid-out=\"true\"")));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void operationDetail_admin_rendersCanUnsetPaidOutTrue() throws Exception {
    UUID opId = UUID.randomUUID();
    stubDetailEndpoints(
        opId, new OperationDto(opId, "Op", "", "ACTIVE", null, 0L, null, null, null));

    mockMvc
        .perform(get("/operations/" + opId).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-can-unset-paid-out=\"true\"")));
  }
}
