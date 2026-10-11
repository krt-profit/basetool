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

package de.greluc.krt.profit.basetool.frontend.leadership.web;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.leadership.client.LeadershipBackendClient;
import de.greluc.krt.profit.basetool.frontend.leadership.model.AddBereichLeaderRequest;
import de.greluc.krt.profit.basetool.frontend.leadership.model.AssignSquadronRankRequest;
import de.greluc.krt.profit.basetool.frontend.leadership.model.BereichMemberResponse;
import de.greluc.krt.profit.basetool.frontend.leadership.model.CreateKommandoGroupRequest;
import de.greluc.krt.profit.basetool.frontend.leadership.model.KommandoGroupDto;
import de.greluc.krt.profit.basetool.frontend.leadership.model.LeitungMemberDto;
import de.greluc.krt.profit.basetool.frontend.leadership.model.LeitungUnitContext;
import de.greluc.krt.profit.basetool.frontend.leadership.model.LeitungUnitDto;
import de.greluc.krt.profit.basetool.frontend.leadership.model.LeitungViewDto;
import de.greluc.krt.profit.basetool.frontend.orgchart.model.BereichChartDto;
import de.greluc.krt.profit.basetool.frontend.orgchart.model.OrgChartDto;
import de.greluc.krt.profit.basetool.frontend.orgchart.model.SpecialCommandChartDto;
import de.greluc.krt.profit.basetool.frontend.orgchart.model.SquadronChartDto;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.MembershipLeadToggleRequest;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.OrgUnitMembershipDto;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

/**
 * Unit tests for {@link LeitungPageController}: the page loads the delegated view without a
 * preloaded user roster, and the AJAX write proxies relay the backend status and {@code {code,
 * detail}} body on failure.
 */
@SuppressWarnings("unchecked")
class LeitungPageControllerTest {

  private static LeitungViewDto emptyView() {
    return new LeitungViewDto(false, List.of(), List.of(), List.of(), List.of());
  }

  @Test
  void leitung_loadsView_doesNotPreloadUserRoster() {
    BackendApiClient backend = mock(BackendApiClient.class);
    LeitungPageController controller =
        new LeitungPageController(new LeadershipBackendClient(backend));
    LeitungViewDto view = emptyView();
    when(backend.get("/api/v1/leitung/view", LeitungViewDto.class)).thenReturn(view);
    Model model = new ConcurrentModel();

    String result = controller.leitung(null, null, null, model);

    assertEquals("organisation/leitung", result);
    assertSame(view, model.getAttribute("leitung"));
    assertNull(model.getAttribute("allUsers"));
    verify(backend, never()).get(eq("/api/v1/users/lookup"), anyTypeRef());
  }

  @Test
  void leitung_fragment_returnsFragmentSelector() {
    BackendApiClient backend = mock(BackendApiClient.class);
    LeitungPageController controller =
        new LeitungPageController(new LeadershipBackendClient(backend));
    when(backend.get("/api/v1/leitung/view", LeitungViewDto.class)).thenReturn(emptyView());
    Model model = new ConcurrentModel();

    String result = controller.leitung("leitungSections", null, null, model);

    assertEquals("organisation/leitung :: leitungSections", result);
    verify(backend, never()).get(eq("/api/v1/users/lookup"), anyTypeRef());
  }

  /**
   * A unit of the given kind with the given caps and roster.
   *
   * @param id the unit id
   * @param kind the unit kind
   * @param canAppointLead the lead-appointment cap
   * @param canManageRoster the roster cap
   * @param members the roster
   * @return the unit
   */
  private static LeitungUnitDto unit(
      UUID id,
      OrgUnitKind kind,
      boolean canAppointLead,
      boolean canManageRoster,
      List<LeitungMemberDto> members) {
    return new LeitungUnitDto(
        id, "Unit " + id, "U", kind, canAppointLead, canManageRoster, members, List.of(), null);
  }

