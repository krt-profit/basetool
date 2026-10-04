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

import de.greluc.krt.profit.basetool.frontend.model.dto.CreateMissionRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.UpdatePayoutPreferenceRequest;
import de.greluc.krt.profit.basetool.frontend.service.BackendClientHarness;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.util.DefaultUriBuilderFactory;

/**
 * Pins the requests {@link MissionBackendClient} sends (plan F3), each the exact request the
 * mission controllers sent before the client existed.
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
    backend.answerJson("[]");
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
    assertThat(client.unassignedParticipants(ID)).isEqualTo(List.of());
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
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson("[]");
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);

    assertThat(client.missionJobTypes().content()).isEmpty();
    assertThat(client.crewJobTypes().content()).isEmpty();
    assertThat(client.squadrons().content()).isEmpty();
    assertThat(client.activeOrgUnits()).isEmpty();
    assertThat(client.frequencyTypes().content()).isEmpty();
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
    Map<String, Object> body = body();
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

    client.addParticipant(ID, body);
    client.updateParticipant(ID, OTHER, body);
    client.deleteParticipant(ID, OTHER);
    client.checkInParticipant(ID, OTHER);
    assertThat(client.addParticipantJson(ID, body)).isEqualTo(List.of());
    assertThat(client.updateParticipantJson(ID, OTHER, body)).isEqualTo(Map.of());
    client.checkOutParticipant(ID, OTHER);
    client.deleteParticipant(ID, OTHER);
    assertThat(client.checkInParticipantJson(ID, OTHER)).isEqualTo(Map.of());
    assertThat(client.checkOutParticipantJson(ID, OTHER)).isEqualTo(Map.of());
    assertThat(client.updatePayoutPreference(ID, OTHER, new UpdatePayoutPreferenceRequest(null)))
        .isEqualTo(Map.of());

    String json = bodyJson();
    String participant = M + "/participants/" + OTHER;
    backend.expect("POST", M + "/participants/add", json);
    backend.expect("PUT", participant + "/slim", json);
    backend.expect("DELETE", participant + "/slim");
    backend.expect("POST", participant + "/check-in/slim", null);
    backend.expect("POST", M + "/participants/slim", json);
    backend.expect("PUT", participant + "/slim", json);
    backend.expect("POST", participant + "/check-out/slim", null);
    backend.expect("DELETE", participant + "/slim");
    backend.expect("POST", participant + "/check-in/slim", null);
    backend.expect("POST", participant + "/check-out/slim", null);
    backend.expect("PUT", participant + "/payout-preference/slim", "{\"preference\":null}");
  }

  @Test
  void sectionAndOwnershipWrites() {
    Map<String, Object> body = body();
    for (int i = 0; i < 9; i++) {
      backend.answerEmpty();
    }
    backend.answerJson("{\"id\":\"" + ID + "\"}");
    CreateMissionRequest create =
        new CreateMissionRequest(
            "Op", null, null, "PLANNED", null, null, null, false, null, null, null, null, null);

    client.setPartyLead(ID, body);
    client.setOwner(ID, body);
    client.setOwningOrgUnit(ID, body);
    client.patchSchedule(ID, body);
    client.patchCore(ID, body);
    client.patchFlags(ID, body);
    client.deleteMission(ID);
    client.addManager(ID, OTHER);
    client.removeManager(ID, OTHER);
    assertThat(client.createMission(create).id()).isEqualTo(ID);

    String json = bodyJson();
    backend.expect("PUT", M + "/party-lead", json);
    backend.expect("PUT", M + "/owner", json);
    backend.expect("PUT", M + "/owning-org-unit", json);
    backend.expect("PATCH", M + "/schedule", json);
    backend.expect("PATCH", M + "/core", json);
    backend.expect("PATCH", M + "/flags", json);
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
    Map<String, Object> body = body();
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

    client.addUnit(ID, body);
    assertThat(client.addUnitJson(ID, body)).isEqualTo(List.of());
    client.updateUnit(ID, OTHER, body);
    assertThat(client.updateUnitJson(ID, OTHER, body)).isEqualTo(Map.of());
    client.deleteUnit(ID, OTHER);
    client.addCrew(ID, OTHER, body);
    assertThat(client.addCrewJson(ID, OTHER, body)).isEqualTo(List.of());
    client.updateCrew(ID, OTHER, THIRD, body);
    assertThat(client.updateCrewJson(ID, OTHER, THIRD, body)).isEqualTo(Map.of());
    client.deleteCrew(ID, OTHER, THIRD);

    String json = bodyJson();
    String unit = M + "/units/" + OTHER;
    backend.expect("POST", M + "/units/slim", json);
    backend.expect("POST", M + "/units/slim", json);
    backend.expect("PUT", unit + "/slim", json);
    backend.expect("PUT", unit + "/slim", json);
    backend.expect("DELETE", unit + "/slim");
    backend.expect("POST", unit + "/crew/slim", json);
    backend.expect("POST", unit + "/crew/slim", json);
    backend.expect("PUT", unit + "/crew/" + THIRD + "/slim", json);
    backend.expect("PUT", unit + "/crew/" + THIRD + "/slim", json);
    backend.expect("DELETE", unit + "/crew/" + THIRD + "/slim");
  }

  @Test
  void frequencyStepAndObjectiveWrites() {
    Map<String, Object> body = body();
    backend.answerEmpty();
    backend.answerJson("[]");
    backend.answerEmpty();
    for (int i = 0; i < 12; i++) {
      backend.answerJson("[]");
    }

    client.putFrequency(ID, body);
    assertThat(client.putFrequencyJson(ID, body)).isEqualTo(List.of());
    client.deleteFrequency(ID, OTHER);
    assertThat(client.deleteFrequencyJson(ID, OTHER)).isEqualTo(List.of());
    client.addCustomFrequency(ID, body);
    client.updateCustomFrequency(ID, OTHER, body);
    client.addStep(ID, body);
    client.updateStep(ID, OTHER, body);
    client.deleteStep(ID, OTHER, 7L);
    client.reorderSteps(ID, body);
    client.setStepDone(ID, OTHER, body);
    client.addObjective(ID, body);
    client.updateObjective(ID, OTHER, body);
    client.deleteObjective(ID, OTHER, 8L);
    client.reorderObjectives(ID, body);

    String json = bodyJson();
    backend.expect("POST", M + "/frequencies/slim", json);
    backend.expect("POST", M + "/frequencies/slim", json);
    backend.expect("DELETE", M + "/frequencies/" + OTHER + "/slim");
    backend.expect("DELETE", M + "/frequencies/" + OTHER + "/slim");
    backend.expect("POST", M + "/frequencies/custom/slim", json);
    backend.expect("PUT", M + "/frequencies/custom/" + OTHER + "/slim", json);
    backend.expect("POST", M + "/steps/slim", json);
    backend.expect("PUT", M + "/steps/" + OTHER + "/slim", json);
    backend.expect("DELETE", M + "/steps/" + OTHER + "/slim?stepsVersion=7");
    backend.expect("PUT", M + "/steps/reorder/slim", json);
    backend.expect("PATCH", M + "/steps/" + OTHER + "/done/slim", json);
    backend.expect("POST", M + "/objectives/slim", json);
    backend.expect("PUT", M + "/objectives/" + OTHER + "/slim", json);
    backend.expect("DELETE", M + "/objectives/" + OTHER + "/slim?objectivesVersion=8");
    backend.expect("PUT", M + "/objectives/reorder/slim", json);
  }

  @Test
  void financeEntryWrites() {
    Map<String, Object> body = body();
    backend.answerEmpty();
    backend.answerJson("{}");
    backend.answerEmpty();
    backend.answerJson("{}");
    backend.answerEmpty();

    client.addFinanceEntry(body);
    assertThat(client.addFinanceEntryJson(body)).isEqualTo(Map.of());
    client.updateFinanceEntry(OTHER, body);
    assertThat(client.updateFinanceEntryJson(OTHER, body)).isEqualTo(Map.of());
    client.deleteFinanceEntry(OTHER);

    String json = bodyJson();
    backend.expect("POST", "/api/v1/finance-entries", json);
    backend.expect("POST", "/api/v1/finance-entries", json);
    backend.expect("PUT", "/api/v1/finance-entries/" + OTHER, json);
    backend.expect("PUT", "/api/v1/finance-entries/" + OTHER, json);
    backend.expect("DELETE", "/api/v1/finance-entries/" + OTHER);
  }

  /**
   * Builds the relayed body every write test sends.
   *
   * @return an ordered body with an id, a text, a missing value and a version
   */
  private static Map<String, Object> body() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("userId", THIRD);
    body.put("name", "Alpha");
    body.put("note", null);
    body.put("version", 4L);
    return body;
  }

  /**
   * Spells the JSON {@link #body()} serializes to.
   *
   * @return the exact request body
   */
  private static String bodyJson() {
    return "{\"userId\":\"" + THIRD + "\",\"name\":\"Alpha\",\"note\":null,\"version\":4}";
  }
}
