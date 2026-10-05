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

package de.greluc.krt.profit.basetool.frontend.mission.client;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.model.dto.AddCrewRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.AddCustomFrequencyRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.AddExternalParticipantRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.AddFrequencyRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.AddMissionObjectiveRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.AddMissionStepRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.AddUnitRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.CreateMissionRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.FinanceType;
import de.greluc.krt.profit.basetool.frontend.model.dto.MissionFinanceEntryCreateDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MissionFinanceEntryUpdateDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PatchMissionCoreRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.PatchMissionFlagsRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.PatchMissionScheduleRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.ReorderMissionObjectivesRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.ReorderMissionStepsRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.SetPartyLeadRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.ToggleMissionStepRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.UpdateCrewRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.UpdateCustomFrequencyRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.UpdateMissionObjectiveRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.UpdateMissionOwnerRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.UpdateMissionOwningOrgUnitRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.UpdateMissionStepRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.UpdateParticipantRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.UpdatePayoutPreferenceRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.UpdateUnitRequest;
import de.greluc.krt.profit.basetool.frontend.service.BackendClientHarness;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.util.DefaultUriBuilderFactory;

/**
 * Pins the requests {@link MissionBackendClient} sends (plan F3), each the exact request the
 * mission controllers sent before the client existed, with the backend's request records as bodies.
 */
class MissionBackendClientTest {

  private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
  private static final UUID OTHER = UUID.fromString("66666666-7777-8888-9999-000000000000");
  private static final UUID THIRD = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
  private static final Instant START = Instant.parse("2026-09-01T10:15:30Z");
  private static final String EMPTY_PAGE =
      "{\"content\":[],\"page\":0,\"size\":50,\"totalElements\":0,\"totalPages\":0}";
  private static final String M = "/api/v1/missions/" + ID;

  private BackendClientHarness backend;
  private MissionBackendClient client;

  @BeforeEach
  void setUp() {
    backend = BackendClientHarness.start();
    client = new MissionBackendClient(backend.backendApiClient());
  }

  @AfterEach
  void tearDown() {
    backend.close();
  }

  @Test
  void theListSearchRelaysEveryFilterAsATemplateVariable() {
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);

    client.searchMissions(
        new MissionBackendClient.MissionSearch(
            "a&b c", START, START, 2, 25, List.of(), "UPCOMING"));
    client.searchMissions(
        new MissionBackendClient.MissionSearch(
            " ", null, null, null, null, List.of("COMPLETED", "ACTIVE"), "ALL"));
    client.searchMissions(
        new MissionBackendClient.MissionSearch(null, null, null, null, null, List.of(), "ALL"));
    client.searchMissions(
        new MissionBackendClient.MissionSearch(null, null, null, null, null, List.of(), "PAST"));