  @Test
  void leitung_requestedUnitListed_isSelected_otherwiseFirstUnit() {
    BackendApiClient backend = mock(BackendApiClient.class);
    LeitungPageController controller =
        new LeitungPageController(new LeadershipBackendClient(backend));
    UUID squadron = UUID.randomUUID();
    UUID sk = UUID.randomUUID();
    when(backend.get("/api/v1/leitung/view", LeitungViewDto.class))
        .thenReturn(
            new LeitungViewDto(
                false,
                List.of(),
                List.of(),
                List.of(unit(squadron, OrgUnitKind.SQUADRON, false, true, List.of())),
                List.of(unit(sk, OrgUnitKind.SPECIAL_COMMAND, true, false, List.of()))));

    Model requested = new ConcurrentModel();
    controller.leitung(null, sk.toString(), "groups", requested);
    Model unknown = new ConcurrentModel();
    controller.leitung(null, UUID.randomUUID().toString(), "bogus", unknown);

    assertEquals(sk.toString(), requested.getAttribute("selectedUnitId"));
    assertEquals("groups", requested.getAttribute("selectedTab"));
    assertEquals(squadron.toString(), unknown.getAttribute("selectedUnitId"));
    assertEquals("members", unknown.getAttribute("selectedTab"));
  }

  @Test
  void leitung_orgChart_placesUnitsUnderTheirBereich() {
    BackendApiClient backend = mock(BackendApiClient.class);
    LeitungPageController controller =
        new LeitungPageController(new LeadershipBackendClient(backend));
    UUID bereich = UUID.randomUUID();
    UUID squadron = UUID.randomUUID();
    UUID sk = UUID.randomUUID();
    when(backend.get("/api/v1/leitung/view", LeitungViewDto.class)).thenReturn(emptyView());
    when(backend.get("/api/v1/org-chart", OrgChartDto.class))
        .thenReturn(
            new OrgChartDto(
                null,
                List.of(
                    new BereichChartDto(
                        bereich,
                        "Logistik",
                        "LOG",
                        "SEARCH_RESCUE",
                        null,
                        List.of(
                            new SquadronChartDto(
                                squadron, "IRIDIUM", "IRI", null, List.of(), List.of(), false,
                                false)),
                        List.of(
                            new SpecialCommandChartDto(sk, "Bergung", "BRG", List.of(), false)))),
                null,
                List.of(),
                List.of()));
    Model model = new ConcurrentModel();

    controller.leitung(null, null, null, model);

    Map<UUID, LeitungUnitContext> context =
        assertInstanceOf(Map.class, model.getAttribute("unitContext"));
    assertNull(context.get(bereich).bereichName());
    assertEquals("search-rescue", context.get(bereich).departmentModifier());
    assertEquals("Logistik", context.get(squadron).bereichName());
    assertEquals("Logistik", context.get(sk).bereichName());
  }

  @Test
  void leitung_orgChartFails_rendersWithoutContext() {
    BackendApiClient backend = mock(BackendApiClient.class);
    LeitungPageController controller =
        new LeitungPageController(new LeadershipBackendClient(backend));
    when(backend.get("/api/v1/leitung/view", LeitungViewDto.class)).thenReturn(emptyView());
    when(backend.get("/api/v1/org-chart", OrgChartDto.class))
        .thenThrow(new BackendServiceException("down", null, 503));
    Model model = new ConcurrentModel();

    String result = controller.leitung(null, null, null, model);

    assertEquals("organisation/leitung", result);
    assertTrue(assertInstanceOf(Map.class, model.getAttribute("unitContext")).isEmpty());
    assertNull(model.getAttribute("error"));
  }

