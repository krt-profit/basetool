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
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import de.greluc.krt.profit.basetool.frontend.model.dto.MissionFinanceTotalsDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.CachedCatalog;
import java.io.IOException;
import java.math.BigDecimal;
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
    UUID missionId = UUID.randomUUID();
    when(backendApiClient.post(any(String.class), any(), Mockito.eq(MissionDto.class)))
        .thenReturn(minimalMission(missionId));

    mockMvc
        .perform(
            post("/missions")
                .param("name", "Test Mission")
                .param("description", "")
                .param("status", "PLANNED")
                .param("plannedStartTime", "2026-02-10T10:00")
                .param("plannedEndTime", "2026-02-10T12:00")
                .with(csrf()))
        .andExpect(status().is3xxRedirection())
        .andDo(org.springframework.test.web.servlet.result.MockMvcResultHandlers.print())
        .andExpect(redirectedUrl("/missions/" + missionId + "?tab=verw"));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void createMission_parsesTheGoalAndStepCarriersIntoTheCreateRequest() throws Exception {
    UUID missionId = UUID.randomUUID();
    when(backendApiClient.post(any(String.class), any(), Mockito.eq(MissionDto.class)))
        .thenReturn(minimalMission(missionId));

    mockMvc
        .perform(
            post("/missions")
                .param("name", "Test Mission")
                .param("status", "PLANNED")
                .param("plannedStartTime", "2026-02-10T10:00")
                .param("plannedEndTime", "2026-02-10T12:00")
                .param("objectivesJson", "[{\"title\":\"Erz\",\"kind\":\"PRIMARY\"}]")
                .param("stepsJson", "[{\"title\":\"Sammeln\",\"meta\":\"20:00\"}]")
                .with(csrf()))
        .andExpect(redirectedUrl("/missions/" + missionId + "?tab=verw"));

    org.mockito.ArgumentCaptor<
            de.greluc.krt.profit.basetool.frontend.model.dto.CreateMissionRequest>
        sent =
            org.mockito.ArgumentCaptor.forClass(
                de.greluc.krt.profit.basetool.frontend.model.dto.CreateMissionRequest.class);
    verify(backendApiClient).post(eq("/api/v1/missions"), sent.capture(), eq(MissionDto.class));
    assertThat(sent.getValue().objectives())
        .containsExactly(
            new de.greluc.krt.profit.basetool.frontend.model.dto.CreateMissionRequest.NewObjective(
                "Erz", "PRIMARY"));
    assertThat(sent.getValue().steps())
        .containsExactly(
            new de.greluc.krt.profit.basetool.frontend.model.dto.CreateMissionRequest.NewStep(
                "Sammeln", "20:00"));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void createMission_aCarrierWithAnUnknownKeyFailsInsteadOfDroppingTheValue() throws Exception {
    mockMvc
        .perform(
            post("/missions")
                .param("name", "Test Mission")
                .param("status", "PLANNED")
                .param("plannedStartTime", "2026-02-10T10:00")
                .param("plannedEndTime", "2026-02-10T12:00")
                .param("objectivesJson", "[{\"titel\":\"Erz\",\"kind\":\"PRIMARY\"}]")
                .with(csrf()))
        .andExpect(redirectedUrl("/missions/new"));
    verify(backendApiClient, never()).post(eq("/api/v1/missions"), any(), eq(MissionDto.class));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void missionDetail_ShouldRenderWithoutErrors() throws Exception {
    UUID missionId = UUID.randomUUID();

    MissionDto mission = minimalMission(missionId);

    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef()))
        .thenReturn(mission);
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());
    stubEmptyFinance(missionId);

    mockMvc
        .perform(get("/missions/" + missionId))
        .andExpect(status().isOk())
        .andExpect(view().name("mission-detail"))
        .andExpect(content().string(containsString("mission-head-sticky")))
        .andExpect(content().string(containsString("facts-bar")))
        .andExpect(content().string(containsString("role=\"tablist\"")))
        .andExpect(content().string(containsString("id=\"pane-ueb\"")))
        .andExpect(content().string(containsString("id=\"pane-crew\"")))
        .andExpect(content().string(containsString("id=\"pane-fin\"")))
        .andExpect(content().string(containsString("id=\"pane-verw\"")))
        .andExpect(content().string(containsString("role=\"tabpanel\"")))
        .andExpect(content().string(containsString("id=\"board-pool\"")))
        .andExpect(content().string(not(containsString("mission-columns-container"))))
        .andExpect(content().string(not(containsString("vertical-title"))));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void missionDetail_asMember_fetchesFinanceLedger() throws Exception {
    UUID missionId = UUID.randomUUID();
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef()))
        .thenReturn(minimalMission(missionId));
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());
    stubEmptyFinance(missionId);

    mockMvc.perform(get("/missions/" + missionId)).andExpect(status().isOk());

    verify(backendApiClient)
        .get(
            eq("/api/v1/missions/" + missionId + "/finance-entries/summary"),
            eq(MissionFinanceTotalsDto.class));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void missionDetail_rendersAblaufChecklistAndEditor_whenStepsPresent() throws Exception {
    UUID missionId = UUID.randomUUID();
    var step1 =
        new de.greluc.krt.profit.basetool.frontend.model.dto.MissionStepDto(
            UUID.randomUUID(), "Briefing", "TS 19:30", true, 0);
    var step2 =
        new de.greluc.krt.profit.basetool.frontend.model.dto.MissionStepDto(
            UUID.randomUUID(), "Mining", null, false, 1);

    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef()))
        .thenReturn(missionWithSteps(missionId, java.util.List.of(step1, step2)));
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());
    stubEmptyFinance(missionId);

    mockMvc
        .perform(get("/missions/" + missionId))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("class=\"ablauf\"")))
        .andExpect(content().string(containsString("Briefing")))
        .andExpect(content().string(containsString("Mining")))
        .andExpect(content().string(containsString("step--done")))
        .andExpect(content().string(containsString("step--now")))
        .andExpect(content().string(containsString("data-trigger=\"mission-toggle-step\"")))
        .andExpect(content().string(containsString("Mission auf einen Blick")))
        .andExpect(content().string(containsString("class=\"ziele\"")))
        .andExpect(content().string(containsString("Janalite sammeln")))
        .andExpect(content().string(containsString("id=\"mission-step-list\"")))
        .andExpect(content().string(containsString("id=\"mission-objective-list\"")))
        .andExpect(content().string(containsString("name=\"meetingPoint\"")));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void missionDetail_omitsEmptyGoalAndProcedureTiles_andOpensDescription() throws Exception {
    UUID missionId = UUID.randomUUID();
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef()))
        .thenReturn(minimalMission(missionId, "**Briefing** folgt."));
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());
    stubEmptyFinance(missionId);

    mockMvc
        .perform(get("/missions/" + missionId))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("class=\"ablauf\""))))
        .andExpect(content().string(not(containsString("class=\"ziele\""))))
        .andExpect(content().string(not(containsString("Noch keine Ziele"))))
        .andExpect(content().string(not(containsString("Noch keine Schritte"))))
        .andExpect(content().string(containsString("id=\"mission-step-list\"")))
        .andExpect(content().string(containsString("id=\"mission-objective-list\"")))
        .andExpect(content().string(containsString("<details class=\"more\" open")));
  }

  /**
   * Builds an editable {@link MissionDto} with the given Ablauf steps, one goal and a meeting
   * point, so the step, goal and at-a-glance sections render (REQ-MISSION-009/-019).
   *
   * @param missionId the id to stamp on the mission
   * @param steps the Ablauf steps to render
   * @return a mission fixture with steps, one goal and a meeting point
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
        "ARC-L1",
        null);
  }

  /**
   * Builds a minimal editable {@link MissionDto} with empty collections, one manager and no
   * description.
   *
   * @param missionId the id to stamp on the mission
   * @return a minimal mission fixture with no description
   */
  private MissionDto minimalMission(UUID missionId) {
    return minimalMission(missionId, null);
  }

  /**
   * Builds a minimal {@link MissionDto} with the given Markdown description and empty goals and
   * steps.
   *
   * @param missionId the id to stamp on the mission
   * @param description the Markdown description, or {@code null} for none
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
        null,
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
            eq("/api/v1/missions/" + missionId + "/finance-entries/summary"),
            eq(MissionFinanceTotalsDto.class)))
        .thenReturn(
            new MissionFinanceTotalsDto(BigDecimal.ZERO, BigDecimal.ZERO, 0L, BigDecimal.ZERO, 0L));
    when(backendApiClient.get(
            eq("/api/v1/missions/" + missionId + "/finance-entries?size=200"), anyTypeRef()))
        .thenReturn(
            new PageResponse<>(Collections.emptyList(), 0, 200, 0, 0, Collections.emptyList()));
  }

  /**
   * Builds an editable {@link MissionDto} with the given participants and assigned units so the
   * crew board renders; all other collections are empty.
   *
   * @param missionId the id to stamp on the mission
   * @param participants the mission participants (source of {@code participantsById})
   * @param units the assigned units whose crew rows the board renders
   * @return a mission fixture with the given participants and units
   */
  private MissionDto missionWithUnitsAndParticipants(
      UUID missionId,
      java.util.Set<de.greluc.krt.profit.basetool.frontend.model.dto.MissionParticipantDto>
          participants,
      java.util.List<de.greluc.krt.profit.basetool.frontend.model.dto.MissionUnitDto> units) {
    de.greluc.krt.profit.basetool.frontend.model.dto.UserReferenceDto manager =
        new de.greluc.krt.profit.basetool.frontend.model.dto.UserReferenceDto(
            UUID.randomUUID(), "manager", null, "Test Manager", 0);
    return new MissionDto(
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
        participants,
        units,
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
        null,
        null);
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void missionDetail_crewWithUnresolvableParticipant_SuppressesGhostRow() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID realParticipantId = UUID.randomUUID();
    UUID missingParticipantId = UUID.randomUUID();
    UUID realCrewId = UUID.randomUUID();
    UUID ghostCrewId = UUID.randomUUID();

    de.greluc.krt.profit.basetool.frontend.model.dto.MissionParticipantDto realParticipant =
        new de.greluc.krt.profit.basetool.frontend.model.dto.MissionParticipantDto(
            realParticipantId,
            null,
            "Real Crew",
            null,
            null,
            null,
            null,
            null,
            null,
            de.greluc.krt.profit.basetool.frontend.model.PayoutPreference.PAYOUT,
            1L);
    de.greluc.krt.profit.basetool.frontend.model.dto.MissionCrewDto realCrew =
        new de.greluc.krt.profit.basetool.frontend.model.dto.MissionCrewDto(
            realCrewId, realParticipantId, "Real Crew", null, null);
    de.greluc.krt.profit.basetool.frontend.model.dto.MissionCrewDto ghostCrew =
        new de.greluc.krt.profit.basetool.frontend.model.dto.MissionCrewDto(
            ghostCrewId, missingParticipantId, "Ghost", null, null);
    de.greluc.krt.profit.basetool.frontend.model.dto.MissionUnitDto unit =
        new de.greluc.krt.profit.basetool.frontend.model.dto.MissionUnitDto(
            UUID.randomUUID(),
            "Alpha Unit",
            null,
            null,
            null,
            false,
            null,
            null,
            null,
            java.util.List.of(realCrew, ghostCrew));

    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef()))
        .thenReturn(
            missionWithUnitsAndParticipants(
                missionId, java.util.Set.of(realParticipant), java.util.List.of(unit)));
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());
    stubEmptyFinance(missionId);

    String html =
        mockMvc
            .perform(get("/missions/" + missionId))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html).as("crew board unit rendered").contains("Alpha Unit");
    assertThat(html)
        .as("resolvable crew member renders a person-row")
        .contains("data-crew-id=\"" + realCrewId + "\"");
    assertThat(html)
        .as("crew entry with an unresolvable participant renders no ghost person-row")
        .doesNotContain("data-crew-id=\"" + ghostCrewId + "\"");
  }

  /**
   * Verifies that each crew-board drop zone renders an idle hint and an armed hint describing the
   * click path, with its {@code aria-label} matching the idle text, in German and English.
   *
   * @throws Exception if the MockMvc exchange fails
   */
  @Test
  @WithMockUser(roles = "OFFICER")
  void missionDetail_CrewBoardZones_AdvertiseTheClickPathNotOnlyDrag() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();

    de.greluc.krt.profit.basetool.frontend.model.dto.MissionParticipantDto participant =
        new de.greluc.krt.profit.basetool.frontend.model.dto.MissionParticipantDto(
            participantId,
            null,
            "Pool Person",
            null,
            null,
            null,
            null,
            null,
            null,
            de.greluc.krt.profit.basetool.frontend.model.PayoutPreference.PAYOUT,
            1L);
    de.greluc.krt.profit.basetool.frontend.model.dto.MissionUnitDto unit =
        new de.greluc.krt.profit.basetool.frontend.model.dto.MissionUnitDto(
            UUID.randomUUID(),
            "Alpha Unit",
            null,
            null,
            null,
            false,
            null,
            null,
            null,
            java.util.List.of());

    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef()))
        .thenReturn(
            missionWithUnitsAndParticipants(
                missionId, java.util.Set.of(participant), java.util.List.of(unit)));
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());
    stubEmptyFinance(missionId);

    String de =
        mockMvc
            .perform(get("/missions/" + missionId))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(de).as("idle hint rendered").contains("class=\"hint-idle\"");
    assertThat(de).as("armed hint rendered").contains("class=\"hint-armed\"");
    assertThat(de)
        .as("idle hint names the click path")
        .contains("Teilnehmer antippen, dann hierher tippen");
    assertThat(de).as("unit zone armed call to action").contains("Hier tippen, um zuzuweisen");
    assertThat(de)
        .as("pool zone armed call to action")
        .contains("Hier tippen, um die Zuweisung zu entfernen");
    assertThat(de)
        .as("zone aria-label carries the click path too")
        .contains("aria-label=\"Teilnehmer antippen, dann hierher tippen");
    assertThat(de).as("no unresolved crew-board message key").doesNotContain("??mission.crew.");

    String en =
        mockMvc
            .perform(get("/missions/" + missionId + "?lang=en"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(en)
        .as("English idle hint names the click path")
        .contains("Tap a participant, then tap here");
    assertThat(en).as("English unit zone armed call to action").contains("Tap here to assign");
    assertThat(en)
        .as("English pool zone armed call to action")
        .contains("Tap here to remove the assignment");
    assertThat(en).as("no unresolved crew-board message key").doesNotContain("??mission.crew.");
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
            1L);

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
            null,
            null);
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef()))
        .thenReturn(mission);
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
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
            1L);

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
            null,
            null);
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef()))
        .thenReturn(mission);
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/missions/" + missionId))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-start-time=\"2026-02-10T09:30:00Z\"")))
        .andExpect(content().string(containsString("data-end-time=\"2026-02-10T11:45:00Z\"")))
        .andExpect(content().string(not(containsString("data-start-time-formatted=\"\""))))
        .andExpect(content().string(not(containsString("data-end-time-formatted=\"\""))))
        .andExpect(content().string(containsString("src=\"/js/mission-detail.js\"")));
    assertThat(missionDetailModuleSource()).contains("krtSyncDatetimeSplitGroup");
  }

  /**
   * Reads the mission-detail page module ({@code static/js/mission-detail.js}) from the classpath,
   * for script-content assertions.
   *
   * @return the full UTF-8 source of the module
   * @throws IOException if the classpath resource cannot be read
   */
  private static String missionDetailModuleSource() throws IOException {
    try (var in =
        new org.springframework.core.io.ClassPathResource("static/js/mission-detail.js")
            .getInputStream()) {
      return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void missionDetail_LinkedRefineryOrder_RendersEndTimeForClientSideLocalZoneConversion()
      throws Exception {
    UUID missionId = UUID.randomUUID();

    de.greluc.krt.profit.basetool.frontend.model.dto.RefineryOrderListDto order =
        new de.greluc.krt.profit.basetool.frontend.model.dto.RefineryOrderListDto(
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
            null,
            Collections.emptyList(),
            null,
            1L);
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
            null,
            null);
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef()))
        .thenReturn(mission);
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());
    stubEmptyFinance(missionId);
    when(backendApiClient.get(eq("/api/v1/refinery-orders/mission/" + missionId), anyTypeRef()))
        .thenReturn(List.of(order));

    mockMvc
        .perform(get("/missions/" + missionId))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-utc=\"" + expectedEndsAtMillis + "\"")))
        .andExpect(content().string(containsString("class=\"refinery-endsat-local\"")))
        .andExpect(content().string(containsString("10.02.2026 11:00 UTC")))
        .andExpect(content().string(containsString("src=\"/js/mission-detail.js\"")));
    assertThat(missionDetailModuleSource()).contains("krtFormatLocalDateTime");
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
            1L);
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
            1L);
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
            1L);

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
            null,
            null);
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef()))
        .thenReturn(mission);
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/missions/" + missionId))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("2/3")));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void setMissionOwner_forwardsTheUserAndTheOwnershipVersionToTheVersionedEndpoint()
      throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    MissionDto refreshed = minimalMission(missionId);
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef()))
        .thenReturn(refreshed);

    mockMvc
        .perform(
            put("/missions/" + missionId + "/owner/ajax")
                .with(csrf())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + userId + "\",\"version\":4}"))
        .andExpect(status().isOk());

    @SuppressWarnings("unchecked")
    org.mockito.ArgumentCaptor<Map<String, Object>> sent =
        org.mockito.ArgumentCaptor.forClass(Map.class);
    verify(backendApiClient)
        .put(eq("/api/v1/missions/" + missionId + "/owner"), sent.capture(), eq(Void.class));
    org.assertj.core.api.Assertions.assertThat(sent.getValue())
        .containsEntry("userId", userId)
        .containsEntry("version", 4);
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void setMissionOwner_staleOwnershipVersion_relaysTheConflictWithItsCode() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    when(backendApiClient.put(
            eq("/api/v1/missions/" + missionId + "/owner"), any(), eq(Void.class)))
        .thenThrow(
            new de.greluc.krt.profit.basetool.frontend.service.BackendServiceException(
                "Conflict",
                null,
                409,
                "OPTIMISTIC_LOCK",
                null,
                java.util.List.of(),
                "Somebody changed it first"));

    mockMvc
        .perform(
            put("/missions/" + missionId + "/owner/ajax")
                .with(csrf())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + userId + "\",\"version\":0}"))
        .andExpect(status().isConflict())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                .value("OPTIMISTIC_LOCK"));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void updatePayoutPreference_callsTheSlimEndpointAndAnswersWithTheParticipantRow()
      throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();
    Map<String, Object> row = Map.of("id", participantId.toString(), "version", 7);
    when(backendApiClient.put(
            eq(
                "/api/v1/missions/"
                    + missionId
                    + "/participants/"
                    + participantId
                    + "/payout-preference/slim"),
            any(),
            eq(Object.class)))
        .thenReturn(row);

    mockMvc
        .perform(
            post("/missions/" + missionId + "/participants/" + participantId + "/payout-preference")
                .with(csrf())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"preference\":\"DONATE\"}"))
        .andExpect(status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.id")
                .value(participantId.toString()))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.version")
                .value(7));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void setMissionOwner_WithInvalidUserId_ShouldReturn400() throws Exception {
    mockMvc
        .perform(
            put("/missions/" + UUID.randomUUID() + "/owner/ajax")
                .with(csrf())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"not-a-uuid\",\"version\":0}"))
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
  void missionDetail_AssetShapedPath_ShouldReturn404NotWarn400() throws Exception {
    mockMvc
        .perform(get("/missions/common-handlers.js"))
        .andExpect(status().isNotFound())
        .andExpect(view().name("error/error"));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void missionDetail_UndottedMalformedId_ShouldKeep400() throws Exception {
    mockMvc.perform(get("/missions/8bd4a2de")).andExpect(status().isBadRequest());
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
            null,
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
            null,
            null);
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), eq(MissionDto.class)))
        .thenReturn(current)
        .thenReturn(refreshed);
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
            null,
            null);
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), eq(MissionDto.class)))
        .thenReturn(current);
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
            eq(String.class)))
        .thenThrow(
            new de.greluc.krt.profit.basetool.frontend.service.BackendServiceException(
                "Error", null, 400));

    mockMvc
        .perform(post("/missions/" + missionId + "/managers/" + userId).with(csrf()))
        .andExpect(status().isBadRequest());
  }

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
            eq("/api/v1/missions/" + missionId + "/frequencies/slim"), any(), eq(Object.class)))
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
            eq("/api/v1/missions/" + missionId + "/frequencies/slim"), any(), eq(Object.class)))
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
            eq(Object.class)))
        .thenReturn(java.util.Collections.emptyList());

    mockMvc
        .perform(delete("/missions/" + missionId + "/frequencies/" + freqId + "/ajax").with(csrf()))
        .andExpect(status().isOk());
  }

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
            eq(Object.class)))
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
            eq(Object.class)))
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
            eq(Object.class)))
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
            eq("/api/v1/missions/" + missionId + "/units/slim"), any(), eq(Object.class)))
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
            eq(Object.class)))
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
            eq("/api/v1/missions/" + missionId + "/units/" + unitId + "/slim"), eq(Void.class)))
        .thenReturn(null);

    mockMvc
        .perform(delete("/missions/" + missionId + "/units/" + unitId + "/ajax").with(csrf()))
        .andExpect(status().isNoContent());
  }

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

    when(backendApiClient.post(
            eq("/api/v1/missions/" + missionId + "/participants/slim"), any(), eq(Object.class)))
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

  @Test
  @WithMockUser(roles = "OFFICER")
  void updateParticipantAjax_WithBackendConflict_ShouldReturn409() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();
    when(backendApiClient.put(
            eq("/api/v1/missions/" + missionId + "/participants/" + participantId + "/slim"),
            any(),
            eq(Object.class)))
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
            eq(Void.class)))
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
            eq(Object.class)))
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
            eq(Object.class)))
        .thenThrow(
            new de.greluc.krt.profit.basetool.frontend.service.BackendServiceException(
                "Conflict", null, 409));

    mockMvc
        .perform(
            post("/missions/" + missionId + "/participants/" + participantId + "/check-out/ajax")
                .with(csrf()))
        .andExpect(status().isConflict());
  }

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
            eq(Object.class)))
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
            eq(Object.class)))
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
            eq(Void.class)))
        .thenReturn(null);

    mockMvc
        .perform(
            delete("/missions/" + missionId + "/units/" + unitId + "/crew/" + crewId + "/ajax")
                .with(csrf()))
        .andExpect(status().isNoContent());
  }

  @Test
  void missionDetail_AsMember_ShouldShowEditButtonForOwnParticipantEntry() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID memberUserId = UUID.randomUUID();
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
            1L);

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
            null,
            null,
            Collections.emptySet(),
            false,
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
            null,
            null);

    de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse<Object> emptyPage =
        new de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse<>(
            Collections.emptyList(), 0, 0, 0, 0, Collections.emptyList());
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef()))
        .thenReturn(mission);
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef())).thenReturn(emptyPage);
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef())).thenReturn(emptyPage);
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(emptyPage);
    when(backendApiClient.get(anyString(), anyClass())).thenReturn(null);
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef()))
        .thenReturn(mission);

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
            content()
                .string(containsString("class=\"btn btn-ghost btn-icon edit-participant-btn\"")));
  }

  @Test
  void missionDetail_AsMember_ShouldNotShowEditButtonForForeignParticipantEntry() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID loggedInUserId = UUID.randomUUID();
    UUID otherUserId = UUID.randomUUID();
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
            1L);

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
            null,
            null,
            Collections.emptySet(),
            false,
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
            null,
            null);

    de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse<Object> emptyPage2 =
        new de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse<>(
            Collections.emptyList(), 0, 0, 0, 0, Collections.emptyList());
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef()))
        .thenReturn(mission);
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef())).thenReturn(emptyPage2);
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef())).thenReturn(emptyPage2);
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(emptyPage2);
    when(backendApiClient.get(anyString(), anyClass())).thenReturn(null);
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef()))
        .thenReturn(mission);

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
            content()
                .string(
                    not(containsString("class=\"btn btn-ghost btn-icon edit-participant-btn\""))));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void getUnassignedParticipantsAjax_ShouldReturn200WithList() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();
    Map<String, Object> participant = new java.util.HashMap<>();
    participant.put("id", participantId.toString());
    participant.put("guestName", "Alice");
    List<Map<String, Object>> response = List.of(participant);

    when(backendApiClient.get(
            eq("/api/v1/missions/" + missionId + "/participants/unassigned"), anyTypeRef()))
        .thenReturn(response);

    mockMvc
        .perform(get("/missions/" + missionId + "/participants/unassigned/ajax"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(participantId.toString())));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void getUnassignedParticipantsAjax_WithBackendError_ShouldPropagateStatus() throws Exception {
    UUID missionId = UUID.randomUUID();
    when(backendApiClient.get(
            eq("/api/v1/missions/" + missionId + "/participants/unassigned"), anyTypeRef()))
        .thenThrow(
            new de.greluc.krt.profit.basetool.frontend.service.BackendServiceException(
                "Not Found", null, 404));

    mockMvc
        .perform(get("/missions/" + missionId + "/participants/unassigned/ajax"))
        .andExpect(status().isNotFound());
  }

  @Test
  void missionDetail_UnitShipPicker_OnlyOffersShipsOfRegisteredParticipants() throws Exception {
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
            1L);

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
            null,
            null);

    de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse<Object> emptyPage =
        new de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse<>(
            Collections.emptyList(), 0, 0, 0, 0, Collections.emptyList());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef())).thenReturn(emptyPage);
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef())).thenReturn(emptyPage);
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(emptyPage);
    when(backendApiClient.get(anyString(), anyClass())).thenReturn(null);
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef()))
        .thenReturn(mission);
    when(backendApiClient.get(
            eq("/api/v1/missions/" + missionId + "/unit-ship-options"), anyTypeRef()))
        .thenReturn(List.of(participantShip, outsiderShip));

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
            1L);

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
            null,
            null);

    de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse<Object> emptyPage =
        new de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse<>(
            Collections.emptyList(), 0, 0, 0, 0, Collections.emptyList());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef())).thenReturn(emptyPage);
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef())).thenReturn(emptyPage);
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(emptyPage);
    when(backendApiClient.get(anyString(), anyClass())).thenReturn(null);
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef()))
        .thenReturn(mission);
    when(backendApiClient.get(
            eq("/api/v1/missions/" + missionId + "/unit-ship-options"), anyTypeRef()))
        .thenReturn(List.of(participantShip, assignedShip, strayShip));

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
   * Verifies that the overview "Funk" panel lists central and per-unit frequencies that carry a
   * value and omits empty ones; the {@code overview} fragment is requested directly.
   */
  @Test
  void missionOverviewFragment_ListsFrequenciesWithValue_OmitsEmptyOnes() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID befehlTypeId = UUID.randomUUID();
    UUID notfallTypeId = UUID.randomUUID();

    de.greluc.krt.profit.basetool.frontend.model.dto.MissionFrequencyDto befehlFrequency =
        new de.greluc.krt.profit.basetool.frontend.model.dto.MissionFrequencyDto(
            UUID.randomUUID(),
            new de.greluc.krt.profit.basetool.frontend.model.dto.MissionFrequencyDto
                .FrequencyTypeRef(befehlTypeId, "Befehl"),
            null,
            new java.math.BigDecimal("121.50"),
            1L);

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
            null,
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

    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef())).thenReturn(emptyPage);
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef())).thenReturn(emptyPage);
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(emptyPage);
    when(backendApiClient.get(anyString(), anyClass())).thenReturn(null);
    when(backendApiClient.getCached(eq(CachedCatalog.FREQUENCY_TYPES_ACTIVE), anyTypeRef()))
        .thenReturn(freqTypesPage);
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef()))
        .thenReturn(mission);
    when(backendApiClient.get(
            eq("/api/v1/missions/" + missionId + "/unit-ship-options"), anyTypeRef()))
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
        .andExpect(content().string(containsString("Befehl")))
        .andExpect(content().string(containsString("121.50")))
        .andExpect(content().string(not(containsString("Notfall"))))
        .andExpect(content().string(containsString("Alpha")))
        .andExpect(content().string(containsString("243.75")))
        .andExpect(content().string(not(containsString("Bravo"))));
  }

  /**
   * Verifies that the "Funk" panel is hidden when the frequency-types lookup fails, even though the
   * mission stores a central frequency value.
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
            null,
            null);

    de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse<Object> emptyPage =
        new de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse<>(
            Collections.emptyList(), 0, 0, 0, 0, Collections.emptyList());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef())).thenReturn(emptyPage);
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef())).thenReturn(emptyPage);
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(emptyPage);
    when(backendApiClient.get(anyString(), anyClass())).thenReturn(null);
    when(backendApiClient.getCached(eq(CachedCatalog.FREQUENCY_TYPES_ACTIVE), anyTypeRef()))
        .thenThrow(new RuntimeException("frequency types unavailable"));
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef()))
        .thenReturn(mission);
    when(backendApiClient.get(
            eq("/api/v1/missions/" + missionId + "/unit-ship-options"), anyTypeRef()))
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
        .andExpect(content().string(not(containsString(">Funk<"))))
        .andExpect(content().string(not(containsString("121.50"))));
  }

  /**
   * Verifies that the mission detail page renders without a template error when the mission has a
   * finance entry, including its edit button's rounded amount.
   *
   * <p>{@code ROLE_OFFICER} is granted via {@code oidcLogin()}, as finance data is only fetched for
   * OIDC members.
   */
  @Test
  void missionDetail_WithFinanceEntry_ShouldRenderEditButtonWithoutTemplateError()
      throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID entryId = UUID.randomUUID();

    de.greluc.krt.profit.basetool.frontend.model.dto.MissionFinanceEntryDto entry =
        new de.greluc.krt.profit.basetool.frontend.model.dto.MissionFinanceEntryDto(
            entryId,
            missionId,
            null,
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
            null,
            null);

    de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse<Object> emptyPage =
        new de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse<>(
            Collections.emptyList(), 0, 0, 0, 0, Collections.emptyList());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef())).thenReturn(emptyPage);
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef())).thenReturn(emptyPage);
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(emptyPage);
    when(backendApiClient.get(anyString(), anyClass())).thenReturn(null);
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef()))
        .thenReturn(mission);
    when(backendApiClient.get(
            eq("/api/v1/missions/" + missionId + "/finance-entries/summary"),
            eq(MissionFinanceTotalsDto.class)))
        .thenReturn(
            new MissionFinanceTotalsDto(BigDecimal.ZERO, BigDecimal.ZERO, 0L, BigDecimal.ZERO, 0L));
    when(backendApiClient.get(
            eq("/api/v1/missions/" + missionId + "/finance-entries?size=200"), anyTypeRef()))
        .thenReturn(financesPage);
    when(backendApiClient.get(eq("/api/v1/refinery-orders/mission/" + missionId), anyTypeRef()))
        .thenReturn(Collections.emptyList());
    when(backendApiClient.get(eq("/api/v1/inventory/mission/" + missionId), anyTypeRef()))
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
        .andExpect(status().isOk())
        .andExpect(view().name("mission-detail"))
        .andExpect(content().string(containsString("data-amount=\"1235\"")));
  }

  /**
   * Verifies the crew-board markup for a unit with one crew member holding one function: the job
   * chip-select, the multi-edit entry and the drop-zone wiring.
   */
  @Test
  @org.springframework.security.test.context.support.WithMockUser(roles = "KRT_MEMBER")
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
            1L);

    de.greluc.krt.profit.basetool.frontend.model.dto.JobTypeDto gunner =
        new de.greluc.krt.profit.basetool.frontend.model.dto.JobTypeDto(
            jobTypeId, "Gunner", null, "CREW", null, true, false, false, 1L);
    de.greluc.krt.profit.basetool.frontend.model.dto.MissionCrewDto crew =
        new de.greluc.krt.profit.basetool.frontend.model.dto.MissionCrewDto(
            crewId, participantId, "Crewman", null, java.util.Set.of(gunner));
    de.greluc.krt.profit.basetool.frontend.model.dto.MissionUnitDto unit =
        new de.greluc.krt.profit.basetool.frontend.model.dto.MissionUnitDto(
            unitId, "Alpha", null, null, null, true, null, null, null, List.of(crew));

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
            null,
            null);

    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef()))
        .thenReturn(mission);
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());
    de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse<java.util.Map<String, Object>>
        crewJobTypesPage =
            new de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse<>(
                List.of(java.util.Map.of("id", jobTypeId, "name", "Gunner")),
                0,
                1,
                1,
                1,
                Collections.emptyList());
    when(backendApiClient.getCached(eq(CachedCatalog.JOB_TYPES_CREW), anyTypeRef()))
        .thenReturn(crewJobTypesPage);

    mockMvc
        .perform(get("/missions/" + missionId))
        .andExpect(status().isOk())
        .andExpect(view().name("mission-detail"))
        .andExpect(content().string(containsString("data-unit-id=\"" + unitId + "\"")))
        .andExpect(
            content().string(containsString("data-participant-id=\"" + participantId + "\"")))
        .andExpect(content().string(containsString("data-crew-id=\"" + crewId + "\"")))
        .andExpect(content().string(containsString("crew-role-select")))
        .andExpect(content().string(containsString("value=\"" + jobTypeId + "\" selected")))
        .andExpect(content().string(containsString("value=\"__edit\"")))
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
            null,
            null);

    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef()))
        .thenReturn(mission);
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/missions/" + missionId))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("<strong>Sammeln</strong>")))
        .andExpect(content().string(not(containsString("<script>alert"))));
  }

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
        null,
        null);
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void missionDetail_CrewBoardFragment_RendersBoardOnly() throws Exception {
    UUID missionId = UUID.randomUUID();
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef()))
        .thenReturn(editableMission(missionId));
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/missions/" + missionId).param("fragment", "crew-board"))
        .andExpect(status().isOk())
        .andExpect(view().name("mission-detail :: crewBoard"))
        .andExpect(content().string(containsString("id=\"board-pool\"")))
        .andExpect(content().string(not(containsString("mission-head-sticky"))))
        .andExpect(content().string(not(containsString("id=\"pane-fin\""))));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void missionDetail_FinanceFragment_RendersFinancePaneOnly() throws Exception {
    UUID missionId = UUID.randomUUID();
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef()))
        .thenReturn(editableMission(missionId));
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
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
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef()))
        .thenReturn(editableMission(missionId));
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
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
  void missionDetail_StepsEditorFragment_RendersStepsEditorOnly() throws Exception {
    UUID missionId = UUID.randomUUID();
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef()))
        .thenReturn(editableMission(missionId));
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/missions/" + missionId).param("fragment", "steps-editor"))
        .andExpect(status().isOk())
        .andExpect(view().name("mission-detail :: stepsEditor"))
        .andExpect(content().string(containsString("id=\"mission-step-list\"")))
        .andExpect(content().string(not(containsString("mission-head-sticky"))))
        .andExpect(content().string(not(containsString("id=\"pane-fin\""))));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void missionDetail_ObjectivesEditorFragment_RendersObjectivesEditorOnly() throws Exception {
    UUID missionId = UUID.randomUUID();
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef()))
        .thenReturn(editableMission(missionId));
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/missions/" + missionId).param("fragment", "objectives-editor"))
        .andExpect(status().isOk())
        .andExpect(view().name("mission-detail :: objectivesEditor"))
        .andExpect(content().string(containsString("id=\"mission-objective-list\"")))
        .andExpect(content().string(not(containsString("mission-head-sticky"))))
        .andExpect(content().string(not(containsString("id=\"pane-fin\""))));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void missionDetail_FrequenciesEditorFragment_RendersFrequenciesEditorOnly() throws Exception {
    UUID missionId = UUID.randomUUID();
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef()))
        .thenReturn(editableMission(missionId));
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/missions/" + missionId).param("fragment", "frequencies-editor"))
        .andExpect(status().isOk())
        .andExpect(view().name("mission-detail :: frequenciesEditor"))
        .andExpect(content().string(containsString("id=\"mission-custom-freq-list\"")))
        .andExpect(content().string(not(containsString("mission-head-sticky"))))
        .andExpect(content().string(not(containsString("id=\"pane-fin\""))));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void missionDetail_OrganisationFragment_RendersPartyLeadAndTypedFrequenciesOnly()
      throws Exception {
    UUID missionId = UUID.randomUUID();
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef()))
        .thenReturn(editableMission(missionId));
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/missions/" + missionId).param("fragment", "organisation"))
        .andExpect(status().isOk())
        .andExpect(view().name("mission-detail :: organisationPanel"))
        .andExpect(content().string(containsString("id=\"party-lead-display\"")))
        .andExpect(content().string(not(containsString("mission-head-sticky"))))
        .andExpect(content().string(not(containsString("id=\"mission-custom-freq-list\""))));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void missionDetail_OrganisationFragment_ErrorPathRendersSectionSizedAlert() throws Exception {
    UUID missionId = UUID.randomUUID();
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef()))
        .thenThrow(new RuntimeException("boom"));

    mockMvc
        .perform(get("/missions/" + missionId).param("fragment", "organisation"))
        .andExpect(status().isOk())
        .andExpect(view().name("mission-detail :: fragmentError"))
        .andExpect(content().string(not(containsString("mission-head-sticky"))));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void missionDetail_FragmentBackendError_RendersInlineErrorFragmentNotRedirect() throws Exception {
    UUID missionId = UUID.randomUUID();
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef()))
        .thenThrow(new RuntimeException("backend unavailable"));

    mockMvc
        .perform(get("/missions/" + missionId).param("fragment", "crew-board"))
        .andExpect(status().isOk())
        .andExpect(view().name("mission-detail :: fragmentError"))
        .andExpect(content().string(containsString("role=\"alert\"")))
        .andExpect(content().string(not(containsString("mission-head-sticky"))))
        .andExpect(content().string(not(containsString("id=\"board-pool\""))));
  }

  /**
   * Asserts that the member-only finance reads (summary, entries page, refinery) were not issued.
   */
  private void verifyNoFinanceReads(UUID missionId) {
    verify(backendApiClient, never())
        .get(eq("/api/v1/missions/" + missionId + "/finance-entries/summary"), anyClass());
    verify(backendApiClient, never())
        .get(eq("/api/v1/missions/" + missionId + "/finance-entries?size=200"), anyTypeRef());
    verify(backendApiClient, never())
        .get(eq("/api/v1/refinery-orders/mission/" + missionId), anyTypeRef());
    verify(backendApiClient, never())
        .get(eq("/api/v1/inventory/mission/" + missionId), anyTypeRef());
  }

  /**
   * Asserts that the all-users roster read ({@code /users/lookup}) was not issued; the owner and
   * manager pickers search {@code /users/search} on demand.
   */
  private void verifyNoUserLookupRead() {
    verify(backendApiClient, never()).get(eq("/api/v1/users/lookup"), anyTypeRef());
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void missionDetail_CrewBoardFragment_SkipsFinanceAndMgmtReads() throws Exception {
    UUID missionId = UUID.randomUUID();
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef()))
        .thenReturn(editableMission(missionId));
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/missions/" + missionId).param("fragment", "crew-board"))
        .andExpect(status().isOk())
        .andExpect(view().name("mission-detail :: crewBoard"));

    verifyNoFinanceReads(missionId);
    verifyNoUserLookupRead();
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void missionDetail_OverviewFragment_SkipsFinanceMgmtAndShipReads() throws Exception {
    UUID missionId = UUID.randomUUID();
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef()))
        .thenReturn(editableMission(missionId));
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/missions/" + missionId).param("fragment", "overview"))
        .andExpect(status().isOk())
        .andExpect(view().name("mission-detail :: overviewSection"));

    verifyNoFinanceReads(missionId);
    verifyNoUserLookupRead();
    verify(backendApiClient, never())
        .get(eq("/api/v1/missions/" + missionId + "/unit-ship-options"), anyTypeRef());
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void missionDetail_FinanceFragment_IssuesFinanceReads() throws Exception {
    UUID missionId = UUID.randomUUID();
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef()))
        .thenReturn(editableMission(missionId));
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());
    stubEmptyFinance(missionId);

    mockMvc
        .perform(get("/missions/" + missionId).param("fragment", "finance"))
        .andExpect(status().isOk())
        .andExpect(view().name("mission-detail :: financeSection"));

    verify(backendApiClient)
        .get(
            eq("/api/v1/missions/" + missionId + "/finance-entries/summary"),
            eq(MissionFinanceTotalsDto.class));
    verify(backendApiClient)
        .get(eq("/api/v1/missions/" + missionId + "/finance-entries?size=200"), anyTypeRef());
    verify(backendApiClient).get(eq("/api/v1/refinery-orders/mission/" + missionId), anyTypeRef());
    verify(backendApiClient).get(eq("/api/v1/inventory/mission/" + missionId), anyTypeRef());
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void missionDetail_MgmtFragment_SkipsFinanceAndUserLookupReads() throws Exception {
    UUID missionId = UUID.randomUUID();
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef()))
        .thenReturn(editableMission(missionId));
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/missions/" + missionId).param("fragment", "mgmt"))
        .andExpect(status().isOk())
        .andExpect(view().name("mission-detail :: mgmtPanels"));

    verifyNoUserLookupRead();
    verifyNoFinanceReads(missionId);
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void missionDetail_FullPage_StillIssuesEveryGatedRead() throws Exception {
    UUID missionId = UUID.randomUUID();
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef()))
        .thenReturn(editableMission(missionId));
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());
    stubEmptyFinance(missionId);

    mockMvc.perform(get("/missions/" + missionId)).andExpect(status().isOk());

    verify(backendApiClient)
        .get(
            eq("/api/v1/missions/" + missionId + "/finance-entries/summary"),
            eq(MissionFinanceTotalsDto.class));
    verifyNoUserLookupRead();
    verify(backendApiClient)
        .get(eq("/api/v1/missions/" + missionId + "/unit-ship-options"), anyTypeRef());
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void setPartyLeadAjax_Success_ReturnsRefreshedMission() throws Exception {
    UUID missionId = UUID.randomUUID();
    when(backendApiClient.put(
            eq("/api/v1/missions/" + missionId + "/party-lead"), any(), eq(Void.class)))
        .thenReturn(null);
    when(backendApiClient.get(eq("/api/v1/missions/" + missionId), anyTypeRef()))
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
            eq("/api/v1/missions/" + missionId + "/party-lead"), any(), eq(Void.class)))
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
