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

package de.greluc.krt.profit.basetool.frontend.leadership.client;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendClientHarness;
import de.greluc.krt.profit.basetool.frontend.leadership.model.AddBereichLeaderRequest;
import de.greluc.krt.profit.basetool.frontend.leadership.model.AddOlMemberRequest;
import de.greluc.krt.profit.basetool.frontend.leadership.model.AssignSquadronRankRequest;
import de.greluc.krt.profit.basetool.frontend.leadership.model.BereichMemberResponse;
import de.greluc.krt.profit.basetool.frontend.leadership.model.CreateKommandoGroupRequest;
import de.greluc.krt.profit.basetool.frontend.leadership.model.GrandAdmiralRequest;
import de.greluc.krt.profit.basetool.frontend.leadership.model.KommandoGroupDto;
import de.greluc.krt.profit.basetool.frontend.leadership.model.OlMemberResponse;
import de.greluc.krt.profit.basetool.frontend.leadership.model.UpdateKommandoGroupRequest;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.MembershipLeadToggleRequest;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.OrgUnitMembershipDto;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the requests {@link LeadershipBackendClient} sends (plan F3), each the exact verb, path and
 * body the Leitung page sent before the client existed, and the typed answers.
 */
class LeadershipBackendClientTest {

  private static final UUID SQUADRON = UUID.fromString("e1f2a3b4-c5d6-4e7f-8a9b-0c1d2e3f4a5b");
  private static final UUID USER = UUID.fromString("f2a3b4c5-d6e7-4f8a-9b0c-1d2e3f4a5b6c");
  private static final UUID GROUP = UUID.fromString("a3b4c5d6-e7f8-4a9b-8c0d-2e3f4a5b6c7d");
  private static final UUID BEREICH = UUID.fromString("b4c5d6e7-f8a9-4b0c-9d1e-3f4a5b6c7d8e");
  private static final UUID OL = UUID.fromString("c5d6e7f8-a9b0-4c1d-8e2f-4a5b6c7d8e9f");
  private static final UUID SK = UUID.fromString("d6e7f8a9-b0c1-4d2e-9f3a-5b6c7d8e9f0a");

  private BackendClientHarness backend;
  private LeadershipBackendClient client;

  @BeforeEach
  void setUp() {
    backend = BackendClientHarness.start();
    client = new LeadershipBackendClient(backend.backendApiClient());
  }

  @AfterEach
  void tearDown() {
    backend.close();
  }

  /**
   * One membership as the backend serialises it.
   *
   * @param unit the org unit
   * @param kind the unit kind
   * @return the JSON
   */
  private static String membership(UUID unit, String kind) {
    return "{\"userId\":\""
        + USER
        + "\",\"userDisplayName\":\"Pilot\",\"orgUnitId\":\""
        + unit
        + "\",\"kind\":\""
        + kind
        + "\",\"isLogistician\":false,\"isMissionManager\":false,\"isLead\":true,"
        + "\"joinedAt\":null,\"version\":4}";
  }

  @Test
  void pageReads() {
    backend.answerJson(
        "{\"admin\":false,\"organisationsleitungen\":[],\"bereiche\":[],\"squadrons\":[],"
            + "\"specialCommands\":[]}");
    backend.answerJson("{}");
    backend.answerJson("[" + membership(SK, "SPECIAL_COMMAND") + "]");

    assertThat(client.leitungView()).isNotNull();
    assertThat(client.orgChart()).isNotNull();
    assertThat(client.specialCommandMembers(SK))
        .containsExactly(
            new OrgUnitMembershipDto(
                USER, "Pilot", SK, OrgUnitKind.SPECIAL_COMMAND, false, false, true, null, 4L));

    backend.expect("GET", "/api/v1/leitung/view");
    backend.expect("GET", "/api/v1/org-chart");
    backend.expect("GET", "/api/v1/special-commands/" + SK + "/members");
  }

