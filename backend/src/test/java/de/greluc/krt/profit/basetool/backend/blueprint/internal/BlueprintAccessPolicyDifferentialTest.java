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

package de.greluc.krt.profit.basetool.backend.blueprint.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.model.MembershipRole;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.orgunit.api.StaffelMembershipResolver;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.service.AccessGateService;
import de.greluc.krt.profit.basetool.backend.service.OrgUnitCascadeService;
import de.greluc.krt.profit.basetool.backend.service.OrgUnitStampingService;
import de.greluc.krt.profit.basetool.backend.service.OwnerScopeService;
import de.greluc.krt.profit.basetool.backend.service.RequestScopeResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The differential verdict test of the blueprint access policy (plan §5.4, ADR-0236): for every
 * caller of the matrix, {@link BlueprintAccessPolicy#canAccessBlueprintOverview()} returns the
 * scope hub's verdict and the verdict of a restatement of the rule on its inputs (admin, officer,
 * or an oversight seat).
 *
 * <p>The hub keeps the predicate for the member's own capability view, so both stay live and the
 * test keeps comparing them. The matrix covers an admin, an officer without a seat, a member of no
 * unit, and a member holding each membership role in turn.
 */
class BlueprintAccessPolicyDifferentialTest {

  /**
   * One caller of the matrix.
   *
   * @param name the scenario label
   * @param admin whether the caller holds ROLE_ADMIN
   * @param officer whether the caller reaches ROLE_OFFICER
   * @param seat the role of the caller's one membership, or {@code null} for none
   */
  private record Caller(String name, boolean admin, boolean officer, MembershipRole seat) {}

  @Test
  void thePolicyReturnsTheScopeHubsVerdictForEveryCaller() {
    List<Caller> callers = new ArrayList<>();
    callers.add(new Caller("admin", true, false, null));
    callers.add(new Caller("officer without a seat", false, true, null));
    callers.add(new Caller("member of none", false, false, null));
    for (MembershipRole role : MembershipRole.values()) {
      callers.add(new Caller("member as " + role, false, false, role));
    }

    int granted = 0;
    for (Caller caller : callers) {
      UUID callerId = UUID.randomUUID();
      AuthHelperService authHelper = mock(AuthHelperService.class);
      when(authHelper.isAuthenticated()).thenReturn(true);
      when(authHelper.isAdmin()).thenReturn(caller.admin());
      when(authHelper.hasReachableRole(any())).thenReturn(false);
      when(authHelper.hasReachableRole(Roles.authority(Roles.OFFICER)))
          .thenReturn(caller.officer());
      when(authHelper.currentUserId()).thenReturn(Optional.of(callerId));

      OrgUnitMembershipRepository memberships = mock(OrgUnitMembershipRepository.class);
      List<OrgUnitMembership> rows = new ArrayList<>();
      if (caller.seat() != null) {
        OrgUnitMembership row = new OrgUnitMembership();
        row.setId(new OrgUnitMembershipId(callerId, UUID.randomUUID()));
        row.setRole(caller.seat());
        rows.add(row);
      }
      when(memberships.findAllByIdUserId(callerId)).thenReturn(rows);

      OrgUnitRepository orgUnits = mock(OrgUnitRepository.class);
      RequestScopeResolver resolver =
          new RequestScopeResolver(
              authHelper,
              memberships,
              orgUnits,
              mock(OrgUnitCascadeService.class),
              new StaffelMembershipResolver(mock(SquadronRepository.class), orgUnits),
              new MockHttpServletRequest());
      AccessGateService accessGateService =
          new AccessGateService(resolver, authHelper, memberships);
      OwnerScopeService hub =
          new OwnerScopeService(
              resolver,
              accessGateService,
              new OrgUnitStampingService(
                  resolver, accessGateService, authHelper, memberships, orgUnits));
      BlueprintAccessPolicy policy = new BlueprintAccessPolicy(hub);

      boolean restated =
          caller.admin()
              || caller.officer()
              || (caller.seat() != null && caller.seat().confersOwnLevelOversight());
      boolean now = policy.canAccessBlueprintOverview();
      assertThat(hub.canAccessBlueprintOverview()).as("hub for %s", caller.name()).isEqualTo(now);
      assertThat(now).as("policy for %s", caller.name()).isEqualTo(restated);
      granted += now ? 1 : 0;
    }

    assertThat(granted).isBetween(1, callers.size() - 1);
  }
}
