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

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyClass;
import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.model.dto.MissionDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
class MissionPageControllerMvcTest {

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
  void createMission_WithEmptyDescription_ShouldSucceed() throws Exception {
    // Prepare Mocks
    when(backendApiClient.post(any(String.class), any(), Mockito.eq(Void.class))).thenReturn(null);

    // Perform Request
    mockMvc
        .perform(
            post("/missions")
                .param("name", "Test Mission")
                .param("description", "") // Empty description triggers StringTrimmerEditor -> null
                .param("status", "PLANNED")
                .param("plannedStartTime", "2026-02-10T10:00")
                .param("plannedEndTime", "2026-02-10T12:00")
                .with(csrf()))
        .andExpect(status().is3xxRedirection())
        .andDo(org.springframework.test.web.servlet.result.MockMvcResultHandlers.print())
        .andExpect(redirectedUrl("/missions"));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void missionDetail_ShouldRenderWithoutErrors() throws Exception {
    UUID missionId = UUID.randomUUID();

    // The MissionDto below intentionally carries empty sub-collections (units, participants,
    // job orders, finance entries, ...). The test focuses on the mission-detail TEMPLATE
    // contract — panel structure, accessible toggles, no horizontal-scroll markers — not on
    // sub-aggregate rendering, so a minimal mission is sufficient. Earlier revisions built
    // up nested Map fixtures here (shipType / unit / order / inventory item / material)
    // that were never actually attached to the mission; they have been removed because
    // unused container literals tripped CodeQL's "Container contents are never accessed"
    // rule and added confusion to anyone reading this test.
    MissionDto mission = minimalMission(missionId);

    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef(), eq(true)))
        .thenReturn(mission);
    when(backendApiClient.getCached(anyString(), anyTypeRef(), eq(true)))
        .thenReturn(Collections.emptyList());
    // OFFICER is a member, so the member-only finance ledger fetch now runs (REQ-SEC-013); stub it
    // empty so the page renders without exercising the entry-row template here.
    stubEmptyFinance(missionId);

