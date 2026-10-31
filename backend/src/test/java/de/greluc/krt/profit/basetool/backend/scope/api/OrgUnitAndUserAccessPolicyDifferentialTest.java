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

package de.greluc.krt.profit.basetool.backend.scope.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.kernel.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitCascadeService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.StaffelMembershipResolver;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The differential verdict test of the org-unit and user access policies (plan section 5.4): over
 * one matrix of callers, org units and target members, {@link OrgUnitAccessPolicy} and {@link
 * UserAccessPolicy} return exactly the verdicts of the scope hub's former {@code canSeeOrgUnit},
 * {@code canEditOrgUnit} and {@code canActOnTargetUser}.
 *
 * <p>{@link ScopeHubGates} holds those three as {@link AccessGateService} decided them before they
 * moved, on the same scope kernel, so the comparison outlives their deletion.
 *
 * <p>The matrix covers an admin unpinned and pinned to either unit, a pinned and an unpinned
 * member, members of zero, one and two units, a Bereich lead reaching a child Staffel through the
 * cascade and a guest; as targets the caller themselves, members of one unit, of both, of the
 * child, of none and an unknown account.
 */
class OrgUnitAndUserAccessPolicyDifferentialTest {

  private static final UUID UNIT_A = UUID.randomUUID();

  private static final UUID UNIT_B = UUID.randomUUID();

  private static final UUID CHILD_C = UUID.randomUUID();

  private static final UUID BEREICH_X = UUID.randomUUID();

  private static final UUID UNIT_UNKNOWN = UUID.randomUUID();

  private static final List<UUID> UNITS = List.of(UNIT_A, UNIT_B, CHILD_C, UNIT_UNKNOWN);

  private static final UUID TARGET_IN_A = UUID.randomUUID();

  private static final UUID TARGET_IN_B = UUID.randomUUID();

  private static final UUID TARGET_IN_A_AND_B = UUID.randomUUID();

  private static final UUID TARGET_IN_CHILD = UUID.randomUUID();

  private static final UUID TARGET_IN_NONE = UUID.randomUUID();

  private static final UUID TARGET_UNKNOWN = UUID.randomUUID();

  private static final List<UUID> TARGETS =
      List.of(
          TARGET_IN_A,
          TARGET_IN_B,
          TARGET_IN_A_AND_B,
          TARGET_IN_CHILD,
          TARGET_IN_NONE,
          TARGET_UNKNOWN);

  /**
   * One caller of the matrix.
   *
   * @param name the scenario label
   * @param admin whether the caller holds ROLE_ADMIN
   * @param memberships the org units the caller is a direct member of
   * @param pin the active-context pin header, or {@code null}
   */
  private record Caller(String name, boolean admin, List<UUID> memberships, UUID pin) {}

  @Test
  void thePoliciesReturnTheScopeHubsVerdictForEveryCallerUnitAndTarget() {
    List<Caller> callers =
        List.of(
            new Caller("admin unpinned", true, List.of(), null),
            new Caller("admin pinned A", true, List.of(), UNIT_A),
            new Caller("admin pinned B", true, List.of(), UNIT_B),
            new Caller("member of none", false, List.of(), null),
            new Caller("member of A", false, List.of(UNIT_A), null),
            new Caller("member of A and B", false, List.of(UNIT_A, UNIT_B), null),
            new Caller("member of A and B pinned B", false, List.of(UNIT_A, UNIT_B), UNIT_B),
            new Caller("member of A pinned foreign B", false, List.of(UNIT_A), UNIT_B),
            new Caller("Bereich lead over C", false, List.of(BEREICH_X), null));

    int compared = 0;
    int granted = 0;
    for (Caller caller : callers) {
      Fixture fixture = new Fixture(caller);
      for (UUID unit : UNITS) {
        boolean seeOld = fixture.hub.canSeeOrgUnit(unit);
        boolean seeNow = fixture.orgUnitPolicy.canSeeOrgUnit(unit);
        assertThat(seeNow).as("canSeeOrgUnit %s for %s", unit, caller.name()).isEqualTo(seeOld);
        boolean editOld = fixture.hub.canEditOrgUnit(unit);
        boolean editNow = fixture.orgUnitPolicy.canEditOrgUnit(unit);
        assertThat(editNow).as("canEditOrgUnit %s for %s", unit, caller.name()).isEqualTo(editOld);
        compared += 2;
        granted += (seeNow ? 1 : 0) + (editNow ? 1 : 0);
      }
      List<UUID> targets = new ArrayList<>(TARGETS);
      targets.add(fixture.callerId);
      for (UUID target : targets) {
        for (boolean edit : List.of(false, true)) {
          boolean old = fixture.hub.canActOnTargetUser(target, edit);
          boolean now = fixture.userPolicy.canActOnTargetUser(target, edit);
          assertThat(now)
              .as("canActOnTargetUser %s edit=%s for %s", target, edit, caller.name())
              .isEqualTo(old);
          compared++;
          granted += now ? 1 : 0;
        }
      }
    }

    assertThat(compared).isEqualTo(callers.size() * (UNITS.size() * 2 + (TARGETS.size() + 1) * 2));
    assertThat(granted).isBetween(1, compared - 1);
  }