    backend.expect(
        "GET",
        sent(
            "/api/v1/missions/search?query={query}&start={start}&end={end}&page={page}&size={size}&"
                + "sort=plannedStartTime,desc&status=PLANNED&status=ACTIVE&",
            "a&b c",
            START,
            START,
            2,
            25));
    backend.expect(
        "GET",
        sent(
            "/api/v1/missions/search?sort=plannedStartTime,desc&status={status}&status={status}&",
            "COMPLETED",
            "ACTIVE"));
    backend.expect(
        "GET",
        sent(
            "/api/v1/missions/search?sort=plannedStartTime,desc&"
                + "status=PLANNED&status=ACTIVE&status=COMPLETED&status=CANCELLED&"));
    backend.expect(
        "GET",
        sent(
            "/api/v1/missions/search?sort=plannedStartTime,desc"
                + "&status=COMPLETED&status=CANCELLED&"));
  }

  /**
   * Expands a URI template the way the backend {@code WebClient} does.
   *
   * @param template the template the controller sent before the client existed
   * @param variables its variables, in order
   * @return the raw path and query that reach the backend
   */
  private static String sent(String template, Object... variables) {
    URI uri = new DefaultUriBuilderFactory().expand(template, variables);
    return uri.getRawPath() + "?" + uri.getRawQuery();
  }

  @Test
  void detailReads() {
    backend.answerJson("[]");
    backend.answerJson("{}");
    backend.answerJson("{}");
    backend.answerJson("{}");
    backend.answerJson("[]");
    backend.answerJson("{}");
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson("[]");
    backend.answerJson("[]");
    backend.answerJson("[]");
    backend.answerJson("[{\"id\":\"" + OTHER + "\"}]");
    backend.answerJson("{\"id\":\"refinery.rounding.mode\",\"value\":\"DOWN\",\"version\":3}");

    assertThat(client.operationReferences()).isEmpty();
    client.currentUser();
    assertThat(client.mission(ID)).isNotNull();
    assertThat(client.reloadMission(ID)).isNotNull();
    assertThat(client.unitShipOptions(ID)).isEmpty();
    client.financeTotals(ID);
    assertThat(client.financeEntries(ID, 200)).isNotNull();
    assertThat(client.refineryOrders(ID)).isEmpty();
    assertThat(client.inventory(ID)).isEmpty();
    assertThat(client.pickableOrgUnits()).isEmpty();
    assertThat(client.unassignedParticipants(ID))
        .singleElement()
        .satisfies(participant -> assertThat(participant.id()).isEqualTo(OTHER));
    assertThat(client.refineryRoundingMode().value()).isEqualTo("DOWN");

    backend.expect("GET", "/api/v1/operations/lookup");
    backend.expect("GET", "/api/v1/users/me");
    backend.expect("GET", M);
    backend.expect("GET", M);
    backend.expect("GET", M + "/unit-ship-options");
    backend.expect("GET", M + "/finance-entries/summary");
    backend.expect("GET", M + "/finance-entries?size=200");
    backend.expect("GET", "/api/v1/refinery-orders/mission/" + ID);
    backend.expect("GET", "/api/v1/inventory/mission/" + ID);
    backend.expect("GET", "/api/v1/users/me/pickable-org-units");
    backend.expect("GET", M + "/participants/unassigned");
    backend.expect("GET", "/api/v1/settings/refinery.rounding.mode");
  }

  @Test
  void cachedCatalogues() {
    String jobTypes =
        "{\"content\":[{\"id\":\""
            + OTHER
            + "\",\"name\":\"Pilot\",\"archetype\":\"CREW\"}],"
            + "\"page\":0,\"size\":1000,\"totalElements\":1,\"totalPages\":1}";
    String frequencyTypes =
        "{\"content\":[{\"id\":\""
            + THIRD
            + "\",\"name\":\"Ops\",\"active\":true,\"sortIndex\":1}],"
            + "\"page\":0,\"size\":1000,\"totalElements\":1,\"totalPages\":1}";
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(jobTypes);
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson("[]");
    backend.answerJson(frequencyTypes);
    backend.answerJson(EMPTY_PAGE);

    assertThat(client.missionJobTypes().content()).isEmpty();
    assertThat(client.crewJobTypes().content())
        .singleElement()
        .satisfies(
            jobType -> {
              assertThat(jobType.id()).isEqualTo(OTHER);
              assertThat(jobType.name()).isEqualTo("Pilot");
            });
    assertThat(client.squadrons().content()).isEmpty();
    assertThat(client.activeOrgUnits()).isEmpty();
    assertThat(client.frequencyTypes().content())
        .singleElement()
        .satisfies(frequencyType -> assertThat(frequencyType.id()).isEqualTo(THIRD));
    assertThat(client.shipTypes().content()).isEmpty();

    backend.expect("GET", "/api/v1/job-types?archetype=MISSION&size=1000&page=0");
    backend.expect("GET", "/api/v1/job-types?archetype=CREW&size=1000&page=0");
    backend.expect("GET", "/api/v1/squadrons?size=1000&page=0");
    backend.expect("GET", "/api/v1/org-units/active");
    backend.expect(
        "GET", "/api/v1/frequency-types?size=1000&active=true&sort=sortIndex,asc&page=0");
    backend.expect("GET", "/api/v1/ship-types?size=1000&page=0");
  }

  @Test
  void participantWrites() {
    AddExternalParticipantRequest add =
        new AddExternalParticipantRequest(THIRD, null, OTHER, "Hi", List.of(ID), "DONATE");
    UpdateParticipantRequest update =
        new UpdateParticipantRequest(OTHER, null, "Hi", null, START, null, null, "PAYOUT", 4L);
    for (int i = 0; i < 4; i++) {
      backend.answerEmpty();
    }
    backend.answerJson("[]");
    backend.answerJson("{}");
    backend.answerEmpty();
    backend.answerEmpty();
    backend.answerJson("{}");
    backend.answerJson("{}");
    backend.answerJson("{}");

    client.addParticipant(ID, add);
    client.updateParticipant(ID, OTHER, update);
    client.deleteParticipant(ID, OTHER);
    client.checkInParticipant(ID, OTHER);
    assertThat(client.addParticipantJson(ID, add)).isEmpty();
    assertThat(client.updateParticipantJson(ID, OTHER, update)).isNotNull();
    client.checkOutParticipant(ID, OTHER);
    client.deleteParticipant(ID, OTHER);
    assertThat(client.checkInParticipantJson(ID, OTHER)).isNotNull();
    assertThat(client.checkOutParticipantJson(ID, OTHER)).isNotNull();
    assertThat(client.updatePayoutPreference(ID, OTHER, new UpdatePayoutPreferenceRequest(null)))
        .isNotNull();

    String addJson =
        "{\"userId\":\""
            + THIRD
            + "\",\"guestName\":null,\"desiredJobTypeId\":\""
            + OTHER
            + "\",\"comment\":\"Hi\",\"orgUnitIds\":[\""
            + ID
            + "\"],\"payoutPreference\":\"DONATE\"}";
    String updateJson =
        "{\"desiredMissionJobTypeId\":\""
            + OTHER
            + "\",\"plannedMissionJobTypeId\":null,\"comment\":\"Hi\",\"guestName\":null,"
            + "\"startTime\":\"2026-09-01T10:15:30Z\",\"endTime\":null,\"orgUnitIds\":null,"
            + "\"payoutPreference\":\"PAYOUT\",\"version\":4}";
    String participant = M + "/participants/" + OTHER;
    backend.expect("POST", M + "/participants/add", addJson);
    backend.expect("PUT", participant + "/slim", updateJson);
    backend.expect("DELETE", participant + "/slim");
    backend.expect("POST", participant + "/check-in/slim", null);
    backend.expect("POST", M + "/participants/slim", addJson);
    backend.expect("PUT", participant + "/slim", updateJson);
    backend.expect("POST", participant + "/check-out/slim", null);
    backend.expect("DELETE", participant + "/slim");
    backend.expect("POST", participant + "/check-in/slim", null);
    backend.expect("POST", participant + "/check-out/slim", null);
    backend.expect("PUT", participant + "/payout-preference/slim", "{\"preference\":null}");
  }

  @Test
  void sectionAndOwnershipWrites() {
    for (int i = 0; i < 9; i++) {
      backend.answerEmpty();
    }
    backend.answerJson("{\"id\":\"" + ID + "\"}");
    CreateMissionRequest create =
        new CreateMissionRequest(
            "Op", null, null, "PLANNED", null, null, null, false, null, null, null, null, null);

    client.setPartyLead(ID, new SetPartyLeadRequest(THIRD, null, 4L));
    client.setOwner(ID, new UpdateMissionOwnerRequest(THIRD, 4L));
    client.setOwningOrgUnit(ID, new UpdateMissionOwningOrgUnitRequest(null, 4L));
    client.patchSchedule(ID, new PatchMissionScheduleRequest(START, null, null, null, null, 4L));
    client.patchCore(
        ID, new PatchMissionCoreRequest("Alpha", null, null, "PLANNED", OTHER, 4L, null));
    client.patchFlags(ID, new PatchMissionFlagsRequest(true, 4L));
    client.deleteMission(ID);
    client.addManager(ID, OTHER);
    client.removeManager(ID, OTHER);
    assertThat(client.createMission(create).id()).isEqualTo(ID);

    backend.expect(
        "PUT",
        M + "/party-lead",
        "{\"userId\":\"" + THIRD + "\",\"guestName\":null,\"version\":4}");
    backend.expect("PUT", M + "/owner", "{\"userId\":\"" + THIRD + "\",\"version\":4}");
    backend.expect("PUT", M + "/owning-org-unit", "{\"owningOrgUnitId\":null,\"version\":4}");
    backend.expect(
        "PATCH",
        M + "/schedule",
        "{\"meetingTime\":\"2026-09-01T10:15:30Z\",\"plannedStartTime\":null,"
            + "\"plannedEndTime\":null,\"actualStartTime\":null,\"actualEndTime\":null,"
            + "\"version\":4}");
    backend.expect(
        "PATCH",
        M + "/core",
        "{\"name\":\"Alpha\",\"description\":null,\"calendarLink\":null,\"status\":\"PLANNED\","
            + "\"operationId\":\""
            + OTHER
            + "\",\"version\":4,\"meetingPoint\":null}");
    backend.expect("PATCH", M + "/flags", "{\"isInternal\":true,\"version\":4}");
    backend.expect("DELETE", M);
    backend.expect("POST", M + "/managers/" + OTHER + "/slim", null);
    backend.expect("DELETE", M + "/managers/" + OTHER + "/slim");
    backend.expect(
        "POST",
        "/api/v1/missions",
        "{\"name\":\"Op\",\"description\":null,\"calendarLink\":null,\"status\":\"PLANNED\","
            + "\"meetingTime\":null,\"plannedStartTime\":null,\"plannedEndTime\":null,"
            + "\"isInternal\":false,\"operationId\":null,\"owningOrgUnitId\":null,"
            + "\"meetingPoint\":null,\"objectives\":null,\"steps\":null}");
  }

  @Test
  void unitAndCrewWrites() {
    AddUnitRequest add = new AddUnitRequest("Alpha", OTHER, null, true, 123.45, null, null);
    UpdateUnitRequest update =
        new UpdateUnitRequest("Alpha", OTHER, null, false, null, THIRD, "Lead", 4L);
    AddCrewRequest crew = new AddCrewRequest(THIRD, List.of(OTHER));
    UpdateCrewRequest crewUpdate = new UpdateCrewRequest(List.of(), null);
    backend.answerEmpty();
    backend.answerJson("[]");
    backend.answerEmpty();
    backend.answerJson("{}");
    backend.answerEmpty();
    backend.answerEmpty();
    backend.answerJson("[]");
    backend.answerEmpty();
    backend.answerJson("{}");
    backend.answerEmpty();

    client.addUnit(ID, add);
    assertThat(client.addUnitJson(ID, add)).isEmpty();
    client.updateUnit(ID, OTHER, update);
    assertThat(client.updateUnitJson(ID, OTHER, update)).isNotNull();
    client.deleteUnit(ID, OTHER);
    client.addCrew(ID, OTHER, crew);
    assertThat(client.addCrewJson(ID, OTHER, crew)).isEmpty();
    client.updateCrew(ID, OTHER, THIRD, crewUpdate);
    assertThat(client.updateCrewJson(ID, OTHER, THIRD, crewUpdate)).isNotNull();
    client.deleteCrew(ID, OTHER, THIRD);

    String addJson =
        "{\"name\":\"Alpha\",\"shipTypeId\":\""
            + OTHER
            + "\",\"shipId\":null,\"highValueUnit\":true,\"frequency\":123.45,"
            + "\"responsibleUserId\":null,\"note\":null}";
    String updateJson =
        "{\"name\":\"Alpha\",\"shipTypeId\":\""
            + OTHER
            + "\",\"shipId\":null,\"highValueUnit\":false,\"frequency\":null,"
            + "\"responsibleUserId\":\""
            + THIRD
            + "\",\"note\":\"Lead\",\"version\":4}";
    String crewJson = "{\"participantId\":\"" + THIRD + "\",\"jobTypeIds\":[\"" + OTHER + "\"]}";
    String crewUpdateJson = "{\"jobTypeIds\":[],\"version\":null}";
    String unit = M + "/units/" + OTHER;
    backend.expect("POST", M + "/units/slim", addJson);
    backend.expect("POST", M + "/units/slim", addJson);
    backend.expect("PUT", unit + "/slim", updateJson);
    backend.expect("PUT", unit + "/slim", updateJson);
    backend.expect("DELETE", unit + "/slim");
    backend.expect("POST", unit + "/crew/slim", crewJson);
    backend.expect("POST", unit + "/crew/slim", crewJson);
    backend.expect("PUT", unit + "/crew/" + THIRD + "/slim", crewUpdateJson);
    backend.expect("PUT", unit + "/crew/" + THIRD + "/slim", crewUpdateJson);
    backend.expect("DELETE", unit + "/crew/" + THIRD + "/slim");
  }

  @Test
  void frequencyStepAndObjectiveWrites() {
    AddFrequencyRequest frequency = new AddFrequencyRequest(OTHER, new BigDecimal("123.45"));
    backend.answerEmpty();
    backend.answerJson("[]");
    backend.answerEmpty();
    for (int i = 0; i < 11; i++) {
      backend.answerJson("[]");
    }

    client.putFrequency(ID, frequency);
    assertThat(client.putFrequencyJson(ID, frequency)).isEmpty();
    client.deleteFrequency(ID, OTHER);
    assertThat(
            client.addCustomFrequency(
                ID, new AddCustomFrequencyRequest("Ops", new BigDecimal("101.10"))))
        .isEmpty();
    client.updateCustomFrequency(
        ID, OTHER, new UpdateCustomFrequencyRequest("Ops", new BigDecimal("101.10"), 4L));
    assertThat(client.addStep(ID, new AddMissionStepRequest("Brief", null, 4L))).isEmpty();
    client.updateStep(ID, OTHER, new UpdateMissionStepRequest("Brief", "19:00", 4L));
    assertThat(client.deleteStep(ID, OTHER, 7L)).isEmpty();
    client.reorderSteps(ID, new ReorderMissionStepsRequest(List.of(OTHER, THIRD), 4L));
    client.setStepDone(ID, OTHER, new ToggleMissionStepRequest(true, 4L));
    assertThat(client.addObjective(ID, new AddMissionObjectiveRequest("Win", "PRIMARY", 4L)))
        .isEmpty();
    client.updateObjective(ID, OTHER, new UpdateMissionObjectiveRequest("Win", "PRIMARY", 5L));
    assertThat(client.deleteObjective(ID, OTHER, 8L)).isEmpty();
    client.reorderObjectives(ID, new ReorderMissionObjectivesRequest(List.of(THIRD), 4L));

    String frequencyJson = "{\"frequencyTypeId\":\"" + OTHER + "\",\"value\":123.45}";
    backend.expect("POST", M + "/frequencies/slim", frequencyJson);
    backend.expect("POST", M + "/frequencies/slim", frequencyJson);
    backend.expect("DELETE", M + "/frequencies/" + OTHER + "/slim");
    backend.expect("POST", M + "/frequencies/custom/slim", "{\"name\":\"Ops\",\"value\":101.10}");
    backend.expect(
        "PUT",
        M + "/frequencies/custom/" + OTHER + "/slim",
        "{\"name\":\"Ops\",\"value\":101.10,\"version\":4}");
    backend.expect(
        "POST", M + "/steps/slim", "{\"title\":\"Brief\",\"meta\":null,\"stepsVersion\":4}");
    backend.expect(
        "PUT",
        M + "/steps/" + OTHER + "/slim",
        "{\"title\":\"Brief\",\"meta\":\"19:00\",\"stepsVersion\":4}");
    backend.expect("DELETE", M + "/steps/" + OTHER + "/slim?stepsVersion=7");
    backend.expect(
        "PUT",
        M + "/steps/reorder/slim",
        "{\"stepIds\":[\"" + OTHER + "\",\"" + THIRD + "\"],\"stepsVersion\":4}");
    backend.expect(
        "PATCH", M + "/steps/" + OTHER + "/done/slim", "{\"done\":true,\"stepsVersion\":4}");
    backend.expect(
        "POST",
        M + "/objectives/slim",
        "{\"title\":\"Win\",\"kind\":\"PRIMARY\",\"objectivesVersion\":4}");
    backend.expect(
        "PUT",
        M + "/objectives/" + OTHER + "/slim",
        "{\"title\":\"Win\",\"kind\":\"PRIMARY\",\"objectivesVersion\":5}");
    backend.expect("DELETE", M + "/objectives/" + OTHER + "/slim?objectivesVersion=8");
    backend.expect(
        "PUT",
        M + "/objectives/reorder/slim",
        "{\"objectiveIds\":[\"" + THIRD + "\"],\"objectivesVersion\":4}");
  }

  @Test
  void financeEntryWrites() {
    MissionFinanceEntryCreateDto create =
        new MissionFinanceEntryCreateDto(
            ID, THIRD, null, FinanceType.INCOME, new BigDecimal("250"));
    MissionFinanceEntryUpdateDto update =
        new MissionFinanceEntryUpdateDto("Sale", FinanceType.EXPENSE, new BigDecimal("99"), 4L);
    backend.answerEmpty();
    backend.answerJson("{}");
    backend.answerEmpty();
    backend.answerJson("{}");
    backend.answerEmpty();

    client.addFinanceEntry(create);
    assertThat(client.addFinanceEntryJson(create)).isNotNull();
    client.updateFinanceEntry(OTHER, update);
    assertThat(client.updateFinanceEntryJson(OTHER, update)).isNotNull();
    client.deleteFinanceEntry(OTHER);

    String createJson =
        "{\"missionId\":\""
            + ID
            + "\",\"participantId\":\""
            + THIRD
            + "\",\"note\":null,\"type\":\"INCOME\",\"amount\":250}";
    String updateJson = "{\"note\":\"Sale\",\"type\":\"EXPENSE\",\"amount\":99,\"version\":4}";
    backend.expect("POST", "/api/v1/finance-entries", createJson);
    backend.expect("POST", "/api/v1/finance-entries", createJson);
    backend.expect("PUT", "/api/v1/finance-entries/" + OTHER, updateJson);
    backend.expect("PUT", "/api/v1/finance-entries/" + OTHER, updateJson);
    backend.expect("DELETE", "/api/v1/finance-entries/" + OTHER);
  }
}