    // This will fail with TemplateProcessingException if the template syntax is invalid
    mockMvc
        .perform(get("/missions/" + missionId))
        .andExpect(status().isOk())
        .andExpect(view().name("mission-detail"))
        // Tab layout (Variante B): sticky head + facts bar + accessible tab nav
        .andExpect(content().string(containsString("mission-head-sticky")))
        .andExpect(content().string(containsString("facts-bar")))
        .andExpect(content().string(containsString("role=\"tablist\"")))
        .andExpect(content().string(containsString("id=\"pane-ueb\"")))
        .andExpect(content().string(containsString("id=\"pane-crew\"")))
        .andExpect(content().string(containsString("id=\"pane-fin\"")))
        // Verwaltung tab renders for an editor (canEdit=true in this fixture)
        .andExpect(content().string(containsString("id=\"pane-verw\"")))
        .andExpect(content().string(containsString("role=\"tabpanel\"")))
        // Crew board skeleton: pool drop zone + the legacy participants table is gone
        .andExpect(content().string(containsString("id=\"board-pool\"")))
        .andExpect(content().string(not(containsString("mission-columns-container"))))
        // Legacy horizontal-scroll markers must be gone
        .andExpect(content().string(not(containsString("vertical-title"))));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void missionDetail_asMember_fetchesFinanceLedger() throws Exception {
    UUID missionId = UUID.randomUUID();
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef(), eq(true)))
        .thenReturn(minimalMission(missionId));
    when(backendApiClient.getCached(anyString(), anyTypeRef(), eq(true)))
        .thenReturn(Collections.emptyList());
    stubEmptyFinance(missionId);

    mockMvc.perform(get("/missions/" + missionId)).andExpect(status().isOk());

    // REQ-SEC-013 regression: a member must trigger the member-only finance ledger fetch. Before
    // the
    // fix isMemberOrAbove read the OidcUser principal authorities (which lack the Keycloak-mapped
    // ROLE_*), so this fetch was silently skipped and the "Finanzen" panel rendered empty.
    verify(backendApiClient)
        .get(
            eq("/api/v1/missions/" + missionId + "/finance-entries?size=1000"),
            anyTypeRef(),
            eq(false));
  }

  @Test
  void missionDetail_asAnonymous_skipsFinanceLedger() throws Exception {
    UUID missionId = UUID.randomUUID();
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef(), eq(true)))
        .thenReturn(minimalMission(missionId));
    when(backendApiClient.getCached(anyString(), anyTypeRef(), eq(true)))
        .thenReturn(Collections.emptyList());

    mockMvc.perform(get("/missions/" + missionId)).andExpect(status().isOk());

    // An anonymous visitor must NOT trigger the member-only finance fetch (it would 403 anyway).
    verify(backendApiClient, never()).get(contains("/finance-entries"), anyTypeRef(), eq(false));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void missionDetail_rendersAblaufChecklistAndEditor_whenStepsPresent() throws Exception {
    // The other render tests use empty step lists, so the per-step Thymeleaf path (the th:each over
    // mission.steps, the derived step--now via mission.steps.^[!done], the data-step-id toggle and
    // the editor rows) is only exercised here, with one done + one open step. Guards
    // REQ-MISSION-009.
    UUID missionId = UUID.randomUUID();
    var step1 =
        new de.greluc.krt.profit.basetool.frontend.model.dto.MissionStepDto(
            UUID.randomUUID(), "Briefing", "TS 19:30", true, 0);
    var step2 =
        new de.greluc.krt.profit.basetool.frontend.model.dto.MissionStepDto(
            UUID.randomUUID(), "Mining", null, false, 1);

    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef(), eq(true)))
        .thenReturn(missionWithSteps(missionId, java.util.List.of(step1, step2)));
    when(backendApiClient.getCached(anyString(), anyTypeRef(), eq(true)))
        .thenReturn(Collections.emptyList());
    stubEmptyFinance(missionId);

    mockMvc
        .perform(get("/missions/" + missionId))
        .andExpect(status().isOk())
        // Overview read-only checklist: titles, done + derived current-phase classes, toggle
        // control.
        .andExpect(content().string(containsString("class=\"ablauf\"")))
        .andExpect(content().string(containsString("Briefing")))
        .andExpect(content().string(containsString("Mining")))
        .andExpect(content().string(containsString("step--done")))
        .andExpect(content().string(containsString("step--now")))
        .andExpect(content().string(containsString("data-trigger=\"mission-toggle-step\"")))
        // Re-split overview + the new Ziele box (structured goals replace the single objective
        // row).
        .andExpect(content().string(containsString("Mission auf einen Blick")))
        .andExpect(content().string(containsString("class=\"ziele\"")))
        .andExpect(content().string(containsString("Janalite sammeln")))
        // Verwaltung drag-editors (canEdit fixture) + the meeting-point form field.
        .andExpect(content().string(containsString("id=\"mission-step-list\"")))
        .andExpect(content().string(containsString("id=\"mission-objective-list\"")))
        .andExpect(content().string(containsString("name=\"meetingPoint\"")));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void missionDetail_omitsEmptyGoalAndProcedureTiles_andOpensDescription() throws Exception {
    // Owner request 2026-07-01 (REQ-MISSION-004/-009/-012): when no goals + no Ablauf steps are
    // authored, the read-only Übersicht Ziele and Ablauf tiles are omitted entirely — no "Noch
    // keine …" placeholder — and the detailed-description <details> opens by default.
    // minimalMission
    // carries empty objectives + empty steps; here it also gets a description so the collapsible
    // renders. The Verwaltung drag-editors (canEdit fixture) stay regardless of emptiness.
    UUID missionId = UUID.randomUUID();
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef(), eq(true)))
        .thenReturn(minimalMission(missionId, "**Briefing** folgt."));
    when(backendApiClient.getCached(anyString(), anyTypeRef(), eq(true)))
        .thenReturn(Collections.emptyList());
    stubEmptyFinance(missionId);

    mockMvc
        .perform(get("/missions/" + missionId))
        .andExpect(status().isOk())
        // Empty goals/steps: the read-only overview tiles and their placeholders are gone.
        .andExpect(content().string(not(containsString("class=\"ablauf\""))))
        .andExpect(content().string(not(containsString("class=\"ziele\""))))
        .andExpect(content().string(not(containsString("Noch keine Ziele"))))
        .andExpect(content().string(not(containsString("Noch keine Schritte"))))
        // The Verwaltung editors remain reachable even with nothing authored yet.
        .andExpect(content().string(containsString("id=\"mission-step-list\"")))
        .andExpect(content().string(containsString("id=\"mission-objective-list\"")))
        // The detailed-description card renders expanded by default.
        .andExpect(content().string(containsString("<details class=\"more\" open")));
  }

  /**
   * Builds a renderable {@link MissionDto} carrying the given Ablauf steps plus one goal (Ziel) and
   * a meeting point (Treffpunkt), so the per-step checklist/editor, the Ziele box/editor and the
   * new at-a-glance rows actually render (REQ-MISSION-009/-012). Editable (canEdit), so the editors
   * + done-toggle show.
   *
   * @param missionId the id to stamp on the mission
   * @param steps the Ablauf steps to render
   * @return a mission fixture with steps + one goal + meeting point
   */
  private MissionDto missionWithSteps(
      UUID missionId,
      java.util.List<de.greluc.krt.profit.basetool.frontend.model.dto.MissionStepDto> steps) {
    de.greluc.krt.profit.basetool.frontend.model.dto.UserReferenceDto manager =
        new de.greluc.krt.profit.basetool.frontend.model.dto.UserReferenceDto(
            UUID.randomUUID(), "manager", null, "Test Manager", 0);
    return new MissionDto(
        missionId,
        "Test Mission",
        null,
        null,
        "ACTIVE",
        null,
        null,
        null,
        null,
        null,
        false,
        Collections.emptySet(),
        Collections.emptyList(),
        Collections.emptyList(),
        Collections.emptySet(),
        Collections.emptyList(),
        Collections.emptyList(),
        null,
        null,
        java.util.Set.of(manager),
        true,
        true,
        1L,
        1L,
        1L,
        1L,
        0,
        0,
        null,
        null,
        null,
        null,
        0L,
        steps,
        0L,
        java.util.List.of(
            new de.greluc.krt.profit.basetool.frontend.model.dto.MissionObjectiveDto(
                UUID.randomUUID(),
                "Janalite sammeln",
                de.greluc.krt.profit.basetool.frontend.model.dto.MissionObjectiveKind.PRIMARY,
                0)),
        0L,
        "ARC-L1");
  }

  /**
   * Builds a minimal renderable {@link MissionDto} (empty sub-collections, one manager, editable,
   * no description) for the mission-detail template tests, so the 38-argument constructor lives in
   * one place.
   *
   * @param missionId the id to stamp on the mission
   * @return a minimal mission fixture with no description
   */
  private MissionDto minimalMission(UUID missionId) {
    return minimalMission(missionId, null);
  }

  /**
   * Builds a minimal renderable {@link MissionDto} carrying the given (possibly {@code null})
   * Markdown description, so a test can exercise the collapsible detailed-description card while
   * still leaving the goals + Ablauf collections empty.
   *
   * @param missionId the id to stamp on the mission
   * @param description the Markdown description to render, or {@code null} for none
   * @return a minimal mission fixture with the given description
   */
  private MissionDto minimalMission(UUID missionId, String description) {
    de.greluc.krt.profit.basetool.frontend.model.dto.UserReferenceDto manager =
        new de.greluc.krt.profit.basetool.frontend.model.dto.UserReferenceDto(
            UUID.randomUUID(), "manager", null, "Test Manager", 0);
    return new MissionDto(
        missionId,
        "Test Mission",
        description,
        null,
        "PLANNED",
        null,
        null,
        null,
        null,
        null,
        false,
        Collections.emptySet(),
        Collections.emptyList(),
        Collections.emptyList(),
        Collections.emptySet(),
        Collections.emptyList(),
        Collections.emptyList(),
        null,
        null,
        java.util.Set.of(manager),
        true,
        true,
        1L,
        1L,
        1L,
        1L,
        0,
        0,
        null,
        null,
        null,
        null,
        0L,
        java.util.List.of(),
        0L,
        java.util.List.of(),
        0L,
        null);
  }

  /**
   * Stubs the member-only finance ledger list fetch to an empty page so a member-rendered
   * mission-detail page does not NPE on the absent finance payload. The sum and refinery fetches
   * are intentionally left unstubbed (the controller null-handles them).
   *
   * @param missionId the mission whose finance-entries fetch is stubbed
   */
  private void stubEmptyFinance(UUID missionId) {
    when(backendApiClient.get(
            eq("/api/v1/missions/" + missionId + "/finance-entries?size=1000"),
            anyTypeRef(),
            eq(false)))
        .thenReturn(
            new PageResponse<>(Collections.emptyList(), 0, 1000, 0, 0, Collections.emptyList()));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void missionDetail_AsAuthenticated_ShouldShowParticipationColumn() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();

    de.greluc.krt.profit.basetool.frontend.model.dto.MissionParticipantDto participant =
        new de.greluc.krt.profit.basetool.frontend.model.dto.MissionParticipantDto(
            participantId,
            null,
            "P1",
            null,
            null,
            null,
            null,
            null,
            null,
            de.greluc.krt.profit.basetool.frontend.model.PayoutPreference.PAYOUT,
            1L,
            null);

    MissionDto mission =
        new MissionDto(
            missionId,
            "Auth Mission",
            null,
            null,
            "PLANNED",
            null,
            null,
            null,
            null,
            null,
            false,
            java.util.Set.of(participant),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptySet(),
            Collections.emptyList(),
            Collections.emptyList(),
            null,
            null,
            Collections.emptySet(),
            true,
            true,
            1L,
            1L,
            1L,
            1L,
            1,
            1,
            null,
            null,
            null,
            null,
            0L,
            java.util.List.of(),
            0L,
            java.util.List.of(),
            0L,
            null);
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef(), eq(true)))
        .thenReturn(mission);
    when(backendApiClient.getCached(anyString(), anyTypeRef(), eq(true)))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/missions/" + missionId))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Teilnahme (%)")));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void missionDetail_ShouldRenderEditButtonWithCheckInTimes() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();

    java.time.Instant checkIn = java.time.Instant.parse("2026-02-10T09:30:00Z");
    java.time.Instant checkOut = java.time.Instant.parse("2026-02-10T11:45:00Z");

    de.greluc.krt.profit.basetool.frontend.model.dto.MissionParticipantDto participant =
        new de.greluc.krt.profit.basetool.frontend.model.dto.MissionParticipantDto(
            participantId,
            null,
            "P1",
            null,
            null,
            null,
            null,
            checkIn,
            checkOut,
            de.greluc.krt.profit.basetool.frontend.model.PayoutPreference.PAYOUT,
            1L,
            null);

    MissionDto mission =
        new MissionDto(
            missionId,
            "Repro Mission",
            null,
            null,
            "RUNNING",
            null,
            null,
            java.time.Instant.parse("2026-02-10T09:00:00Z"),
            null,
            null,
            false,
            java.util.Set.of(participant),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptySet(),
            Collections.emptyList(),
            Collections.emptyList(),
            null,
            null,
            Collections.emptySet(),
            true,
            true,
            1L,
            1L,
            1L,
            1L,
            1,
            1,
            null,
            null,
            null,
            null,
            0L,
            java.util.List.of(),
            0L,
            java.util.List.of(),
            0L,
            null);
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef(), eq(true)))
        .thenReturn(mission);
    when(backendApiClient.getCached(anyString(), anyTypeRef(), eq(true)))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/missions/" + missionId))
        .andExpect(status().isOk())
        // Raw UTC timestamps must be present on the edit button
        .andExpect(content().string(containsString("data-start-time=\"2026-02-10T09:30:00Z\"")))
        .andExpect(content().string(containsString("data-end-time=\"2026-02-10T11:45:00Z\"")))
        // Formatted (local-zone) timestamps must also be present and non-empty so the
        // participant edit modal can pre-populate datetime-local inputs.
        .andExpect(content().string(not(containsString("data-start-time-formatted=\"\""))))
        .andExpect(content().string(not(containsString("data-end-time-formatted=\"\""))))
        // Regression guard: the edit modal must re-sync the datetime-split widget
        // after programmatically setting the hidden value (otherwise the visible
        // date/time inputs stay empty even though startTime/endTime are present).
        .andExpect(content().string(containsString("krtSyncDatetimeSplitGroup")));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void missionDetail_LinkedRefineryOrder_RendersEndTimeForClientSideLocalZoneConversion()
      throws Exception {
    // Regression guard: a refinery order linked to a mission used to render its end time with a raw
    // #temporals.format() that fell back to the server's default zone (UTC in the container), so
    // the
    // user saw UTC instead of their local time. The fix renders the end timestamp into a data-utc
    // epoch-millis attribute plus an explicitly UTC-labelled fallback, and a small page script
    // (formatRefineryEndLocalTimes) rewrites it to the browser's local zone on load.
    UUID missionId = UUID.randomUUID();

    de.greluc.krt.profit.basetool.frontend.model.dto.RefineryOrderDto order =
        new de.greluc.krt.profit.basetool.frontend.model.dto.RefineryOrderDto(
            UUID.randomUUID(),
            null,
            null,
            null,
            java.time.Instant.parse("2026-02-10T09:00:00Z"),
            120L,
            null,
            null,
            null,
            null,
            null,
            Collections.emptyList(),
            null,
            null,
            1L,
            null);
    long expectedEndsAtMillis = order.getEndsAt().toEpochMilli();

    MissionDto mission =
        new MissionDto(
            missionId,
            "Refinery TZ Mission",
            null,
            null,
            "RUNNING",
            null,
            null,
            null,
            null,
            null,
            false,
            Collections.emptySet(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptySet(),
            Collections.emptyList(),
            List.of(order),
            null,
            null,
            Collections.emptySet(),
            true,
            true,
            1L,
            1L,
            1L,
            1L,
            0,
            0,
            null,
            null,
            null,
            null,
            0L,
            java.util.List.of(),
            0L,
            java.util.List.of(),
            0L,
            null);
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef(), eq(true)))
        .thenReturn(mission);
    when(backendApiClient.getCached(anyString(), anyTypeRef(), eq(true)))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/missions/" + missionId))
        .andExpect(status().isOk())
        // End timestamp carried as epoch millis for the client-side local-zone converter.
        .andExpect(content().string(containsString("data-utc=\"" + expectedEndsAtMillis + "\"")))
        .andExpect(content().string(containsString("class=\"refinery-endsat-local\"")))
        // Pre-JS fallback is explicitly UTC-labelled (never an unlabelled, misleading local-looking
        // string).
        .andExpect(content().string(containsString("10.02.2026 11:00 UTC")))
        // The conversion script must be present so the value becomes browser-local on load.
        .andExpect(content().string(containsString("krtFormatLocalDateTime")));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void missionDetail_ShouldRenderParticipantsCounter_AsXSlashY() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID p1Id = UUID.randomUUID();
    UUID p2Id = UUID.randomUUID();
    UUID p3Id = UUID.randomUUID();

    de.greluc.krt.profit.basetool.frontend.model.dto.MissionParticipantDto p1 =
        new de.greluc.krt.profit.basetool.frontend.model.dto.MissionParticipantDto(
            p1Id,
            null,
            "P1",
            null,
            null,
            null,
            null,
            null,
            null,
            de.greluc.krt.profit.basetool.frontend.model.PayoutPreference.PAYOUT,
            1L,
            null);
    de.greluc.krt.profit.basetool.frontend.model.dto.MissionParticipantDto p2 =
        new de.greluc.krt.profit.basetool.frontend.model.dto.MissionParticipantDto(
            p2Id,
            null,
            "P2",
            null,
            null,
            null,
            null,
            null,
            null,
            de.greluc.krt.profit.basetool.frontend.model.PayoutPreference.PAYOUT,
            1L,
            null);
    de.greluc.krt.profit.basetool.frontend.model.dto.MissionParticipantDto p3 =
        new de.greluc.krt.profit.basetool.frontend.model.dto.MissionParticipantDto(
            p3Id,
            null,
            "P3",
            null,
            null,
            null,
            null,
            null,
            null,
            de.greluc.krt.profit.basetool.frontend.model.PayoutPreference.PAYOUT,
            1L,
            null);

    // 2 checked-in out of 3 registered
    MissionDto mission =
        new MissionDto(
            missionId,
            "Counter Mission",
            null,
            null,
            "PLANNED",
            null,
            null,
            null,
            null,
            null,
            false,
            java.util.Set.of(p1, p2, p3),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptySet(),
            Collections.emptyList(),
            Collections.emptyList(),
            null,
            null,
            Collections.emptySet(),
            true,
            true,
            1L,
            1L,
            1L,
            1L,
            2,
            3,
            null,
            null,
            null,
            null,
            0L,
            java.util.List.of(),
            0L,
            java.util.List.of(),
            0L,
            null);
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef(), eq(true)))
        .thenReturn(mission);
    when(backendApiClient.getCached(anyString(), anyTypeRef(), eq(true)))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/missions/" + missionId))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("2/3")));
  }

  @Test
  void missionDetail_AsGuest_ShouldHideParticipationColumn() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();

    de.greluc.krt.profit.basetool.frontend.model.dto.MissionParticipantDto participant =
        new de.greluc.krt.profit.basetool.frontend.model.dto.MissionParticipantDto(
            participantId,
            null,
            "P1",
            null,
            null,
            null,
            null,
            null,
            null,
            de.greluc.krt.profit.basetool.frontend.model.PayoutPreference.PAYOUT,
            1L,
            null);

    MissionDto mission =
        new MissionDto(
            missionId,
            "Guest Mission",
            null,
            null,
            "PLANNED",
            null,
            null,
            null,
            null,
            null,
            false,
            java.util.Set.of(participant),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptySet(),
            Collections.emptyList(),
            Collections.emptyList(),
            null,
            null,
            Collections.emptySet(),
            true,
            true,
            1L,
            1L,
            1L,
            1L,
            0,
            1,
            null,
            null,
            null,
            null,
            0L,
            java.util.List.of(),
            0L,
            java.util.List.of(),
            0L,
            null);
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef(), eq(true)))
        .thenReturn(mission);
    when(backendApiClient.getCached(anyString(), anyTypeRef(), eq(true)))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/missions/" + missionId))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("Teilnahme (%)"))));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void setMissionOwner_WithValidIds_ShouldReturn200() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();

    when(backendApiClient.put(
            eq("/api/v1/missions/" + missionId + "/owner/" + userId),
            eq(null),
            eq(Void.class),
            eq(false)))
        .thenReturn(null);

    mockMvc
        .perform(put("/missions/" + missionId + "/owner/" + userId).with(csrf()))
        .andExpect(status().isOk());
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void setMissionOwner_WithInvalidMissionId_ShouldReturn400() throws Exception {
    mockMvc
        .perform(put("/missions/not-a-uuid/owner/" + UUID.randomUUID()).with(csrf()))
        .andExpect(status().isBadRequest());
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void addManager_WithInvalidIds_ShouldReturn400() throws Exception {
    mockMvc
        .perform(post("/missions/not-a-uuid/managers/not-a-uuid").with(csrf()))
        .andExpect(status().isBadRequest());
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void updateActualTime_Success_ShouldReturn200WithRefreshedMission() throws Exception {
    UUID missionId = UUID.randomUUID();
    MissionDto current =
        new MissionDto(
            missionId,
            "M",
            null,
            null,
            "PLANNED",
            null,
            null,
            null,
            null,
            null,
            false,
            Collections.emptySet(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptySet(),
            Collections.emptyList(),
            Collections.emptyList(),
            null,
            null,
            Collections.emptySet(),
            true,
            true,
            1L,
            1L,
            1L,
            1L,
            0,
            0,
            null,
            null,
            null,
            null,
            0L,
            java.util.List.of(),
            0L,
            java.util.List.of(),
            0L,
            null);
    MissionDto refreshed =
        new MissionDto(
            missionId,
            "M",
            null,
            null,
            "PLANNED",
            null,
            null,
            java.time.Instant.parse("2026-04-20T12:00:00Z"),
            null,
            null,
            false,
            Collections.emptySet(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptySet(),
            Collections.emptyList(),
            Collections.emptyList(),
            null,
            null,
            Collections.emptySet(),
            true,
            true,
            2L,
            1L,
            1L,
            1L,
            0,
            0,
            null,
            null,
            null,
            null,
            0L,
            java.util.List.of(),
            0L,
            java.util.List.of(),
            0L,
            null);
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), eq(MissionDto.class)))
        .thenReturn(current)
        .thenReturn(refreshed);
    // The actual-time endpoint now dispatches a section-scoped PATCH on /schedule, not the
    // legacy full-PUT — concurrent edits on other sections must not 409 the actual-time flow.
    when(backendApiClient.patch(
            eq("/api/v1/missions/" + missionId + "/schedule"), any(), eq(Void.class)))
        .thenReturn(null);

    String body =
        "{\"field\":\"actualStartTime\",\"value\":\"2026-04-20T12:00:00Z\",\"version\":1}";
    mockMvc
        .perform(
            post("/missions/" + missionId + "/actual-time")
                .with(csrf())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isOk());
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void updateActualTime_OptimisticLockConflict_ShouldReturn409() throws Exception {
    UUID missionId = UUID.randomUUID();
    MissionDto current =
        new MissionDto(
            missionId,
            "M",
            null,
            null,
            "PLANNED",
            null,
            null,
            null,
            null,
            null,
            false,
            Collections.emptySet(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptySet(),
            Collections.emptyList(),
            Collections.emptyList(),
            null,
            null,
            Collections.emptySet(),
            true,
            true,
            5L,
            1L,
            1L,
            1L,
            0,
            0,
            null,
            null,
            null,
            null,
            0L,
            java.util.List.of(),
            0L,
            java.util.List.of(),
            0L,
            null);
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), eq(MissionDto.class)))
        .thenReturn(current);
    // The actual-time endpoint dispatches a section-scoped PATCH on /schedule. A stale
    // scheduleVersion now surfaces as a 409 here — and only on the schedule patch, so
    // concurrent edits on core or flags are unaffected.
    when(backendApiClient.patch(
            eq("/api/v1/missions/" + missionId + "/schedule"), any(), eq(Void.class)))
        .thenThrow(
            new de.greluc.krt.profit.basetool.frontend.service.BackendServiceException(
                "Conflict", null, 409));

    String body = "{\"field\":\"actualEndTime\",\"value\":\"2026-04-20T13:00:00Z\",\"version\":1}";
    mockMvc
        .perform(
            post("/missions/" + missionId + "/actual-time")
                .with(csrf())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isConflict());
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void updateActualTime_InvalidField_ShouldReturn400() throws Exception {
    UUID missionId = UUID.randomUUID();
    String body = "{\"field\":\"unknownField\",\"value\":\"2026-04-20T13:00:00Z\",\"version\":1}";
    mockMvc
        .perform(
            post("/missions/" + missionId + "/actual-time")
                .with(csrf())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void addManager_WithBackendError_ShouldPropagateStatus() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();

    when(backendApiClient.post(
            eq("/api/v1/missions/" + missionId + "/managers/" + userId + "/slim"),
            eq(null),
            eq(String.class),
            eq(false)))
        .thenThrow(
            new de.greluc.krt.profit.basetool.frontend.service.BackendServiceException(
                "Error", null, 400));

    mockMvc
        .perform(post("/missions/" + missionId + "/managers/" + userId).with(csrf()))
        .andExpect(status().isBadRequest());
  }

  // --- Paket 3B: Frequencies AJAX endpoints -----------------------------

  @Test
  @WithMockUser(roles = "OFFICER")
  void addOrUpdateFrequencyAjax_WithValidBody_ShouldReturn200() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID freqTypeId = UUID.randomUUID();
    java.util.List<Map<String, Object>> slimResponse = new java.util.ArrayList<>();
    Map<String, Object> freq = new java.util.HashMap<>();
    freq.put("id", UUID.randomUUID().toString());
    Map<String, Object> ft = new java.util.HashMap<>();
    ft.put("id", freqTypeId.toString());
    ft.put("name", "Tac");
    freq.put("frequencyType", ft);
    freq.put("value", 123.45);
    freq.put("version", 1);
    slimResponse.add(freq);

    when(backendApiClient.post(
            eq("/api/v1/missions/" + missionId + "/frequencies/slim"),
            any(),
            eq(Object.class),
            eq(false)))
        .thenReturn(slimResponse);

    String body = "{\"frequencyTypeId\":\"" + freqTypeId + "\",\"value\":123.45}";
    mockMvc
        .perform(
            put("/missions/" + missionId + "/frequencies/ajax")
                .with(csrf())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(freqTypeId.toString())));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void addOrUpdateFrequencyAjax_WithBackendError_ShouldPropagateStatus() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID freqTypeId = UUID.randomUUID();
    when(backendApiClient.post(
            eq("/api/v1/missions/" + missionId + "/frequencies/slim"),
            any(),
            eq(Object.class),
            eq(false)))
        .thenThrow(
            new de.greluc.krt.profit.basetool.frontend.service.BackendServiceException(
                "Conflict", null, 409));

    String body = "{\"frequencyTypeId\":\"" + freqTypeId + "\",\"value\":123.45}";
    mockMvc
        .perform(
            put("/missions/" + missionId + "/frequencies/ajax")
                .with(csrf())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isConflict());
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void deleteFrequencyAjax_Success_ShouldReturn200() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID freqId = UUID.randomUUID();
    when(backendApiClient.delete(
            eq("/api/v1/missions/" + missionId + "/frequencies/" + freqId + "/slim"),
            eq(Object.class),
            eq(false)))
        .thenReturn(java.util.Collections.emptyList());

    mockMvc
        .perform(delete("/missions/" + missionId + "/frequencies/" + freqId + "/ajax").with(csrf()))
        .andExpect(status().isOk());
  }

  // --- Custom (mission-specific) frequencies AJAX endpoints (REQ-MISSION-014) -----

  @Test
  @WithMockUser(roles = "OFFICER")
  void addCustomFrequencyAjax_WithValidBody_ShouldReturn200() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID freqId = UUID.randomUUID();
    java.util.List<Map<String, Object>> slimResponse = new java.util.ArrayList<>();
    Map<String, Object> freq = new java.util.HashMap<>();
    freq.put("id", freqId.toString());
    freq.put("name", "Recon");
    freq.put("value", 42.10);
    freq.put("version", 0);
    slimResponse.add(freq);

    when(backendApiClient.post(
            eq("/api/v1/missions/" + missionId + "/frequencies/custom/slim"),
            any(),
            eq(Object.class),
            eq(false)))
        .thenReturn(slimResponse);

    String body = "{\"name\":\"Recon\",\"value\":42.10}";
    mockMvc
        .perform(
            post("/missions/" + missionId + "/frequencies/custom/ajax")
                .with(csrf())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Recon")));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void updateCustomFrequencyAjax_WithValidBody_ShouldReturn200() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID freqId = UUID.randomUUID();
    java.util.List<Map<String, Object>> slimResponse = new java.util.ArrayList<>();
    Map<String, Object> freq = new java.util.HashMap<>();
    freq.put("id", freqId.toString());
    freq.put("name", "Recon 2");
    freq.put("value", 43.00);
    freq.put("version", 1);
    slimResponse.add(freq);

    when(backendApiClient.put(
            eq("/api/v1/missions/" + missionId + "/frequencies/custom/" + freqId + "/slim"),
            any(),
            eq(Object.class),
            eq(false)))
        .thenReturn(slimResponse);

    String body = "{\"name\":\"Recon 2\",\"value\":43.00,\"version\":0}";
    mockMvc
        .perform(
            put("/missions/" + missionId + "/frequencies/custom/" + freqId + "/ajax")
                .with(csrf())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Recon 2")));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void updateCustomFrequencyAjax_WithBackendConflict_ShouldPropagateStatus() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID freqId = UUID.randomUUID();
    when(backendApiClient.put(
            eq("/api/v1/missions/" + missionId + "/frequencies/custom/" + freqId + "/slim"),
            any(),
            eq(Object.class),
            eq(false)))
        .thenThrow(
            new de.greluc.krt.profit.basetool.frontend.service.BackendServiceException(
                "Conflict", null, 409));

    String body = "{\"name\":\"Recon\",\"value\":42.10,\"version\":0}";
    mockMvc
        .perform(
            put("/missions/" + missionId + "/frequencies/custom/" + freqId + "/ajax")
                .with(csrf())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isConflict());
  }

  // --- Paket 3C: Units AJAX endpoints -----------------------------------

  @Test
  @WithMockUser(roles = "OFFICER")
  void addUnitAjax_WithValidBody_ShouldReturn200() throws Exception {
    UUID missionId = UUID.randomUUID();
    java.util.List<Map<String, Object>> slimResponse = new java.util.ArrayList<>();
    Map<String, Object> unit = new java.util.HashMap<>();
    unit.put("id", UUID.randomUUID().toString());
    unit.put("name", "Alpha");
    unit.put("highValueUnit", false);
    unit.put("version", 0);
    slimResponse.add(unit);

    when(backendApiClient.post(
            eq("/api/v1/missions/" + missionId + "/units/slim"),
            any(),
            eq(Object.class),
            eq(false)))
        .thenReturn(slimResponse);

    String body = "{\"name\":\"Alpha\",\"highValueUnit\":false}";
    mockMvc
        .perform(
            post("/missions/" + missionId + "/units/ajax")
                .with(csrf())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Alpha")));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void updateUnitAjax_WithBackendConflict_ShouldReturn409() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID unitId = UUID.randomUUID();
    when(backendApiClient.put(
            eq("/api/v1/missions/" + missionId + "/units/" + unitId + "/slim"),
            any(),
            eq(Object.class),
            eq(false)))
        .thenThrow(
            new de.greluc.krt.profit.basetool.frontend.service.BackendServiceException(
                "Conflict", null, 409));

    String body = "{\"name\":\"Alpha\",\"highValueUnit\":false}";
    mockMvc
        .perform(
            put("/missions/" + missionId + "/units/" + unitId + "/ajax")
                .with(csrf())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isConflict());
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void deleteUnitAjax_Success_ShouldReturn204() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID unitId = UUID.randomUUID();
    when(backendApiClient.delete(
            eq("/api/v1/missions/" + missionId + "/units/" + unitId + "/slim"),
            eq(Void.class),
            eq(false)))
        .thenReturn(null);

    mockMvc
        .perform(delete("/missions/" + missionId + "/units/" + unitId + "/ajax").with(csrf()))
        .andExpect(status().isNoContent());
  }

  // --- Paket 3C (Option b): Participants AJAX endpoints ------------------

  @Test
  @WithMockUser(roles = "OFFICER")
  void addParticipantAjax_WithValidBody_ShouldReturn200() throws Exception {
    UUID missionId = UUID.randomUUID();
    java.util.List<Map<String, Object>> slimResponse = new java.util.ArrayList<>();
    Map<String, Object> p = new java.util.HashMap<>();
    p.put("id", UUID.randomUUID().toString());
    p.put("guestName", "Guest-X");
    p.put("version", 0);
    slimResponse.add(p);

    // Note: since the bugfix for anonymous mission signups, this controller method
    // routes via `isPublic = (principal == null)` — `@WithMockUser` does not produce
    // an `OidcUser` principal, so `isPublic` evaluates to `true` here. The dedicated
    // happy-path test for an authenticated OIDC user lives in
    // `addParticipantAjax_AsAnonymousGuest_ShouldRouteThroughPublicWebClientAndReturn200`
    // (anonymous) and is implicitly covered by the production wiring; for this MVC
    // smoke-test we therefore accept any boolean for the isPublic flag and only
    // assert that the controller forwards to the correct backend slim endpoint.
    when(backendApiClient.post(
            eq("/api/v1/missions/" + missionId + "/participants/slim"),
            any(),
            eq(Object.class),
            org.mockito.ArgumentMatchers.anyBoolean()))
        .thenReturn(slimResponse);

    String body = "{\"guestName\":\"Guest-X\"}";
    mockMvc
        .perform(
            post("/missions/" + missionId + "/participants/ajax")
                .with(csrf())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Guest-X")));
  }

  /**
   * Reproducer for the "anonymous guest cannot sign up to a mission" bug (see live-log/log.txt:
   * repeated `AccessDeniedException` on POST /missions/{id}/participants/ajax for `[anonymous]`).
   *
   * <p>Given an anonymous caller (no `@WithMockUser`), when the AJAX add-participant endpoint is
   * invoked with a guestName payload, then the request must be routed through the public WebClient
   * (`isPublic=true`) and return HTTP 200 with the slim response - it must NOT be blocked by Spring
   * Security with 403.
   */
  @Test
  void addParticipantAjax_AsAnonymousGuest_ShouldRouteThroughPublicWebClientAndReturn200()
      throws Exception {
    UUID missionId = UUID.randomUUID();
    java.util.List<Map<String, Object>> slimResponse = new java.util.ArrayList<>();
    Map<String, Object> p = new java.util.HashMap<>();
    p.put("id", UUID.randomUUID().toString());
    p.put("guestName", "Anon-Guest");
    p.put("version", 0);
    slimResponse.add(p);

    // The fix flips isPublic to true when no OidcUser principal is present, so
    // the stub MUST match isPublic=true for an anonymous caller.
    when(backendApiClient.post(
            eq("/api/v1/missions/" + missionId + "/participants/slim"),
            any(),
            eq(Object.class),
            eq(true)))
        .thenReturn(slimResponse);

    String body = "{\"guestName\":\"Anon-Guest\"}";
    mockMvc
        .perform(
            post("/missions/" + missionId + "/participants/ajax")
                .with(csrf())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Anon-Guest")));
  }

  /**
   * Reproducer for the "anonymous guest cannot edit/delete their own participant entry" bug (see
   * live-log/log.txt: repeated 403 on PUT and DELETE /missions/{id}/participants/{pid}/ajax for
   * `[anonymous]`).
   *
   * <p>Given an anonymous caller, the AJAX update/delete endpoints must route through the public
   * WebClient (`isPublic=true`) and not be blocked by Spring Security.
   */
  @Test
  void updateParticipantAjax_AsAnonymousGuest_ShouldRouteThroughPublicWebClientAndReturn200()
      throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();
    Map<String, Object> slimResponse = new java.util.HashMap<>();
    slimResponse.put("id", participantId.toString());
    slimResponse.put("guestName", "Anon-Guest");
    slimResponse.put("version", 1);
    when(backendApiClient.put(
            eq("/api/v1/missions/" + missionId + "/participants/" + participantId + "/slim"),
            any(),
            eq(Object.class),
            eq(true)))
        .thenReturn(slimResponse);

    String body = "{\"version\":0,\"guestName\":\"Anon-Guest\",\"comment\":\"edited\"}";
    mockMvc
        .perform(
            put("/missions/" + missionId + "/participants/" + participantId + "/ajax")
                .with(csrf())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Anon-Guest")));
  }

  @Test
  void deleteParticipantAjax_AsAnonymousGuest_ShouldRouteThroughPublicWebClientAndReturn204()
      throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();
    when(backendApiClient.delete(
            eq("/api/v1/missions/" + missionId + "/participants/" + participantId + "/slim"),
            eq(Void.class),
            eq(true)))
        .thenReturn(null);

    mockMvc
        .perform(
            delete("/missions/" + missionId + "/participants/" + participantId + "/ajax")
                .with(csrf()))
        .andExpect(status().isNoContent());
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void updateParticipantAjax_WithBackendConflict_ShouldReturn409() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();
    // Note: since the bugfix for anonymous mission participant edits, this controller
    // method routes via `isPublic = (principal == null)`. `@WithMockUser` does not
    // produce an `OidcUser` principal, so isPublic evaluates to true here. Accept any
    // boolean for the isPublic flag in this MVC smoke-test.
    when(backendApiClient.put(
            eq("/api/v1/missions/" + missionId + "/participants/" + participantId + "/slim"),
            any(),
            eq(Object.class),
            org.mockito.ArgumentMatchers.anyBoolean()))
        .thenThrow(
            new de.greluc.krt.profit.basetool.frontend.service.BackendServiceException(
                "Conflict", null, 409));

    String body = "{\"version\":1,\"comment\":\"test\"}";
    mockMvc
        .perform(
            put("/missions/" + missionId + "/participants/" + participantId + "/ajax")
                .with(csrf())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isConflict());
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void deleteParticipantAjax_Success_ShouldReturn204() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();
    when(backendApiClient.delete(
            eq("/api/v1/missions/" + missionId + "/participants/" + participantId + "/slim"),
            eq(Void.class),
            org.mockito.ArgumentMatchers.anyBoolean()))
        .thenReturn(null);

    mockMvc
        .perform(
            delete("/missions/" + missionId + "/participants/" + participantId + "/ajax")
                .with(csrf()))
        .andExpect(status().isNoContent());
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void checkInParticipantAjax_Success_ShouldReturn200() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();
    Map<String, Object> slimResponse = new java.util.HashMap<>();
    slimResponse.put("id", participantId.toString());
    slimResponse.put("version", 2);
    when(backendApiClient.post(
            eq(
                "/api/v1/missions/"
                    + missionId
                    + "/participants/"
                    + participantId
                    + "/check-in/slim"),
            any(),
            eq(Object.class),
            org.mockito.ArgumentMatchers.anyBoolean()))
        .thenReturn(slimResponse);

    mockMvc
        .perform(
            post("/missions/" + missionId + "/participants/" + participantId + "/check-in/ajax")
                .with(csrf()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(participantId.toString())));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void checkOutParticipantAjax_WithBackendError_ShouldPropagateStatus() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();
    when(backendApiClient.post(
            eq(
                "/api/v1/missions/"
                    + missionId
                    + "/participants/"
                    + participantId
                    + "/check-out/slim"),
            any(),
            eq(Object.class),
            org.mockito.ArgumentMatchers.anyBoolean()))
        .thenThrow(
            new de.greluc.krt.profit.basetool.frontend.service.BackendServiceException(
                "Conflict", null, 409));

    mockMvc
        .perform(
            post("/missions/" + missionId + "/participants/" + participantId + "/check-out/ajax")
                .with(csrf()))
        .andExpect(status().isConflict());
  }

  // --- Paket 3C (Option c): Crew AJAX endpoints --------------------------

  @Test
  @WithMockUser(roles = "OFFICER")
  void addCrewAjax_WithValidBody_ShouldReturn200() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID unitId = UUID.randomUUID();
    java.util.List<Map<String, Object>> slimResponse = new java.util.ArrayList<>();
    Map<String, Object> crew = new java.util.HashMap<>();
    crew.put("id", UUID.randomUUID().toString());
    crew.put("participantName", "Alice");
    crew.put("version", 0);
    slimResponse.add(crew);

    when(backendApiClient.post(
            eq("/api/v1/missions/" + missionId + "/units/" + unitId + "/crew/slim"),
            any(),
            eq(Object.class),
            eq(false)))
        .thenReturn(slimResponse);

    String body = "{\"participantId\":\"" + UUID.randomUUID() + "\",\"jobTypeIds\":[]}";
    mockMvc
        .perform(
            post("/missions/" + missionId + "/units/" + unitId + "/crew/ajax")
                .with(csrf())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Alice")));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void updateCrewAjax_WithBackendConflict_ShouldReturn409() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID unitId = UUID.randomUUID();
    UUID crewId = UUID.randomUUID();
    when(backendApiClient.put(
            eq("/api/v1/missions/" + missionId + "/units/" + unitId + "/crew/" + crewId + "/slim"),
            any(),
            eq(Object.class),
            eq(false)))
        .thenThrow(
            new de.greluc.krt.profit.basetool.frontend.service.BackendServiceException(
                "Conflict", null, 409));

    String body = "{\"jobTypeIds\":[]}";
    mockMvc
        .perform(
            put("/missions/" + missionId + "/units/" + unitId + "/crew/" + crewId + "/ajax")
                .with(csrf())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isConflict());
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void deleteCrewAjax_Success_ShouldReturn204() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID unitId = UUID.randomUUID();
    UUID crewId = UUID.randomUUID();
    when(backendApiClient.delete(
            eq("/api/v1/missions/" + missionId + "/units/" + unitId + "/crew/" + crewId + "/slim"),
            eq(Void.class),
            eq(false)))
        .thenReturn(null);

    mockMvc
        .perform(
            delete("/missions/" + missionId + "/units/" + unitId + "/crew/" + crewId + "/ajax")
                .with(csrf()))
        .andExpect(status().isNoContent());
  }

  // --- Bugfix: MEMBER sieht Bearbeiten-Button für eigenen Teilnehmereintrag ---

  @Test
  void missionDetail_AsMember_ShouldShowEditButtonForOwnParticipantEntry() throws Exception {
    // Given
    UUID missionId = UUID.randomUUID();
    UUID memberUserId = UUID.randomUUID(); // Keycloak sub der eingeloggten Person
    UUID participantId = UUID.randomUUID();

    de.greluc.krt.profit.basetool.frontend.model.dto.UserDto memberUser =
        new de.greluc.krt.profit.basetool.frontend.model.dto.UserDto(
            memberUserId,
            "member1",
            "Member One",
            "Member One",
            "member@example.com",
            1,
            null,
            java.util.Set.of("KRT_MEMBER"),
            java.util.Set.of(),
            null,
            false,
            false,
            true,
            null,
            java.util.List.of(),
            1L,
            null,
            false);

    de.greluc.krt.profit.basetool.frontend.model.dto.MissionParticipantDto participant =
        new de.greluc.krt.profit.basetool.frontend.model.dto.MissionParticipantDto(
            participantId,
            memberUser,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            de.greluc.krt.profit.basetool.frontend.model.PayoutPreference.PAYOUT,
            1L,
            null);

    MissionDto mission =
        new MissionDto(
            missionId,
            "Test Mission",
            null,
            null,
            "PLANNED",
            null,
            null,
            null,
            null,
            null,
            false,
            java.util.Set.of(participant),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptySet(),
            Collections.emptyList(),
            Collections.emptyList(),
            null,
            null,
            Collections.emptySet(),
            false, // canEdit = false (kein Manager/Admin)
            false,
            1L,
            1L,
            1L,
            1L,
            1,
            1,
            null,
            null,
            null,
            null,
            0L,
            java.util.List.of(),
            0L,
            java.util.List.of(),
            0L,
            null);

    de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse<Object> emptyPage =
        new de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse<>(
            Collections.emptyList(), 0, 0, 0, 0, Collections.emptyList());
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef(), eq(false)))
        .thenReturn(mission);
    when(backendApiClient.getCached(
            anyString(), anyTypeRef(), org.mockito.ArgumentMatchers.anyBoolean()))
        .thenReturn(emptyPage);
    when(backendApiClient.getCached(anyString(), anyTypeRef())).thenReturn(emptyPage);
    when(backendApiClient.get(anyString(), anyTypeRef(), eq(false))).thenReturn(emptyPage);
    when(backendApiClient.get(anyString(), anyClass(), eq(false))).thenReturn(null);
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef(), eq(false)))
        .thenReturn(mission);

    // When / Then: MEMBER sieht den Bearbeiten-Button für seinen eigenen Eintrag
    mockMvc
        .perform(
            get("/missions/" + missionId)
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.oidcLogin()
                        .idToken(
                            token ->
                                token
                                    .subject(memberUserId.toString())
                                    .claim("preferred_username", "member1"))))
        .andExpect(status().isOk())
        .andExpect(
            // The participant edit action is now an icon button (gained `btn-icon`). Assert the
            // full class attribute of the rendered button — the bare `edit-participant-btn` marker
            // also appears in the page's inline JS (querySelectorAll), so it cannot tell
            // "button rendered" apart from "script present".
            content()
                .string(containsString("class=\"btn btn-ghost btn-icon edit-participant-btn\"")));
  }

  @Test
  void missionDetail_AsMember_ShouldNotShowEditButtonForForeignParticipantEntry() throws Exception {
    // Given
    UUID missionId = UUID.randomUUID();
    UUID loggedInUserId = UUID.randomUUID(); // eingeloggter MEMBER
    UUID otherUserId = UUID.randomUUID(); // anderer Nutzer
    UUID participantId = UUID.randomUUID();

    de.greluc.krt.profit.basetool.frontend.model.dto.UserDto otherUser =
        new de.greluc.krt.profit.basetool.frontend.model.dto.UserDto(
            otherUserId,
            "other1",
            "Other One",
            "Other One",
            "other@example.com",
            1,
            null,
            java.util.Set.of("KRT_MEMBER"),
            java.util.Set.of(),
            null,
            false,
            false,
            true,
            null,
            java.util.List.of(),
            1L,
            null,
            false);

    de.greluc.krt.profit.basetool.frontend.model.dto.MissionParticipantDto participant =
        new de.greluc.krt.profit.basetool.frontend.model.dto.MissionParticipantDto(
            participantId,
            otherUser,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            de.greluc.krt.profit.basetool.frontend.model.PayoutPreference.PAYOUT,
            1L,
            null);

    MissionDto mission =
        new MissionDto(
            missionId,
            "Test Mission",
            null,
            null,
            "PLANNED",
            null,
            null,
            null,
            null,
            null,
            false,
            java.util.Set.of(participant),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptySet(),
            Collections.emptyList(),
            Collections.emptyList(),
            null,
            null,
            Collections.emptySet(),
            false, // canEdit = false (kein Manager/Admin)
            false,
            1L,
            1L,
            1L,
            1L,
            1,
            1,
            null,
            null,
            null,
            null,
            0L,
            java.util.List.of(),
            0L,
            java.util.List.of(),
            0L,
            null);

    de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse<Object> emptyPage2 =
        new de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse<>(
            Collections.emptyList(), 0, 0, 0, 0, Collections.emptyList());
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef(), eq(false)))
        .thenReturn(mission);
    when(backendApiClient.getCached(
            anyString(), anyTypeRef(), org.mockito.ArgumentMatchers.anyBoolean()))
        .thenReturn(emptyPage2);
    when(backendApiClient.getCached(anyString(), anyTypeRef())).thenReturn(emptyPage2);
    when(backendApiClient.get(anyString(), anyTypeRef(), eq(false))).thenReturn(emptyPage2);
    when(backendApiClient.get(anyString(), anyClass(), eq(false))).thenReturn(null);
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef(), eq(false)))
        .thenReturn(mission);

    // When / Then: MEMBER sieht den Bearbeiten-Button NICHT für fremde Einträge
    mockMvc
        .perform(
            get("/missions/" + missionId)
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.oidcLogin()
                        .idToken(
                            token ->
                                token
                                    .subject(loggedInUserId.toString())
                                    .claim("preferred_username", "member2"))))
        .andExpect(status().isOk())
        .andExpect(
            // The participant edit action gained `btn-icon`; assert the full rendered class
            // attribute. The bare `edit-participant-btn` marker also appears in the page's inline
            // JS, so `not(contains(marker))` would false-fail even when the button is absent.
            content()
                .string(
                    not(containsString("class=\"btn btn-ghost btn-icon edit-participant-btn\""))));
  }

  // --- Unassigned participants AJAX endpoint ------------------------------

  @Test
  @WithMockUser(roles = "OFFICER")
  void getUnassignedParticipantsAjax_ShouldReturn200WithList() throws Exception {
    // Given
    UUID missionId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();
    Map<String, Object> participant = new java.util.HashMap<>();
    participant.put("id", participantId.toString());
    participant.put("guestName", "Alice");
    List<Map<String, Object>> response = List.of(participant);

    when(backendApiClient.get(
            eq("/api/v1/missions/" + missionId + "/participants/unassigned"),
            anyTypeRef(),
            eq(false)))
        .thenReturn(response);

    // When / Then
    mockMvc
        .perform(get("/missions/" + missionId + "/participants/unassigned/ajax"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(participantId.toString())));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void getUnassignedParticipantsAjax_WithBackendError_ShouldPropagateStatus() throws Exception {
    // Given
    UUID missionId = UUID.randomUUID();
    when(backendApiClient.get(
            eq("/api/v1/missions/" + missionId + "/participants/unassigned"),
            anyTypeRef(),
            eq(false)))
        .thenThrow(
            new de.greluc.krt.profit.basetool.frontend.service.BackendServiceException(
                "Not Found", null, 404));

    // When / Then
    mockMvc
        .perform(get("/missions/" + missionId + "/participants/unassigned/ajax"))
        .andExpect(status().isNotFound());
  }

  // --- Unit ship picker is restricted to ships of registered participants ----

  @Test
  void missionDetail_UnitShipPicker_OnlyOffersShipsOfRegisteredParticipants() throws Exception {
    // Given: one registered (account-backed) participant who owns a ship, plus an outsider who
    // owns another ship. Both ships are in `allShips`, but only the participant's may be offered.
    UUID missionId = UUID.randomUUID();
    UUID participantUserId = UUID.randomUUID();
    UUID outsiderUserId = UUID.randomUUID();
    UUID participantShipId = UUID.randomUUID();
    UUID outsiderShipId = UUID.randomUUID();
    UUID shipTypeId = UUID.randomUUID();

    de.greluc.krt.profit.basetool.frontend.model.dto.UserDto participantUser =
        new de.greluc.krt.profit.basetool.frontend.model.dto.UserDto(
            participantUserId,
            "pilot1",
            "Pilot One",
            "Pilot One",
            "pilot@example.com",
            1,
            null,
            java.util.Set.of("KRT_MEMBER"),
            java.util.Set.of(),
            null,
            false,
            false,
            true,
            null,
            java.util.List.of(),
            1L,
            null,
            false);
    de.greluc.krt.profit.basetool.frontend.model.dto.UserDto outsiderUser =
        new de.greluc.krt.profit.basetool.frontend.model.dto.UserDto(
            outsiderUserId,
            "outsider1",
            "Out Sider",
            "Out Sider",
            "outsider@example.com",
            1,
            null,
            java.util.Set.of("KRT_MEMBER"),
            java.util.Set.of(),
            null,
            false,
            false,
            true,
            null,
            java.util.List.of(),
            1L,
            null,
            false);

    de.greluc.krt.profit.basetool.frontend.model.dto.MissionParticipantDto participant =
        new de.greluc.krt.profit.basetool.frontend.model.dto.MissionParticipantDto(
            UUID.randomUUID(),
            participantUser,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            de.greluc.krt.profit.basetool.frontend.model.PayoutPreference.PAYOUT,
            1L,
            null);

    de.greluc.krt.profit.basetool.frontend.model.dto.ShipTypeDto shipType =
        new de.greluc.krt.profit.basetool.frontend.model.dto.ShipTypeDto(
            shipTypeId, "Fighter", null, null, null, false);
    de.greluc.krt.profit.basetool.frontend.model.dto.ShipDto participantShip =
        new de.greluc.krt.profit.basetool.frontend.model.dto.ShipDto(
            participantShipId, "P-Ship", shipType, "10", null, false, participantUser, null, 1L);
    de.greluc.krt.profit.basetool.frontend.model.dto.ShipDto outsiderShip =
        new de.greluc.krt.profit.basetool.frontend.model.dto.ShipDto(
            outsiderShipId, "O-Ship", shipType, "10", null, false, outsiderUser, null, 1L);

    MissionDto mission =
        new MissionDto(
            missionId,
            "Test Mission",
            null,
            null,
            "PLANNED",
            null,
            null,
            null,
            null,
            null,
            false,
            java.util.Set.of(participant),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptySet(),
            Collections.emptyList(),
            Collections.emptyList(),
            null,
            null,
            Collections.emptySet(),
            true, // canEdit -> unit add/edit modals (and their ship pickers) render
            true,
            1L,
            1L,
            1L,
            1L,
            1,
            1,
            null,
            null,
            null,
            null,
            0L,
            java.util.List.of(),
            0L,
            java.util.List.of(),
            0L,
            null);

    de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse<Object> emptyPage =
        new de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse<>(
            Collections.emptyList(), 0, 0, 0, 0, Collections.emptyList());
    when(backendApiClient.getCached(
            anyString(), anyTypeRef(), org.mockito.ArgumentMatchers.anyBoolean()))
        .thenReturn(emptyPage);
    when(backendApiClient.getCached(anyString(), anyTypeRef())).thenReturn(emptyPage);
    when(backendApiClient.get(anyString(), anyTypeRef(), eq(false))).thenReturn(emptyPage);
    when(backendApiClient.get(anyString(), anyClass(), eq(false))).thenReturn(null);
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef(), eq(false)))
        .thenReturn(mission);
    // Unit ship pickers are populated from the mission-scoped endpoint; specific stub AFTER the
    // generic get(...) so it wins for this URL.
    when(backendApiClient.get(
            eq("/api/v1/missions/" + missionId + "/unit-ship-options"), anyTypeRef(), eq(false)))
        .thenReturn(List.of(participantShip, outsiderShip));

    // When / Then: the rendered ship pickers offer the participant's ship but not the outsider's.
    mockMvc
        .perform(
            get("/missions/" + missionId)
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.oidcLogin()
                        .idToken(
                            token ->
                                token
                                    .subject(participantUserId.toString())
                                    .claim("preferred_username", "pilot1"))))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(participantShipId.toString())))
        .andExpect(content().string(not(containsString(outsiderShipId.toString()))));
  }

  @Test
  void missionDetail_UnitShipPicker_KeepsAlreadyAssignedShipEvenIfOwnerNotParticipant()
      throws Exception {
    // Given: a unit already holds a ship whose owner is NOT (or no longer) a participant. The edit
    // picker must keep offering that ship so editing the unit doesn't silently drop it. A stray
    // non-participant ship that is not assigned anywhere must still be excluded.
    UUID missionId = UUID.randomUUID();
    UUID participantUserId = UUID.randomUUID();
    UUID outsiderUserId = UUID.randomUUID();
    UUID participantShipId = UUID.randomUUID();
    UUID assignedShipId = UUID.randomUUID();
    UUID strayShipId = UUID.randomUUID();
    UUID shipTypeId = UUID.randomUUID();

    de.greluc.krt.profit.basetool.frontend.model.dto.UserDto participantUser =
        new de.greluc.krt.profit.basetool.frontend.model.dto.UserDto(
            participantUserId,
            "pilot1",
            "Pilot One",
            "Pilot One",
            "pilot@example.com",
            1,
            null,
            java.util.Set.of("KRT_MEMBER"),
            java.util.Set.of(),
            null,
            false,
            false,
            true,
            null,
            java.util.List.of(),
            1L,
            null,
            false);
    de.greluc.krt.profit.basetool.frontend.model.dto.UserDto outsiderUser =
        new de.greluc.krt.profit.basetool.frontend.model.dto.UserDto(
            outsiderUserId,
            "outsider1",
            "Out Sider",
            "Out Sider",
            "outsider@example.com",
            1,
            null,
            java.util.Set.of("KRT_MEMBER"),
            java.util.Set.of(),
            null,
            false,
            false,
            true,
            null,
            java.util.List.of(),
            1L,
            null,
            false);

    de.greluc.krt.profit.basetool.frontend.model.dto.MissionParticipantDto participant =
        new de.greluc.krt.profit.basetool.frontend.model.dto.MissionParticipantDto(
            UUID.randomUUID(),
            participantUser,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            de.greluc.krt.profit.basetool.frontend.model.PayoutPreference.PAYOUT,
            1L,
            null);

    de.greluc.krt.profit.basetool.frontend.model.dto.ShipTypeDto shipType =
        new de.greluc.krt.profit.basetool.frontend.model.dto.ShipTypeDto(
            shipTypeId, "Fighter", null, null, null, false);
    de.greluc.krt.profit.basetool.frontend.model.dto.ShipDto participantShip =
        new de.greluc.krt.profit.basetool.frontend.model.dto.ShipDto(
            participantShipId, "P-Ship", shipType, "10", null, false, participantUser, null, 1L);
    de.greluc.krt.profit.basetool.frontend.model.dto.ShipDto assignedShip =
        new de.greluc.krt.profit.basetool.frontend.model.dto.ShipDto(
            assignedShipId, "A-Ship", shipType, "10", null, false, outsiderUser, null, 1L);
    de.greluc.krt.profit.basetool.frontend.model.dto.ShipDto strayShip =
        new de.greluc.krt.profit.basetool.frontend.model.dto.ShipDto(
            strayShipId, "S-Ship", shipType, "10", null, false, outsiderUser, null, 1L);

    de.greluc.krt.profit.basetool.frontend.model.dto.MissionUnitDto assignedUnit =
        new de.greluc.krt.profit.basetool.frontend.model.dto.MissionUnitDto(
            UUID.randomUUID(),
            "Alpha",
            shipType,
            assignedShip,
            123.45,
            false,
            null,
            null,
            Collections.emptyList());

    MissionDto mission =
        new MissionDto(
            missionId,
            "Test Mission",
            null,
            null,
            "PLANNED",
            null,
            null,
            null,
            null,
            null,
            false,
            java.util.Set.of(participant),
            List.of(assignedUnit),
            Collections.emptyList(),
            Collections.emptySet(),
            Collections.emptyList(),
            Collections.emptyList(),
            null,
            null,
            Collections.emptySet(),
            true,
            true,
            1L,
            1L,
            1L,
            1L,
            1,
            1,
            null,
            null,
            null,
            null,
            0L,
            java.util.List.of(),
            0L,
            java.util.List.of(),
            0L,
            null);

    de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse<Object> emptyPage =
        new de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse<>(
            Collections.emptyList(), 0, 0, 0, 0, Collections.emptyList());
    when(backendApiClient.getCached(
            anyString(), anyTypeRef(), org.mockito.ArgumentMatchers.anyBoolean()))
        .thenReturn(emptyPage);
    when(backendApiClient.getCached(anyString(), anyTypeRef())).thenReturn(emptyPage);
    when(backendApiClient.get(anyString(), anyTypeRef(), eq(false))).thenReturn(emptyPage);
    when(backendApiClient.get(anyString(), anyClass(), eq(false))).thenReturn(null);
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef(), eq(false)))
        .thenReturn(mission);
    // The endpoint returns participant ships plus already-assigned ships; the stray ship is neither
    // and must be filtered out by the template. Specific stub AFTER the generic get(...).
    when(backendApiClient.get(
            eq("/api/v1/missions/" + missionId + "/unit-ship-options"), anyTypeRef(), eq(false)))
        .thenReturn(List.of(participantShip, assignedShip, strayShip));

    // When / Then: the assigned ship is still selectable as an <option value="..."> (so the
    // client-side edit picker can pre-select it), while the unassigned stray ship is excluded.
    mockMvc
        .perform(
            get("/missions/" + missionId)
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.oidcLogin()
                        .idToken(
                            token ->
                                token
                                    .subject(participantUserId.toString())
                                    .claim("preferred_username", "pilot1"))))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("value=\"" + participantShipId + "\"")))
        .andExpect(content().string(containsString("value=\"" + assignedShipId + "\"")))
        .andExpect(content().string(not(containsString("value=\"" + strayShipId + "\""))));
  }

  /**
   * #816: the Übersicht "Funk" panel must list both the central, unit-less frequencies that carry a
   * value and the per-unit frequencies of units that have one, while omitting empty entries.
   *
   * <p>Given a mission with one central frequency type that has a value ("Befehl"), one that has
   * none ("Notfall"), one unit with a frequency ("Alpha") and one without ("Bravo"), the overview
   * fragment must render the two entries that carry a value and neither empty one. The overview
   * fragment is requested directly (`?fragment=overview`) so the assertions are scoped to the
   * overview pane and never pick up the unit names from the crew board.
   */
  @Test
  void missionOverviewFragment_ListsFrequenciesWithValue_OmitsEmptyOnes() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID befehlTypeId = UUID.randomUUID();
    UUID notfallTypeId = UUID.randomUUID();

    // Central frequency: only "Befehl" carries a value; "Notfall" is an active type with none.
    de.greluc.krt.profit.basetool.frontend.model.dto.MissionFrequencyDto befehlFrequency =
        new de.greluc.krt.profit.basetool.frontend.model.dto.MissionFrequencyDto(
            UUID.randomUUID(),
            new de.greluc.krt.profit.basetool.frontend.model.dto.MissionFrequencyDto
                .FrequencyTypeRef(befehlTypeId, "Befehl"),
            null,
            new java.math.BigDecimal("121.50"),
            1L);

    // "Alpha" carries a frequency, "Bravo" does not -> only Alpha appears in the Funk panel.
    de.greluc.krt.profit.basetool.frontend.model.dto.MissionUnitDto alphaUnit =
        new de.greluc.krt.profit.basetool.frontend.model.dto.MissionUnitDto(
            UUID.randomUUID(),
            "Alpha",
            null,
            null,
            243.75,
            false,
            null,
            null,
            Collections.emptyList());
    de.greluc.krt.profit.basetool.frontend.model.dto.MissionUnitDto bravoUnit =
        new de.greluc.krt.profit.basetool.frontend.model.dto.MissionUnitDto(
            UUID.randomUUID(),
            "Bravo",
            null,
            null,
            null,
            false,
            null,
            null,
            Collections.emptyList());

    MissionDto mission =
        new MissionDto(
            missionId,
            "Freq Mission",
            null,
            null,
            "PLANNED",
            null,
            null,
            null,
            null,
            null,
            false,
            Collections.emptySet(),
            List.of(alphaUnit, bravoUnit),
            List.of(befehlFrequency),
            Collections.emptySet(),
            Collections.emptyList(),
            Collections.emptyList(),
            null,
            null,
            Collections.emptySet(),
            true,
            true,
            1L,
            1L,
            1L,
            1L,
            0,
            0,
            null,
            null,
            null,
            null,
            0L,
            java.util.List.of(),
            0L,
            java.util.List.of(),
            0L,
            null);

    de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse<Object> emptyPage =
        new de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse<>(
            Collections.emptyList(), 0, 0, 0, 0, Collections.emptyList());
    de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse<Map<String, Object>>
        freqTypesPage =
            new de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse<>(
                List.of(
                    Map.of("id", befehlTypeId.toString(), "name", "Befehl"),
                    Map.of("id", notfallTypeId.toString(), "name", "Notfall")),
                0,
                2,
                2,
                1,
                Collections.emptyList());

    when(backendApiClient.getCached(
            anyString(), anyTypeRef(), org.mockito.ArgumentMatchers.anyBoolean()))
        .thenReturn(emptyPage);
    when(backendApiClient.getCached(anyString(), anyTypeRef())).thenReturn(emptyPage);
    when(backendApiClient.get(anyString(), anyTypeRef(), eq(false))).thenReturn(emptyPage);
    when(backendApiClient.get(anyString(), anyClass(), eq(false))).thenReturn(null);
    // The active frequency types feed the central rows; specific stub AFTER the generic getCached.
    when(backendApiClient.getCached(
            eq("/api/v1/frequency-types?size=1000&active=true&sort=sortIndex,asc"),
            anyTypeRef(),
            eq(true)))
        .thenReturn(freqTypesPage);
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef(), eq(false)))
        .thenReturn(mission);
    // canEdit=true -> the unit ship-option picker is fetched; return a List (not the PageResponse
    // the
    // generic stub yields) so the controller's List<ShipDto> assignment does not ClassCast.
    when(backendApiClient.get(
            eq("/api/v1/missions/" + missionId + "/unit-ship-options"), anyTypeRef(), eq(false)))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(
            get("/missions/" + missionId)
                .param("fragment", "overview")
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.oidcLogin()
                        .idToken(
                            token ->
                                token
                                    .subject(UUID.randomUUID().toString())
                                    .claim("preferred_username", "viewer1"))))
        .andExpect(status().isOk())
        // Central frequency with a value is shown; the value-less central type is omitted.
        .andExpect(content().string(containsString("Befehl")))
        .andExpect(content().string(containsString("121.50")))
        .andExpect(content().string(not(containsString("Notfall"))))
        // The unit that carries a frequency is shown; the one without is omitted.
        .andExpect(content().string(containsString("Alpha")))
        .andExpect(content().string(containsString("243.75")))
        .andExpect(content().string(not(containsString("Bravo"))));
  }

  /**
   * #816 follow-up (review finding): the "Funk" panel must collapse — not paint a bare heading over
   * an empty list — when the frequency-types lookup fails (a Resilience4j-wrapped fetch whose
   * failure the controller swallows, leaving the {@code frequencyTypes} attribute null) while the
   * mission still carries a stored central frequency value and no unit has a frequency.
   *
   * <p>The central rows iterate {@code frequencyTypes}; the panel gate therefore AND-s the
   * central-value flag with {@code hasCentralTypes} so it stays hidden when the type list is
   * absent.
   */
  @Test
  void missionOverviewFragment_FreqTypesFetchFailed_CollapsesFunkPanel() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID befehlTypeId = UUID.randomUUID();

    de.greluc.krt.profit.basetool.frontend.model.dto.MissionFrequencyDto befehlFrequency =
        new de.greluc.krt.profit.basetool.frontend.model.dto.MissionFrequencyDto(
            UUID.randomUUID(),
            new de.greluc.krt.profit.basetool.frontend.model.dto.MissionFrequencyDto
                .FrequencyTypeRef(befehlTypeId, "Befehl"),
            null,
            new java.math.BigDecimal("121.50"),
            1L);

    MissionDto mission =
        new MissionDto(
            missionId,
            "Freq Mission",
            null,
            null,
            "PLANNED",
            null,
            null,
            null,
            null,
            null,
            false,
            Collections.emptySet(),
            Collections.emptyList(),
            List.of(befehlFrequency),
            Collections.emptySet(),
            Collections.emptyList(),
            Collections.emptyList(),
            null,
            null,
            Collections.emptySet(),
            true,
            true,
            1L,
            1L,
            1L,
            1L,
            0,
            0,
            null,
            null,
            null,
            null,
            0L,
            java.util.List.of(),
            0L,
            java.util.List.of(),
            0L,
            null);

    de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse<Object> emptyPage =
        new de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse<>(
            Collections.emptyList(), 0, 0, 0, 0, Collections.emptyList());
    when(backendApiClient.getCached(
            anyString(), anyTypeRef(), org.mockito.ArgumentMatchers.anyBoolean()))
        .thenReturn(emptyPage);
    when(backendApiClient.getCached(anyString(), anyTypeRef())).thenReturn(emptyPage);
    when(backendApiClient.get(anyString(), anyTypeRef(), eq(false))).thenReturn(emptyPage);
    when(backendApiClient.get(anyString(), anyClass(), eq(false))).thenReturn(null);
    // The frequency-types fetch fails -> the controller swallows it and never sets frequencyTypes.
    when(backendApiClient.getCached(
            eq("/api/v1/frequency-types?size=1000&active=true&sort=sortIndex,asc"),
            anyTypeRef(),
            eq(true)))
        .thenThrow(new RuntimeException("frequency types unavailable"));
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef(), eq(false)))
        .thenReturn(mission);
    when(backendApiClient.get(
            eq("/api/v1/missions/" + missionId + "/unit-ship-options"), anyTypeRef(), eq(false)))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(
            get("/missions/" + missionId)
                .param("fragment", "overview")
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.oidcLogin()
                        .idToken(
                            token ->
                                token
                                    .subject(UUID.randomUUID().toString())
                                    .claim("preferred_username", "viewer1"))))
        .andExpect(status().isOk())
        // The panel collapses entirely: no "Funk" heading and no orphaned central value.
        .andExpect(content().string(not(containsString(">Funk<"))))
        .andExpect(content().string(not(containsString("121.50"))));
  }

  /**
   * Reproducer for the "creating a finance entry 500s the mission detail page" bug (live log:
   * {@code TemplateProcessingException ... mission-detail line 856} right after a finance entry is
   * persisted).
   *
   * <p>The {@code th:each="entry : ${financeEntries}"} loop renders an edit button whose rounded
   * amount used to be produced by {@code th:data-amount="${@moneyFormat.round(entry.amount)}"}.
   * Thymeleaf 3.1 evaluates default/unknown attributes (such as {@code th:data-*}) in a restricted
   * expression context where {@code @bean} references are forbidden, so the call threw and every
   * render of a mission that owned at least one finance entry returned HTTP 500. The fix binds the
   * rounded value via {@code th:with} (an unrestricted context) and only reads the resulting local
   * variable in {@code th:data-amount}.
   *
   * <p>The earlier render tests all pass an empty finance list, so the loop body never executed and
   * the bug slipped through; this test populates exactly one entry. {@code ROLE_OFFICER} is granted
   * via {@code oidcLogin()} because the finance ledger is only fetched for member-or-above OIDC
   * principals.
   */
  @Test
  void missionDetail_WithFinanceEntry_ShouldRenderEditButtonWithoutTemplateError()
      throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID entryId = UUID.randomUUID();

    // 1234.5 exercises the HALF_UP rounding the bean applies (-> 1235), and the raw integer (no
    // thousands separator) is what the edit modal expects in the number input.
    de.greluc.krt.profit.basetool.frontend.model.dto.MissionFinanceEntryDto entry =
        new de.greluc.krt.profit.basetool.frontend.model.dto.MissionFinanceEntryDto(
            entryId,
            missionId,
            null, // no participant -> the "Nutzer" cell renders "-"
            "Salvage income",
            de.greluc.krt.profit.basetool.frontend.model.dto.FinanceType.INCOME,
            new java.math.BigDecimal("1234.5"),
            1L);
    de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse<
            de.greluc.krt.profit.basetool.frontend.model.dto.MissionFinanceEntryDto>
        financesPage =
            new de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse<>(
                List.of(entry), 0, 1, 1, 1, Collections.emptyList());

    MissionDto mission =
        new MissionDto(
            missionId,
            "Finance Mission",
            null,
            null,
            "RUNNING",
            null,
            null,
            null,
            null,
            null,
            false,
            Collections.emptySet(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptySet(),
            Collections.emptyList(),
            Collections.emptyList(),
            null,
            null,
            Collections.emptySet(),
            true,
            true,
            1L,
            1L,
            1L,
            1L,
            0,
            0,
            null,
            null,
            null,
            null,
            0L,
            java.util.List.of(),
            0L,
            java.util.List.of(),
            0L,
            null);

    de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse<Object> emptyPage =
        new de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse<>(
            Collections.emptyList(), 0, 0, 0, 0, Collections.emptyList());
    // Broad stubs first so unrelated detail-page fetches never NPE; specific overrides win below.
    when(backendApiClient.getCached(
            anyString(), anyTypeRef(), org.mockito.ArgumentMatchers.anyBoolean()))
        .thenReturn(emptyPage);
    when(backendApiClient.getCached(anyString(), anyTypeRef())).thenReturn(emptyPage);
    when(backendApiClient.get(anyString(), anyTypeRef(), eq(false))).thenReturn(emptyPage);
    when(backendApiClient.get(anyString(), anyClass(), eq(false))).thenReturn(null);
    // An authenticated OIDC principal fetches the mission with the public flag = false.
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef(), eq(false)))
        .thenReturn(mission);
    when(backendApiClient.get(
            eq("/api/v1/missions/" + missionId + "/finance-entries?size=1000"),
            anyTypeRef(),
            eq(false)))
        .thenReturn(financesPage);
    // Return a real (empty) List for the refinery-orders fetch so it is not assigned the broad
    // PageResponse stub (which would ClassCastException inside the finance try-block).
    when(backendApiClient.get(
            eq("/api/v1/refinery-orders/mission/" + missionId), anyTypeRef(), eq(false)))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(
            get("/missions/" + missionId)
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.oidcLogin()
                        .authorities(
                            new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "ROLE_OFFICER"))
                        .idToken(
                            token ->
                                token
                                    .subject(UUID.randomUUID().toString())
                                    .claim("preferred_username", "officer1"))))
        // Before the fix this threw TemplateProcessingException -> HTTP 500 here.
        .andExpect(status().isOk())
        .andExpect(view().name("mission-detail"))
        // The rounded amount is rendered as a raw integer data attribute (HALF_UP: 1234.5 -> 1235).
        .andExpect(content().string(containsString("data-amount=\"1235\"")));
  }

  /**
   * Renders the crew board with a unit that actually has crew: the earlier render fixtures all use
   * empty crew lists, so the person-row fragment's crew branch (chip-select with the assigned job
   * preselected, the multi-edit entry, and the unit drop-zone wiring) never executed. This test
   * populates one unit with one crew member holding one function and asserts the board markup.
   */
  @Test
  void missionDetail_UnitWithCrew_RendersBoardRowWithChipSelect() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();
    UUID unitId = UUID.randomUUID();
    UUID crewId = UUID.randomUUID();
    UUID jobTypeId = UUID.randomUUID();

    de.greluc.krt.profit.basetool.frontend.model.dto.MissionParticipantDto participant =
        new de.greluc.krt.profit.basetool.frontend.model.dto.MissionParticipantDto(
            participantId,
            null,
            "Crewman",
            null,
            null,
            null,
            null,
            null,
            null,
            de.greluc.krt.profit.basetool.frontend.model.PayoutPreference.PAYOUT,
            1L,
            null);

    de.greluc.krt.profit.basetool.frontend.model.dto.JobTypeDto gunner =
        new de.greluc.krt.profit.basetool.frontend.model.dto.JobTypeDto(
            jobTypeId, "Gunner", null, "CREW", null, true, false, false, 1L);
    de.greluc.krt.profit.basetool.frontend.model.dto.MissionCrewDto crew =
        new de.greluc.krt.profit.basetool.frontend.model.dto.MissionCrewDto(
            crewId, participantId, "Crewman", java.util.Set.of(gunner));
    de.greluc.krt.profit.basetool.frontend.model.dto.MissionUnitDto unit =
        new de.greluc.krt.profit.basetool.frontend.model.dto.MissionUnitDto(
            unitId, "Alpha", null, null, null, true, null, null, List.of(crew));

    MissionDto mission =
        new MissionDto(
            missionId,
            "Board Mission",
            null,
            null,
            "PLANNED",
            null,
            null,
            null,
            null,
            null,
            false,
            java.util.Set.of(participant),
            List.of(unit),
            Collections.emptyList(),
            Collections.emptySet(),
            Collections.emptyList(),
            Collections.emptyList(),
            null,
            null,
            Collections.emptySet(),
            true,
            true,
            1L,
            1L,
            1L,
            1L,
            0,
            1,
            null,
            null,
            null,
            null,
            0L,
            java.util.List.of(),
            0L,
            java.util.List.of(),
            0L,
            null);

    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef(), eq(true)))
        .thenReturn(mission);
    when(backendApiClient.getCached(anyString(), anyTypeRef(), eq(true)))
        .thenReturn(Collections.emptyList());
    // The chip-select options are rendered from the CREW job-type lookup; without this stub the
    // generic emptyList answer above throws on .content() and the options list stays empty.
    de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse<java.util.Map<String, Object>>
        crewJobTypesPage =
            new de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse<>(
                List.of(java.util.Map.of("id", jobTypeId, "name", "Gunner")),
                0,
                1,
                1,
                1,
                Collections.emptyList());
    when(backendApiClient.getCached(
            eq("/api/v1/job-types?archetype=CREW&size=1000"), anyTypeRef(), eq(true)))
        .thenReturn(crewJobTypesPage);

    mockMvc
        .perform(get("/missions/" + missionId))
        .andExpect(status().isOk())
        .andExpect(view().name("mission-detail"))
        // The unit renders as a drop zone carrying its id; the crew member renders as a
        // person row carrying participant + crew ids for the board move handlers.
        .andExpect(content().string(containsString("data-unit-id=\"" + unitId + "\"")))
        .andExpect(
            content().string(containsString("data-participant-id=\"" + participantId + "\"")))
        .andExpect(content().string(containsString("data-crew-id=\"" + crewId + "\"")))
        // On-board function chip-select with the single assigned job preselected and the
        // multi-function edit entry present (canEdit=true in this fixture).
        .andExpect(content().string(containsString("crew-role-select")))
        .andExpect(content().string(containsString("value=\"" + jobTypeId + "\" selected")))
        .andExpect(content().string(containsString("value=\"__edit\"")))
        // HVU marking surfaces as the warning chip on the unit head.
        .andExpect(content().string(containsString("chip--warning")));
  }

  /**
   * The mission description is Markdown: the Uebersicht briefing panel must render it to HTML via
   * the {@code @markdown} bean (bold -> strong) while raw HTML in the source stays escaped — the
   * th:utext sink must never emit user-controlled markup.
   */
  @Test
  @WithMockUser(roles = "OFFICER")
  void missionDetail_DescriptionMarkdown_RendersHtmlAndEscapesRawTags() throws Exception {
    UUID missionId = UUID.randomUUID();

    MissionDto mission =
        new MissionDto(
            missionId,
            "Markdown Mission",
            "**Sammeln** bei ARC-L1 <script>alert('xss')</script>",
            null,
            "PLANNED",
            null,
            null,
            null,
            null,
            null,
            false,
            Collections.emptySet(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptySet(),
            Collections.emptyList(),
            Collections.emptyList(),
            null,
            null,
            Collections.emptySet(),
            true,
            true,
            1L,
            1L,
            1L,
            1L,
            0,
            0,
            null,
            null,
            null,
            null,
            0L,
            java.util.List.of(),
            0L,
            java.util.List.of(),
            0L,
            null);

    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef(), eq(true)))
        .thenReturn(mission);
    when(backendApiClient.getCached(anyString(), anyTypeRef(), eq(true)))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/missions/" + missionId))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("<strong>Sammeln</strong>")))
        .andExpect(content().string(not(containsString("<script>alert"))));
  }

  // --- #574: in-place section fragment branches --------------------------

  /** Minimal editable mission (canEdit + canManageManagers) for the fragment-render assertions. */
  private static MissionDto editableMission(UUID id) {
    return new MissionDto(
        id,
        "Frag Mission",
        null,
        null,
        "PLANNED",
        null,
        null,
        null,
        null,
        null,
        false,
        Collections.emptySet(),
        Collections.emptyList(),
        Collections.emptyList(),
        Collections.emptySet(),
        Collections.emptyList(),
        Collections.emptyList(),
        null,
        null,
        Collections.emptySet(),
        true,
        true,
        1L,
        1L,
        1L,
        1L,
        0,
        0,
        null,
        null,
        null,
        null,
        0L,
        java.util.List.of(),
        0L,
        java.util.List.of(),
        0L,
        null);
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void missionDetail_CrewBoardFragment_RendersBoardOnly() throws Exception {
    UUID missionId = UUID.randomUUID();
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef(), eq(true)))
        .thenReturn(editableMission(missionId));
    when(backendApiClient.getCached(anyString(), anyTypeRef(), eq(true)))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/missions/" + missionId).param("fragment", "crew-board"))
        .andExpect(status().isOk())
        .andExpect(view().name("mission-detail :: crewBoard"))
        .andExpect(content().string(containsString("id=\"board-pool\"")))
        // The fragment must NOT carry the page chrome (sticky head / tab nav / sibling panes).
        .andExpect(content().string(not(containsString("mission-head-sticky"))))
        .andExpect(content().string(not(containsString("id=\"pane-fin\""))));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void missionDetail_FinanceFragment_RendersFinancePaneOnly() throws Exception {
    UUID missionId = UUID.randomUUID();
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef(), eq(true)))
        .thenReturn(editableMission(missionId));
    when(backendApiClient.getCached(anyString(), anyTypeRef(), eq(true)))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/missions/" + missionId).param("fragment", "finance"))
        .andExpect(status().isOk())
        .andExpect(view().name("mission-detail :: financeSection"))
        .andExpect(content().string(containsString("id=\"finance-count-meta\"")))
        .andExpect(content().string(not(containsString("mission-head-sticky"))))
        .andExpect(content().string(not(containsString("id=\"pane-crew\""))));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void missionDetail_MgmtFragment_RendersManagementPanelOnly() throws Exception {
    UUID missionId = UUID.randomUUID();
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef(), eq(true)))
        .thenReturn(editableMission(missionId));
    when(backendApiClient.getCached(anyString(), anyTypeRef(), eq(true)))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/missions/" + missionId).param("fragment", "mgmt"))
        .andExpect(status().isOk())
        .andExpect(view().name("mission-detail :: mgmtPanels"))
        .andExpect(content().string(containsString("new-manager-id")))
        .andExpect(content().string(not(containsString("mission-head-sticky"))))
        .andExpect(content().string(not(containsString("id=\"board-pool\""))));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void missionDetail_FragmentBackendError_RendersInlineErrorFragmentNotRedirect() throws Exception {
    UUID missionId = UUID.randomUUID();
    // The backend read fails mid-swap (circuit-breaker open / timeout / 5xx). The fragment path
    // must answer with a section-sized inline error fragment (HTTP 200), never the classic
    // redirect:/missions — krtFetch.swap would otherwise follow the 302 and paint the whole
    // missions page into the small #crew-board-results container (#574 review must-fix).
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef(), eq(true)))
        .thenThrow(new RuntimeException("backend unavailable"));

    mockMvc
        .perform(get("/missions/" + missionId).param("fragment", "crew-board"))
        .andExpect(status().isOk())
        .andExpect(view().name("mission-detail :: fragmentError"))
        .andExpect(content().string(containsString("role=\"alert\"")))
        // Section-sized: no page chrome, no full board markup.
        .andExpect(content().string(not(containsString("mission-head-sticky"))))
        .andExpect(content().string(not(containsString("id=\"board-pool\""))));
  }

  // --- #574: party-lead AJAX endpoint ------------------------------------

  @Test
  @WithMockUser(roles = "OFFICER")
  void setPartyLeadAjax_Success_ReturnsRefreshedMission() throws Exception {
    UUID missionId = UUID.randomUUID();
    when(backendApiClient.put(
            eq("/api/v1/missions/" + missionId + "/party-lead"), any(), eq(Void.class), eq(false)))
        .thenReturn(null);
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef(), eq(false)))
        .thenReturn(editableMission(missionId));

    String body = "{\"guestName\":\"Lead Guy\",\"version\":0}";
    mockMvc
        .perform(
            put("/missions/" + missionId + "/party-lead/ajax")
                .with(csrf())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isOk());
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void setPartyLeadAjax_BackendConflict_Returns409() throws Exception {
    UUID missionId = UUID.randomUUID();
    when(backendApiClient.put(
            eq("/api/v1/missions/" + missionId + "/party-lead"), any(), eq(Void.class), eq(false)))
        .thenThrow(
            new de.greluc.krt.profit.basetool.frontend.service.BackendServiceException(
                "Conflict", null, 409));

    String body = "{\"guestName\":\"Ambiguous\",\"version\":0}";
    mockMvc
        .perform(
            put("/missions/" + missionId + "/party-lead/ajax")
                .with(csrf())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isConflict());
  }
}
