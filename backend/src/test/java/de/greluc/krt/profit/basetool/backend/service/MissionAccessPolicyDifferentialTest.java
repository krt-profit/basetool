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

package de.greluc.krt.profit.basetool.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.StaffelMembershipResolver;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The differential verdict test of the mission access policy (plan §5.4, ADR-0236): over one
 * fixture matrix of callers and missions, {@link MissionAccessPolicy} returns exactly the verdict
 * of the scope hub's former mission gates for the read and the edit gate.
 *
 * <p>{@link ScopeHubMissionGates} restates those gates as {@link AccessGateService} decided them
 * before they moved, on the scope kernel's primitives, so the comparison outlives their deletion.
 *
 * <p>The matrix covers an admin unpinned and pinned to either unit, a pinned and an unpinned
 * member, members of zero, one and two units and a Bereich lead reaching a child Staffel through
 * the cascade, each with and without member-or-above standing; and missions of either unit, of the
 * child unit and without an owning unit, each public and internal, without a parent, below an
 * internal mission of a foreign unit and below a public one, plus an unknown mission.
 */
class MissionAccessPolicyDifferentialTest {

  private static final UUID UNIT_A = UUID.randomUUID();

  private static final UUID UNIT_B = UUID.randomUUID();

  private static final UUID CHILD_C = UUID.randomUUID();

  private static final UUID BEREICH_X = UUID.randomUUID();

  private static final int MISSION_CASES = 4 * 2 * 3 + 1;

  /**
   * One caller of the matrix.
   *
   * @param name the scenario label
   * @param admin whether the caller holds ROLE_ADMIN
   * @param memberships the org units the caller is a direct member of
   * @param pin the active-context pin header, or {@code null}
   * @param memberOrAbove whether the caller holds member-or-above standing
   */
  private record Caller(
      String name, boolean admin, List<UUID> memberships, UUID pin, boolean memberOrAbove) {}

  /**
   * One mission gate, as the scope hub and as the policy decide it.
   *
   * @param name the gate's method name
   * @param scopeHub the restated scope hub's verdict
   * @param policy the policy's verdict
   */
  private record Gate(
      String name,
      BiPredicate<ScopeHubMissionGates, UUID> scopeHub,
      BiPredicate<MissionAccessPolicy, UUID> policy) {}

  @Test
  void thePolicyReturnsTheScopeHubsVerdictForEveryCallerMissionAndGate() {
    List<Caller> callers = new ArrayList<>();
    for (boolean memberOrAbove : List.of(true, false)) {
      String suffix = memberOrAbove ? "" : " below member";
      callers.add(new Caller("admin unpinned" + suffix, true, List.of(), null, memberOrAbove));
      callers.add(new Caller("admin pinned A" + suffix, true, List.of(), UNIT_A, memberOrAbove));
      callers.add(new Caller("admin pinned B" + suffix, true, List.of(), UNIT_B, memberOrAbove));
      callers.add(new Caller("member of none" + suffix, false, List.of(), null, memberOrAbove));
      callers.add(new Caller("member of A" + suffix, false, List.of(UNIT_A), null, memberOrAbove));
      callers.add(
          new Caller(
              "member of A and B" + suffix, false, List.of(UNIT_A, UNIT_B), null, memberOrAbove));
      callers.add(
          new Caller(
              "member of A and B pinned B" + suffix,
              false,
              List.of(UNIT_A, UNIT_B),
              UNIT_B,
              memberOrAbove));
      callers.add(
          new Caller(
              "member of A pinned foreign B" + suffix,
              false,
              List.of(UNIT_A),
              UNIT_B,
              memberOrAbove));
      callers.add(
          new Caller(
              "Bereich lead over C" + suffix, false, List.of(BEREICH_X), null, memberOrAbove));
    }

    int compared = 0;
    int granted = 0;
    for (Caller caller : callers) {
      Fixture fixture = new Fixture(caller);
      for (Map.Entry<String, UUID> mission : fixture.missions.entrySet()) {
        for (Gate gate : gates()) {
          boolean old = gate.scopeHub().test(fixture.scopeHub, mission.getValue());
          boolean now = gate.policy().test(fixture.policy, mission.getValue());
          assertThat(now)
              .as("%s on %s for %s", gate.name(), mission.getKey(), caller.name())
              .isEqualTo(old);
          compared++;
          granted += now ? 1 : 0;
        }
      }
    }

    assertThat(compared).isEqualTo(callers.size() * MISSION_CASES * gates().size());
    assertThat(granted).isBetween(1, compared - 1);
  }

  private static List<Gate> gates() {
    return List.of(
        new Gate(
            "canSeeMission",
            ScopeHubMissionGates::canSeeMission,
            MissionAccessPolicy::canSeeMission),
        new Gate(
            "canEditMission",
            ScopeHubMissionGates::canEditMission,
            MissionAccessPolicy::canEditMission));
  }

  /** One caller's freshly wired scope hub and policy over the scenario mocks. */
  private static final class Fixture {

    private final ScopeHubMissionGates scopeHub;

    private final MissionAccessPolicy policy;

    private final Map<String, UUID> missions = new LinkedHashMap<>();