  /** One caller's freshly wired scope hub and policies over shared scenario mocks. */
  private static final class Fixture {

    private final UUID callerId = UUID.randomUUID();

    private final ScopeHubGates hub;

    private final OrgUnitAccessPolicy orgUnitPolicy;

    private final UserAccessPolicy userPolicy;

    Fixture(Caller caller) {
      AuthHelperService authHelper = mock(AuthHelperService.class);
      when(authHelper.isAuthenticated()).thenReturn(true);
      when(authHelper.isAdmin()).thenReturn(caller.admin());
      when(authHelper.isMemberOrAbove()).thenReturn(true);
      when(authHelper.currentUserId()).thenReturn(Optional.of(callerId));

      List<OrgUnitMembership> rows = new ArrayList<>();
      for (UUID unit : caller.memberships()) {
        rows.add(membership(callerId, unit));
      }
      OrgUnitMembershipRepository memberships = mock(OrgUnitMembershipRepository.class);
      when(memberships.findAllByIdUserId(any())).thenReturn(List.of());
      when(memberships.findAllByIdUserId(callerId)).thenReturn(rows);
      when(memberships.findAllByIdUserIdAndKind(any(), any()))
          .thenReturn(rows.stream().filter(r -> r.getKind() == OrgUnitKind.SQUADRON).toList());
      when(memberships.findAllByIdUserId(TARGET_IN_A))
          .thenReturn(List.of(membership(TARGET_IN_A, UNIT_A)));
      when(memberships.findAllByIdUserId(TARGET_IN_B))
          .thenReturn(List.of(membership(TARGET_IN_B, UNIT_B)));
      when(memberships.findAllByIdUserId(TARGET_IN_A_AND_B))
          .thenReturn(
              List.of(
                  membership(TARGET_IN_A_AND_B, UNIT_A), membership(TARGET_IN_A_AND_B, UNIT_B)));
      when(memberships.findAllByIdUserId(TARGET_IN_CHILD))
          .thenReturn(List.of(membership(TARGET_IN_CHILD, CHILD_C)));

      OrgUnitCascadeService cascade = mock(OrgUnitCascadeService.class);
      when(cascade.expandWithDescendants(anyCollection()))
          .thenAnswer(invocation -> {
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
      AccessGateService accessGateService = new AccessGateService(resolver, authHelper);
      orgUnitPolicy = new OrgUnitAccessPolicy(accessGateService);
      userPolicy = new UserAccessPolicy(accessGateService, authHelper, memberships);
      hub = new ScopeHubGates(accessGateService, authHelper, memberships);
    }

    private static OrgUnitMembership membership(UUID user, UUID unit) {
      OrgUnitMembership row = new OrgUnitMembership();
      row.setId(new OrgUnitMembershipId(user, unit));
      row.setKind(unit.equals(BEREICH_X) ? OrgUnitKind.BEREICH : OrgUnitKind.SQUADRON);
      return row;
    }
  }

  /**
   * The three gates as the scope hub decided them, kept for the comparison.
   *
   * @param accessGateService the scope hub, for its Staffel gates
   * @param authHelper the caller's identity and roles
   * @param orgUnitMembershipRepository the memberships
   */
  private record ScopeHubGates(
      AccessGateService accessGateService,
      AuthHelperService authHelper,
      OrgUnitMembershipRepository orgUnitMembershipRepository) {

    boolean canSeeOrgUnit(UUID orgUnitId) {
      return accessGateService.canSeeSquadron(orgUnitId);
    }

    boolean canEditOrgUnit(UUID orgUnitId) {
      return accessGateService.canEditSquadron(orgUnitId);
    }

    boolean canActOnTargetUser(UUID targetUserId, boolean edit) {
      return canActOnTargetUserScoped(
          targetUserId,
          edit ? accessGateService::canEditSquadron : accessGateService::canSeeSquadron);
    }

    private boolean canActOnTargetUserScoped(UUID targetUserId, Predicate<UUID> unitScope) {
      if (authHelper.isAdmin()) {
        return true;
      }
      if (authHelper.currentUserId().map(targetUserId::equals).orElse(false)) {
        return true;
      }
      return orgUnitMembershipRepository.findAllByIdUserId(targetUserId).stream()
          .map(m -> m.getId().getOrgUnitId())
          .anyMatch(unitScope);
    }
  }
}
