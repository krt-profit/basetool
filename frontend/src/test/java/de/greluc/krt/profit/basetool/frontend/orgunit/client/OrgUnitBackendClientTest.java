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

package de.greluc.krt.profit.basetool.frontend.orgunit.client;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.orgunit.model.BereichCreateRequest;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.BereichDto;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.MembershipFlagsPatchRequest;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.MembershipLeadToggleRequest;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.OrgUnitMembershipDto;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.OrgUnitParentResponse;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.OrgUnitParentUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.OrganisationsleitungCreateRequest;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.OrganisationsleitungDto;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.SpecialCommandDto;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.SpecialCommandProfitEligibleToggleRequest;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.SquadronProfitEligibleToggleRequest;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.SquadronPromotionToggleRequest;
import de.greluc.krt.profit.basetool.frontend.service.BackendClientHarness;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the requests {@link OrgUnitBackendClient} sends (plan F3), each the exact verb, path and
 * body the org-unit controllers sent before the client existed, and the typed answers.
 */
class OrgUnitBackendClientTest {

  private static final UUID SK = UUID.fromString("a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d");
  private static final UUID USER = UUID.fromString("b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e");
  private static final UUID OL = UUID.fromString("c3d4e5f6-a7b8-4c9d-8e0f-2a3b4c5d6e7f");
  private static final UUID UNIT = UUID.fromString("d4e5f6a7-b8c9-4d0e-9f1a-3b4c5d6e7f80");
  private static final String EMPTY_PAGE =
      "{\"content\":[],\"page\":0,\"size\":1000,\"totalElements\":0,\"totalPages\":0}";

  private BackendClientHarness backend;
  private OrgUnitBackendClient client;

  @BeforeEach
  void setUp() {
    backend = BackendClientHarness.start();
    client = new OrgUnitBackendClient(backend.backendApiClient());
  }

  @AfterEach
  void tearDown() {
    backend.close();
  }

  @Test
  void hierarchyReadAndWrites() {
    backend.answerJson("[]");
    backend.answerJson(
        "{\"id\":\""
            + UNIT
            + "\",\"name\":\"Profit\",\"shorthand\":\"PRF\",\"description\":null,"
            + "\"active\":true,\"parentOrgUnitId\":\""
            + OL
            + "\",\"department\":\"PROFIT\",\"version\":0}");
    backend.answerJson(
        "{\"id\":\""
            + OL
            + "\",\"name\":\"Leitung\",\"shorthand\":\"OL\",\"description\":null,"
            + "\"active\":true,\"version\":0}");
    backend.answerJson(
        "{\"orgUnitId\":\""
            + UNIT
            + "\",\"kind\":\"BEREICH\",\"parentOrgUnitId\":\""
            + OL
            + "\",\"version\":2}");

    assertThat(client.orgUnitNodes()).isEmpty();
    BereichDto bereich =
        client.createBereich(new BereichCreateRequest("Profit", "PRF", null, OL, "PROFIT"));
    OrganisationsleitungDto ol =
        client.createOrganisationsleitung(
            new OrganisationsleitungCreateRequest("Leitung", "OL", null));
    OrgUnitParentResponse parent = client.setParent(UNIT, new OrgUnitParentUpdateRequest(OL, 1L));

    assertThat(bereich)
        .isEqualTo(new BereichDto(UNIT, "Profit", "PRF", null, true, OL, "PROFIT", 0L));
    assertThat(ol).isEqualTo(new OrganisationsleitungDto(OL, "Leitung", "OL", null, true, 0L));
    assertThat(parent).isEqualTo(new OrgUnitParentResponse(UNIT, "BEREICH", OL, 2L));
    backend.expect("GET", "/api/v1/org-hierarchy/org-units");
    backend.expect(
        "POST",
        "/api/v1/org-hierarchy/bereiche",
        "{\"name\":\"Profit\",\"shorthand\":\"PRF\",\"description\":null,\"parentOrgUnitId\":\""
            + OL
            + "\",\"department\":\"PROFIT\"}");
    backend.expect(
        "POST",
        "/api/v1/org-hierarchy/organisationsleitung",
        "{\"name\":\"Leitung\",\"shorthand\":\"OL\",\"description\":null}");
    backend.expect(
        "PATCH",
        "/api/v1/org-hierarchy/org-units/" + UNIT + "/parent",
        "{\"parentOrgUnitId\":\"" + OL + "\",\"version\":1}");
  }