    Fixture(Caller caller) {
      AuthHelperService authHelper = mock(AuthHelperService.class);
      when(authHelper.isAuthenticated()).thenReturn(true);
      when(authHelper.isAdmin()).thenReturn(caller.admin());
      when(authHelper.isMemberOrAbove()).thenReturn(caller.memberOrAbove());
      when(authHelper.currentUserId()).thenReturn(Optional.of(UUID.randomUUID()));

      OrgUnitMembershipRepository memberships = mock(OrgUnitMembershipRepository.class);
      List<OrgUnitMembership> callerRows = rows(caller.memberships());
      when(memberships.findAllByIdUserId(any())).thenReturn(callerRows);
      when(memberships.findAllByIdUserIdAndKind(any(), any()))
          .thenReturn(
              callerRows.stream().filter(r -> r.getKind() == OrgUnitKind.SQUADRON).toList());

      OrgUnitCascadeService cascade = mock(OrgUnitCascadeService.class);
      when(cascade.expandWithDescendants(anyCollection()))
          .thenAnswer(
              invocation -> {
                Collection<OrgUnitMembership> given = invocation.getArgument(0);
                Set<UUID> ids = new LinkedHashSet<>();
                for (OrgUnitMembership row : given) {
                  ids.add(row.getId().getOrgUnitId());
                  if (row.getId().getOrgUnitId().equals(BEREICH_X)) {
                    ids.add(CHILD_C);
                  }
                }
                return ids;
              });

      MissionRepository repository = mock(MissionRepository.class);
      when(repository.findByIdForAuthorization(any())).thenReturn(Optional.empty());
      Map<String, Mission> parents = new LinkedHashMap<>();
      parents.put("", null);
      parents.put(" below an internal mission of B", mission(UNIT_B, true, null));
      parents.put(" below a public mission of B", mission(UNIT_B, false, null));
      Map<String, UUID> units = new LinkedHashMap<>();
      units.put("A", UNIT_A);
      units.put("B", UNIT_B);
      units.put("child C", CHILD_C);
      units.put("no unit", null);
      for (Map.Entry<String, UUID> unit : units.entrySet()) {
        for (boolean internal : List.of(false, true)) {
          for (Map.Entry<String, Mission> parent : parents.entrySet()) {
            Mission mission = mission(unit.getValue(), internal, parent.getValue());
            when(repository.findByIdForAuthorization(mission.getId()))
                .thenReturn(Optional.of(mission));
            missions.put(
                (internal ? "internal" : "public")
                    + " mission of "
                    + unit.getKey()
                    + parent.getKey(),
                mission.getId());
          }
        }
      }
      missions.put("unknown mission", UUID.randomUUID());

      MockHttpServletRequest request = new MockHttpServletRequest();
      if (caller.pin() != null) {
        request.addHeader(RequestScopeResolver.ACTIVE_ORG_UNIT_HEADER, caller.pin().toString());
      }
      OrgUnitRepository orgUnits = mock(OrgUnitRepository.class);
      RequestScopeResolver resolver =
          new RequestScopeResolver(
              authHelper,
              memberships,
              orgUnits,
              cascade,
              new StaffelMembershipResolver(mock(SquadronRepository.class), orgUnits),
              request);
      AccessGateService accessGateService =
          new AccessGateService(
              resolver,
              authHelper,
              mock(InventoryItemRepository.class),
              mock(ShipRepository.class),
              memberships);
      OwnerScopeService ownerScopeService =
          new OwnerScopeService(
              resolver,
              accessGateService,
              new OrgUnitStampingService(
                  resolver, accessGateService, authHelper, memberships, orgUnits));
      scopeHub = new ScopeHubMissionGates(accessGateService, authHelper, repository);
      policy = new MissionAccessPolicy(ownerScopeService, authHelper, repository);
    }

    private static Mission mission(UUID unit, boolean internal, Mission parent) {
      Mission mission = new Mission();
      mission.setId(UUID.randomUUID());
      mission.setIsInternal(internal);
      mission.setParent(parent);
      if (unit != null) {
        Squadron squadron = new Squadron();
        squadron.setId(unit);
        mission.setOwningOrgUnit(squadron);
      }
      return mission;
    }

    private static List<OrgUnitMembership> rows(List<UUID> units) {
      List<OrgUnitMembership> rows = new ArrayList<>();
      for (UUID unit : units) {
        OrgUnitMembership row = new OrgUnitMembership();
        row.setId(new OrgUnitMembershipId(UUID.randomUUID(), unit));
        row.setKind(unit.equals(BEREICH_X) ? OrgUnitKind.BEREICH : OrgUnitKind.SQUADRON);
        rows.add(row);
      }
      return rows;
    }
  }

  /**
   * The mission gates as the scope hub decided them, restated on the kernel's primitives.
   *
   * @param accessGateService the scope hub, for its org-unit gates
   * @param authHelper the caller's roles
   * @param missions the missions
   */
  private record ScopeHubMissionGates(
      AccessGateService accessGateService,
      AuthHelperService authHelper,
      MissionRepository missions) {

    boolean canSeeMission(UUID missionId) {
      return missions
          .findByIdForAuthorization(missionId)
          .map(
              m -> {
                for (Mission ancestor = m; ancestor != null; ancestor = ancestor.getParent()) {
                  if (!row(ancestor)) {
                    return false;
                  }
                }
                return true;
              })
          .orElse(false);
    }

    boolean canEditMission(UUID missionId) {
      return missions
          .findByIdForAuthorization(missionId)
          .map(
              m ->
                  m.getOwningOrgUnit() == null
                      || accessGateService.canEditSquadron(m.getOwningOrgUnit().getId()))
          .orElse(false);
    }

    private boolean row(Mission m) {
      if (m.getOwningOrgUnit() == null) {
        if (!Boolean.TRUE.equals(m.getIsInternal())) {
          return true;
        }
        return authHelper.isMemberOrAbove();
      }
      if (accessGateService.canSeeSquadron(m.getOwningOrgUnit().getId())) {
        return true;
      }
      return !Boolean.TRUE.equals(m.getIsInternal());
    }
  }
}
