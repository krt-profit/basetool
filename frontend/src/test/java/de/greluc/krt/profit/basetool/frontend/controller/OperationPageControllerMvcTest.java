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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.PayoutPreference;
import de.greluc.krt.profit.basetool.frontend.model.dto.MissionListDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OperationDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OperationFinanceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OperationPayoutDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OperationPayoutSummaryDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

  // ── /operations (list) ──────────────────────────────────────────────────

  @Test
  @WithMockUser(roles = "OFFICER")
  void operationsList_rendersOperationStatusViaI18n_inGerman() throws Exception {
    // Backend returns one operation with the raw enum status. The previous
    // bug rendered this verbatim in the table cell — we now expect the
    // German translation "GEPLANT".
    OperationDto op =
        new OperationDto(
            UUID.randomUUID(), "Op Alpha", "First op", "PLANNED", null, 0L, null, null, null);
    PageResponse<OperationDto> page =
        new PageResponse<>(List.of(op), 0, 20, 1L, 1, List.of("createdAt,desc"));
    when(backendApiClient.get(startsWith("/api/v1/operations/search?"), anyTypeRef(), anyBoolean()))
        .thenReturn(page);

    mockMvc
        .perform(get("/operations").locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("GEPLANT")))
        // Status renders through the design-system .status-pill component with a
        // status-specific modifier class derived from the (lower-cased) enum.
        .andExpect(content().string(containsString("status-pill")))
        .andExpect(content().string(containsString("status-planned")))
        // The raw enum name must NOT survive into the table cell.
        // (The string can still appear in the <option value="PLANNED">
        // attribute of the create modal, so we assert on the visible
        // text by looking for the wrapping <td> pattern.)
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
    when(backendApiClient.get(startsWith("/api/v1/operations/search?"), anyTypeRef(), anyBoolean()))
        .thenReturn(page);

    // English locale resolves to messages_en.properties → "ACTIVE".
    // Coincidentally the same casing as the backend enum, so this test
    // can't tell raw-vs-translated apart for English. We still run it
    // to make sure the lookup does not throw for the EN bundle.
    mockMvc
        .perform(get("/operations").locale(Locale.ENGLISH))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("ACTIVE")))
        // Active operations carry the success-hued status-pill modifier.
        .andExpect(content().string(containsString("status-active")));
  }

  // ── /operations/{id} (detail) ───────────────────────────────────────────

  // ── /operations/{id} (detail) — canEdit-driven form visibility ──────────

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void operationDetail_readOnlyUser_seesDisabledFormAndNoSaveButton() throws Exception {
    UUID opId = UUID.randomUUID();
    stubDetailEndpoints(
        opId, new OperationDto(opId, "Op Read", "ro", "PLANNED", null, 0L, null, null, null));

    mockMvc
        .perform(get("/operations/" + opId).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        // Form is rendered but inputs are disabled for non-editors.
        .andExpect(content().string(containsString("id=\"operation-form\"")))
        .andExpect(content().string(containsString("id=\"op-name\"")))
        .andExpect(content().string(containsString("disabled")))
        // No submit button anywhere — Save is gated on canEdit.
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
        // The submit button references the form by id — its presence is
        // the canonical signal that canEdit was true on the model.
        .andExpect(content().string(containsString("form=\"operation-form\"")));
  }

  private void stubDetailEndpoints(UUID opId, OperationDto operation) {
    when(backendApiClient.get(
            eq("/api/v1/operations/" + opId), eq(OperationDto.class), anyBoolean()))
        .thenReturn(operation);
    when(backendApiClient.get(
            contains("/api/v1/missions/search?operationId=" + opId), anyTypeRef(), anyBoolean()))
        .thenReturn(new PageResponse<>(List.<MissionListDto>of(), 0, 10, 0L, 0, List.of()));
    when(backendApiClient.get(
            eq("/api/v1/operations/" + opId + "/finances"),
            eq(OperationFinanceDto.class),
            anyBoolean()))
        .thenReturn(new OperationFinanceDto(opId, BigDecimal.ZERO, List.of()));
    when(backendApiClient.get(
            eq("/api/v1/operations/" + opId + "/payouts"),
            eq(OperationPayoutSummaryDto.class),
            anyBoolean()))
        .thenReturn(new OperationPayoutSummaryDto(BigDecimal.ZERO, List.of()));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void operationDetail_translatesBothOperationAndMissionStatus() throws Exception {
    UUID opId = UUID.randomUUID();

    // Operation: status COMPLETED → German "ABGESCHLOSSEN".
    OperationDto operation =
        new OperationDto(opId, "Completed Op", "", "COMPLETED", null, 0L, null, null, null);
    when(backendApiClient.get(
            eq("/api/v1/operations/" + opId), eq(OperationDto.class), anyBoolean()))
        .thenReturn(operation);

    // Mission inside operation: status CANCELLED (double L on Mission!) →
    // German "ABGEBROCHEN". This is the second i18n bug-fix-point — the
    // nested missions table on the operation-detail page used to dump the
    // raw enum value.
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
            0L);
    PageResponse<MissionListDto> missionsPage =
        new PageResponse<>(List.of(mission), 0, 10, 1L, 1, List.of("plannedStartTime,asc"));
    when(backendApiClient.get(
            contains("/api/v1/missions/search?operationId=" + opId), anyTypeRef(), anyBoolean()))
        .thenReturn(missionsPage);

    // Empty finance/payout stubs so the page renders without NPE.
    when(backendApiClient.get(
            eq("/api/v1/operations/" + opId + "/finances"),
            eq(OperationFinanceDto.class),
            anyBoolean()))
        .thenReturn(new OperationFinanceDto(opId, BigDecimal.ZERO, List.of()));
    when(backendApiClient.get(
            eq("/api/v1/operations/" + opId + "/payouts"),
            eq(OperationPayoutSummaryDto.class),
            anyBoolean()))
        .thenReturn(new OperationPayoutSummaryDto(BigDecimal.ZERO, List.of()));

    mockMvc
        .perform(get("/operations/" + opId).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("ABGESCHLOSSEN")))
        .andExpect(content().string(containsString("ABGEBROCHEN")))
        // Raw enum values must not appear as cell content. The
        // dropdown <option value="..."> attributes still legitimately
        // carry the enum names, so we look for the closing-tag form
        // ">VALUE<" which only the visible text matches.
        .andExpect(content().string(not(containsString(">COMPLETED<"))))
        .andExpect(content().string(not(containsString(">CANCELLED<"))))
        // The nested missions table renders status through the .status-pill
        // component; the cancelled mission resolves to the danger-hued modifier.
        .andExpect(content().string(containsString("status-cancelled")));
  }

  // covers REQ-FE-002 — an AJAX missions-pager swap (fragment=missions) renders only the embedded
  // missions sub-table fragment: the mission row + page-nav are present, but the swap-target
  // wrapper and the other detail columns (outside the fragment) are not. The finance/payout
  // endpoints are intentionally NOT stubbed — the fragment path must not call them.
  @Test
  @WithMockUser(roles = "OFFICER")
  void operationDetail_fragmentMissions_rendersOnlyMissionsFragment() throws Exception {
    UUID opId = UUID.randomUUID();
    OperationDto operation =
        new OperationDto(opId, "Op", "", "PLANNED", null, 0L, null, null, null);
    when(backendApiClient.get(
            eq("/api/v1/operations/" + opId), eq(OperationDto.class), anyBoolean()))
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
            0L);
    // Two pages so the embedded pager renders.
    when(backendApiClient.get(
            contains("/api/v1/missions/search?operationId=" + opId), anyTypeRef(), anyBoolean()))
        .thenReturn(
            new PageResponse<>(List.of(mission), 0, 10, 15L, 2, List.of("plannedStartTime,asc")));

    mockMvc
        .perform(get("/operations/" + opId).param("fragment", "missions").locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Frag Mission")))
        .andExpect(content().string(containsString("class=\"pagination\"")))
        // The page-nav links page the embedded table in place against /operations/{id}.
        .andExpect(content().string(containsString("/operations/" + opId + "?page=1")))
        // Wrapper div and sibling detail columns live outside the fragment.
        .andExpect(content().string(not(containsString("id=\"op-missions-results\""))))
        .andExpect(content().string(not(containsString("id=\"col-payout\""))));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void operationDetail_rendersDonationTotals_whenParticipantsDonate() throws Exception {
    UUID opId = UUID.randomUUID();
    stubDetailEndpoints(
        opId, new OperationDto(opId, "Op", "", "COMPLETED", null, 0L, null, null, Boolean.FALSE));
    // Override the (zero) payout stub with a donor row + an operation-wide donation total.
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
            eq("/api/v1/operations/" + opId + "/payouts"),
            eq(OperationPayoutSummaryDto.class),
            anyBoolean()))
        .thenReturn(new OperationPayoutSummaryDto(new BigDecimal("350.00"), List.of(donor)));

    mockMvc
        .perform(get("/operations/" + opId).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        // Central donation total surfaces in both the payout panel and the finance panel.
        .andExpect(content().string(containsString("Spenden gesamt")))
        .andExpect(content().string(containsString("Davon gespendet")))
        // Per-donor donated amount sublabel renders in the payout row.
        .andExpect(content().string(containsString("gespendet")))
        .andExpect(content().string(containsString("350")));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void operationDetail_rendersPreliminaryWarning_whenBackendReportsUnfinishedMissions()
      throws Exception {
    UUID opId = UUID.randomUUID();
    // Backend signals at least one mission still lacks actualStartTime/EndTime.
    stubDetailEndpoints(
        opId,
        new OperationDto(opId, "Ongoing Op", "", "ACTIVE", null, 0L, null, null, Boolean.TRUE));

    mockMvc
        .perform(get("/operations/" + opId).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("alert-warning")))
        // Title from the i18n bundle — pin the visible text so a key rename
        // breaks this test instead of silently dropping the warning.
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
    // payoutPreliminary == null is treated as "unknown" — banner stays hidden.
    stubDetailEndpoints(
        opId, new OperationDto(opId, "Unknown Op", "", "PLANNED", null, 0L, null, null, null));

    mockMvc
        .perform(get("/operations/" + opId).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("alert-warning"))));
  }

  // ── /operations/{id}/payouts/paid-out — asymmetric authorization ────────
  // Plain mission managers can SET paidOut=true, but only ADMIN/OFFICER may
  // clear it back to false. The SpEL guard returns 403 for a plain mission
  // manager attempting paidOut=false.

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
            eq(OperationPayoutDto.class),
            anyBoolean()))
        .thenReturn(
            new OperationPayoutDto(
                participantId.toString(),
                "Alice",
                100.0,
                PayoutPreference.PAYOUT,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                false,
                null,
                null));

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
            eq(OperationPayoutDto.class),
            anyBoolean()))
        .thenReturn(
            new OperationPayoutDto(
                participantId.toString(),
                "Alice",
                100.0,
                PayoutPreference.PAYOUT,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                true,
                null,
                null));

    mockMvc
        .perform(
            post("/operations/" + opId + "/payouts/paid-out")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"participantKey\":\"" + participantId + "\",\"paidOut\":true}"))
        .andExpect(status().isOk());
  }

  // ── /operations/{id} — canUnsetPaidOut model attribute ─────────────────

  @Test
  @WithMockUser(roles = "MISSION_MANAGER")
  void operationDetail_missionManager_rendersCanUnsetPaidOutFalse() throws Exception {
    UUID opId = UUID.randomUUID();
    stubDetailEndpoints(
        opId, new OperationDto(opId, "Op", "", "ACTIVE", null, 0L, null, null, null));

    mockMvc
        .perform(get("/operations/" + opId).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        // Plain mission managers don't get the unset capability — the panel
        // exposes data-can-unset-paid-out=false so the JS lockout activates
        // after a successful paidOut=true transition.
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