  @Test
  void specialCommandLifecycle() {
    backend.answerJson(EMPTY_PAGE);
    backend.answerEmpty();
    backend.answerEmpty();
    backend.answerEmpty();
    backend.answerEmpty();
    backend.answerEmpty();
    SpecialCommandDto body = new SpecialCommandDto(SK, "Bergung", "BRG", null, true, false, 4L);

    client.specialCommandPage(true, 2);
    client.createSpecialCommand(body);
    client.updateSpecialCommand(SK, body);
    client.deleteSpecialCommand(SK);
    client.activateSpecialCommand(SK);
    client.toggleSpecialCommandLead(SK, USER, new MembershipLeadToggleRequest(true, 3L));

    String json =
        "{\"id\":\""
            + SK
            + "\",\"name\":\"Bergung\",\"shorthand\":\"BRG\",\"description\":null,"
            + "\"active\":true,\"isProfitEligible\":false,\"version\":4}";
    backend.expect(
        "GET", "/api/v1/special-commands?size=1000&sort=name,asc&includeInactive=true&page=2");
    backend.expect("POST", "/api/v1/special-commands", json);
    backend.expect("PUT", "/api/v1/special-commands/" + SK, json);
    backend.expect("DELETE", "/api/v1/special-commands/" + SK, null);
    backend.expect("POST", "/api/v1/special-commands/" + SK + "/activate", null);
    backend.expect(
        "PATCH",
        "/api/v1/special-commands/" + SK + "/members/" + USER + "/lead",
        "{\"isLead\":true,\"version\":3}");
  }

  @Test
  void specialCommandRoster() {
    backend.answerJson(
        "{\"id\":\""
            + SK
            + "\",\"name\":\"Bergung\",\"shorthand\":\"BRG\",\"description\":null,"
            + "\"active\":true,\"isProfitEligible\":null,\"version\":4}");
    backend.answerJson(
        "[{\"userId\":\""
            + USER
            + "\",\"userDisplayName\":\"Pilot\",\"orgUnitId\":\""
            + SK
            + "\",\"kind\":\"SPECIAL_COMMAND\",\"isLogistician\":true,\"isMissionManager\":false,"
            + "\"isLead\":false,\"joinedAt\":\"2026-09-01T10:15:30Z\",\"version\":7}]");
    backend.answerEmpty();
    backend.answerEmpty();
    backend.answerEmpty();

    SpecialCommandDto sc = client.specialCommand(SK);
    assertThat(client.specialCommandMembers(SK))
        .containsExactly(
            new OrgUnitMembershipDto(
                USER,
                "Pilot",
                SK,
                OrgUnitKind.SPECIAL_COMMAND,
                true,
                false,
                false,
                Instant.parse("2026-09-01T10:15:30Z"),
                7L));
    client.addSpecialCommandMember(SK, USER);
    client.removeSpecialCommandMember(SK, USER);
    client.patchSpecialCommandMemberFlags(
        SK, USER, new MembershipFlagsPatchRequest(true, false, 7L));

    assertThat(sc).isEqualTo(new SpecialCommandDto(SK, "Bergung", "BRG", null, true, null, 4L));
    backend.expect("GET", "/api/v1/special-commands/" + SK);
    backend.expect("GET", "/api/v1/special-commands/" + SK + "/members");
    backend.expect("POST", "/api/v1/special-commands/" + SK + "/members/" + USER, null);
    backend.expect("DELETE", "/api/v1/special-commands/" + SK + "/members/" + USER, null);
    backend.expect(
        "PATCH",
        "/api/v1/special-commands/" + SK + "/members/" + USER,
        "{\"isLogistician\":true,\"isMissionManager\":false,\"version\":7}");
  }

  @Test
  void adminToggles() {
    backend.answerEmpty();
    backend.answerEmpty();
    backend.answerEmpty();

    client.setSpecialCommandProfitEligible(SK, new SpecialCommandProfitEligibleToggleRequest(true));
    client.setSquadronPromotionEnabled(UNIT, new SquadronPromotionToggleRequest(false));
    client.setSquadronProfitEligible(UNIT, new SquadronProfitEligibleToggleRequest(true));

    backend.expect(
        "PATCH", "/api/v1/special-commands/" + SK + "/profit-eligible", "{\"eligible\":true}");
    backend.expect(
        "PATCH", "/api/v1/squadrons/" + UNIT + "/promotion-enabled", "{\"enabled\":false}");
    backend.expect(
        "PATCH", "/api/v1/squadrons/" + UNIT + "/profit-eligible", "{\"eligible\":true}");
  }
}