  @Test
  void leitung_skRoster_readsFlagsOnlyWithTheRosterCap() {
    BackendApiClient backend = mock(BackendApiClient.class);
    LeitungPageController controller =
        new LeitungPageController(new LeadershipBackendClient(backend));
    UUID managed = UUID.randomUUID();
    UUID leadOnly = UUID.randomUUID();
    UUID pilot = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    when(backend.get("/api/v1/leitung/view", LeitungViewDto.class))
        .thenReturn(
            new LeitungViewDto(
                false,
                List.of(),
                List.of(),
                List.of(),
                List.of(
                    unit(
                        managed,
                        OrgUnitKind.SPECIAL_COMMAND,
                        false,
                        true,
                        List.of(
                            new LeitungMemberDto(lead, "Lead", "SK_LEAD", null, 1L, true),
                            new LeitungMemberDto(pilot, "Pilot", "MEMBER", null, 2L, false))),
                    unit(
                        leadOnly,
                        OrgUnitKind.SPECIAL_COMMAND,
                        true,
                        false,
                        List.of(
                            new LeitungMemberDto(pilot, "Pilot", "MEMBER", null, 0L, false))))));
    when(backend.get(eq("/api/v1/special-commands/{id}/members"), anyTypeRef(), eq(managed)))
        .thenReturn(
            List.of(
                new OrgUnitMembershipDto(
                    pilot,
                    "Pilot",
                    managed,
                    OrgUnitKind.SPECIAL_COMMAND,
                    true,
                    false,
                    false,
                    null,
                    5L)));
    Model model = new ConcurrentModel();

    controller.leitung(null, null, null, model);

    Set<UUID> known = assertInstanceOf(Set.class, model.getAttribute("skFlagsKnown"));
    assertTrue(known.contains(managed));
    assertFalse(known.contains(leadOnly));
    verify(backend, never())
        .get(eq("/api/v1/special-commands/{id}/members"), anyTypeRef(), eq(leadOnly));
    Map<UUID, List<OrgUnitMembershipDto>> rosters =
        assertInstanceOf(Map.class, model.getAttribute("skRosters"));
    List<OrgUnitMembershipDto> rows = rosters.get(managed);
    assertEquals(2, rows.size());
    assertTrue(rows.get(0).isLead());
    assertFalse(rows.get(0).isLogistician());
    assertEquals(1L, rows.get(0).version());
    assertTrue(rows.get(1).isLogistician());
    assertEquals(5L, rows.get(1).version());
    assertEquals(lead, model.getAttribute("selfUserId"));
  }

  @Test
  void leitung_backendFailure_setsErrorAttribute() {
    BackendApiClient backend = mock(BackendApiClient.class);
    LeitungPageController controller =
        new LeitungPageController(new LeadershipBackendClient(backend));
    when(backend.get("/api/v1/leitung/view", LeitungViewDto.class))
        .thenThrow(new BackendServiceException("boom", null, 503));
    Model model = new ConcurrentModel();

    String result = controller.leitung(null, null, null, model);

    assertEquals("organisation/leitung", result);
    assertEquals("leitung.error.load", model.getAttribute("error"));
  }

  @Test
  void assignSquadronRank_success_returns200() {
    BackendApiClient backend = mock(BackendApiClient.class);
    LeitungPageController controller =
        new LeitungPageController(new LeadershipBackendClient(backend));
    UUID squadronId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    when(backend.put(
            eq("/api/v1/squadrons/{squadronId}/ranks/{userId}"),
            any(),
            eq(OrgUnitMembershipDto.class),
            eq(squadronId),
            eq(userId)))
        .thenReturn(membership(userId, squadronId));

    ResponseEntity<Object> response =
        controller.assignSquadronRank(
            squadronId, userId, new AssignSquadronRankRequest("KOMMANDOLEITER", null, 0L));

    assertEquals(200, response.getStatusCode().value());
  }

  @Test
  void assignSquadronRank_optimisticLock_relays409() {
    BackendApiClient backend = mock(BackendApiClient.class);
    LeitungPageController controller =
        new LeitungPageController(new LeadershipBackendClient(backend));
    UUID squadronId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    when(backend.put(
            eq("/api/v1/squadrons/{squadronId}/ranks/{userId}"),
            any(),
            eq(OrgUnitMembershipDto.class),
            eq(squadronId),
            eq(userId)))
        .thenThrow(
            new BackendServiceException(
                "conflict", null, 409, "OPTIMISTIC_LOCK", null, List.of(), "Stale."));

    ResponseEntity<Object> response =
        controller.assignSquadronRank(
            squadronId, userId, new AssignSquadronRankRequest("ENSIGN", null, 0L));

    assertEquals(409, response.getStatusCode().value());
    Map<String, Object> body = assertInstanceOf(Map.class, response.getBody());
    assertEquals("OPTIMISTIC_LOCK", body.get("code"));
  }