  @Test
  void squadronRanksAndKommandoGroups() {
    backend.answerJson(membership(SQUADRON, "SQUADRON"));
    backend.answerJson(membership(SQUADRON, "SQUADRON"));
    String group =
        "{\"id\":\""
            + GROUP
            + "\",\"squadronId\":\""
            + SQUADRON
            + "\",\"name\":\"Alpha\","
            + "\"sortIndex\":1,\"version\":2}";
    backend.answerJson(group);
    backend.answerJson(group);
    backend.answerEmpty();

    OrgUnitMembershipDto assigned =
        client.assignSquadronRank(
            SQUADRON, USER, new AssignSquadronRankRequest("KOMMANDOLEITER", GROUP, 3L));
    client.removeSquadronRank(SQUADRON, USER, 4L);
    KommandoGroupDto created =
        client.createKommandoGroup(SQUADRON, new CreateKommandoGroupRequest("Alpha"));
    client.updateKommandoGroup(GROUP, new UpdateKommandoGroupRequest("Alpha", 1, 1L));
    client.deleteKommandoGroup(GROUP);

    assertThat(assigned.version()).isEqualTo(4L);
    assertThat(created).isEqualTo(new KommandoGroupDto(GROUP, SQUADRON, "Alpha", 1, 2L));
    backend.expect(
        "PUT",
        "/api/v1/squadrons/" + SQUADRON + "/ranks/" + USER,
        "{\"role\":\"KOMMANDOLEITER\",\"kommandoGroupId\":\"" + GROUP + "\",\"version\":3}");
    backend.expect("DELETE", "/api/v1/squadrons/" + SQUADRON + "/ranks/" + USER + "?version=4");
    backend.expect(
        "POST", "/api/v1/squadrons/" + SQUADRON + "/kommando-groups", "{\"name\":\"Alpha\"}");
    backend.expect(
        "PUT",
        "/api/v1/kommando-groups/" + GROUP,
        "{\"name\":\"Alpha\",\"sortIndex\":1,\"version\":1}");
    backend.expect("DELETE", "/api/v1/kommando-groups/" + GROUP, null);
  }

  @Test
  void bereichAndOrganisationsleitung() {
    backend.answerJson(
        "{\"bereichId\":\""
            + BEREICH
            + "\",\"userId\":\""
            + USER
            + "\",\"isBereichsleiter\":false,\"isBereichskoordinator\":true,"
            + "\"isBereichsoperator\":false,\"version\":1}");
    backend.answerEmpty();
    backend.answerJson(
        "{\"organisationsleitungId\":\""
            + OL
            + "\",\"userId\":\""
            + USER
            + "\",\"isOlMember\":true,\"version\":1}");
    backend.answerEmpty();
    backend.answerEmpty();
    backend.answerEmpty();

    BereichMemberResponse bereichMember =
        client.addBereichLeader(BEREICH, new AddBereichLeaderRequest(USER, "KOORDINATOR"));
    client.removeBereichLeader(BEREICH, USER);
    OlMemberResponse olMember = client.addOlMember(OL, new AddOlMemberRequest(USER));
    client.removeOlMember(OL, USER);
    client.setGrandAdmiral(OL, new GrandAdmiralRequest(USER, null));
    client.removeGrandAdmiral(OL);

    assertThat(bereichMember)
        .isEqualTo(new BereichMemberResponse(BEREICH, USER, false, true, false, 1L));
    assertThat(olMember).isEqualTo(new OlMemberResponse(OL, USER, true, 1L));
    backend.expect(
        "POST",
        "/api/v1/org-hierarchy/bereiche/" + BEREICH + "/members",
        "{\"userId\":\"" + USER + "\",\"role\":\"KOORDINATOR\"}");
    backend.expect(
        "DELETE", "/api/v1/org-hierarchy/bereiche/" + BEREICH + "/members/" + USER, null);
    backend.expect(
        "POST",
        "/api/v1/org-hierarchy/organisationsleitung/" + OL + "/members",
        "{\"userId\":\"" + USER + "\"}");
    backend.expect(
        "DELETE", "/api/v1/org-hierarchy/organisationsleitung/" + OL + "/members/" + USER, null);
    backend.expect(
        "PUT",
        "/api/v1/org-hierarchy/organisationsleitung/" + OL + "/grand-admiral",
        "{\"userId\":\"" + USER + "\",\"displayName\":null}");
    backend.expect(
        "DELETE", "/api/v1/org-hierarchy/organisationsleitung/" + OL + "/grand-admiral", null);
  }

  @Test
  void specialCommandLeadToggle() {
    backend.answerJson(membership(SK, "SPECIAL_COMMAND"));

    OrgUnitMembershipDto toggled =
        client.toggleSkLead(SK, USER, new MembershipLeadToggleRequest(true, 4L));

    assertThat(toggled.isLead()).isTrue();
    backend.expect(
        "PATCH",
        "/api/v1/special-commands/" + SK + "/members/" + USER + "/lead",
        "{\"isLead\":true,\"version\":4}");
  }
}