  @Test
  void removeSquadronRank_relaysVersionToBackend() {
    BackendApiClient backend = mock(BackendApiClient.class);
    LeitungPageController controller =
        new LeitungPageController(new LeadershipBackendClient(backend));
    UUID squadronId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();

    ResponseEntity<Object> response = controller.removeSquadronRank(squadronId, userId, 4L);

    assertEquals(200, response.getStatusCode().value());
    verify(backend)
        .delete(
            "/api/v1/squadrons/{squadronId}/ranks/{userId}?version={version}",
            OrgUnitMembershipDto.class,
            squadronId,
            userId,
            4L);
  }

  @Test
  void createKommandoGroup_success_returns200() {
    BackendApiClient backend = mock(BackendApiClient.class);
    LeitungPageController controller =
        new LeitungPageController(new LeadershipBackendClient(backend));
    UUID squadronId = UUID.randomUUID();
    when(backend.post(
            eq("/api/v1/squadrons/{squadronId}/kommando-groups"),
            any(),
            eq(KommandoGroupDto.class),
            eq(squadronId)))
        .thenReturn(
            new KommandoGroupDto(
                UUID.fromString("5e6f7a8b-9c0d-4e1f-a2b3-c4d5e6f7a8b9"),
                squadronId,
                "Alpha",
                0,
                0L));

    ResponseEntity<Object> response =
        controller.createKommandoGroup(squadronId, new CreateKommandoGroupRequest("Alpha"));

    assertEquals(200, response.getStatusCode().value());
  }

  @Test
  void addBereichLeader_backendForbidden_relays403() {
    BackendApiClient backend = mock(BackendApiClient.class);
    LeitungPageController controller =
        new LeitungPageController(new LeadershipBackendClient(backend));
    UUID bereichId = UUID.randomUUID();
    when(backend.post(
            eq("/api/v1/org-hierarchy/bereiche/{bereichId}/members"),
            any(),
            eq(BereichMemberResponse.class),
            eq(bereichId)))
        .thenThrow(
            new BackendServiceException(
                "denied", null, 403, "ACCESS_DENIED", null, List.of(), "No."));

    ResponseEntity<Object> response =
        controller.addBereichLeader(
            bereichId, new AddBereichLeaderRequest(UUID.randomUUID(), "KOORDINATOR"));

    assertEquals(403, response.getStatusCode().value());
    assertTrue(((Map<String, Object>) response.getBody()).containsKey("code"));
  }

  @Test
  void toggleSkLead_relaysToBackend() {
    BackendApiClient backend = mock(BackendApiClient.class);
    LeitungPageController controller =
        new LeitungPageController(new LeadershipBackendClient(backend));
    UUID skId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    when(backend.patch(
            eq("/api/v1/special-commands/{skId}/members/{userId}/lead"),
            any(),
            eq(OrgUnitMembershipDto.class),
            eq(skId),
            eq(userId)))
        .thenReturn(membership(userId, skId));

    ResponseEntity<Object> response =
        controller.toggleSkLead(skId, userId, new MembershipLeadToggleRequest(true, 0L));

    assertEquals(200, response.getStatusCode().value());
  }

  @Test
  void removeOlMember_success_returns200() {
    BackendApiClient backend = mock(BackendApiClient.class);
    LeitungPageController controller =
        new LeitungPageController(new LeadershipBackendClient(backend));
    UUID olId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();

    ResponseEntity<Object> response = controller.removeOlMember(olId, userId);

    assertEquals(200, response.getStatusCode().value());
    verify(backend)
        .delete(
            "/api/v1/org-hierarchy/organisationsleitung/{olId}/members/{userId}",
            Void.class,
            olId,
            userId);
  }

  private static OrgUnitMembershipDto membership(UUID userId, UUID orgUnitId) {
    return new OrgUnitMembershipDto(
        userId, "Pilot", orgUnitId, OrgUnitKind.SQUADRON, false, false, false, null, 1L);
  }
}
