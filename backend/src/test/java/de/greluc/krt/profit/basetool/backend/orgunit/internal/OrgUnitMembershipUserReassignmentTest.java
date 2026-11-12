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

package de.greluc.krt.profit.basetool.backend.orgunit.internal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.kernel.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.orgunit.api.MembershipRole;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.OwnedRowsMover;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Checks that a merge preserves membership authority and obeys seat constraints. */
class OrgUnitMembershipUserReassignmentTest {

  private final UUID source = UUID.randomUUID();
  private final UUID target = UUID.randomUUID();
  private final OrgUnitMembershipRepository memberships = mock(OrgUnitMembershipRepository.class);
  private final OrgUnitMembershipUserReassignment reassignment =
      new OrgUnitMembershipUserReassignment(mock(OwnedRowsMover.class), memberships);

  @Test
  void rejectsDroppingACapabilityButAllowsEquivalentMemberships() {
    UUID unit = UUID.randomUUID();
    OrgUnitMembership sourceRow = row(source, unit, OrgUnitKind.SQUADRON, MembershipRole.MEMBER);
    OrgUnitMembership targetRow = row(target, unit, OrgUnitKind.SQUADRON, MembershipRole.MEMBER);
    sourceRow.setLogistician(true);
    when(memberships.findAllByIdUserId(source)).thenReturn(List.of(sourceRow));
    when(memberships.findAllByIdUserId(target)).thenReturn(List.of(targetRow));

    assertThrows(
        BusinessConflictException.class, () -> reassignment.assertMergeable(source, target));
    targetRow.setLogistician(true);
    assertDoesNotThrow(() -> reassignment.assertMergeable(source, target));
  }

  @Test
  void refusesAThirdStaffelOrLeadershipAlongsideStaffeln() {
    when(memberships.findAllByIdUserId(target))
        .thenReturn(
            List.of(
                row(target, UUID.randomUUID(), OrgUnitKind.SQUADRON, MembershipRole.MEMBER),
                row(target, UUID.randomUUID(), OrgUnitKind.SQUADRON, MembershipRole.MEMBER)));
    when(memberships.findAllByIdUserId(source))
        .thenReturn(
            List.of(row(source, UUID.randomUUID(), OrgUnitKind.SQUADRON, MembershipRole.MEMBER)));

    assertThrows(
        BusinessConflictException.class, () -> reassignment.assertMergeable(source, target));

    when(memberships.findAllByIdUserId(source))
        .thenReturn(
            List.of(
                row(
                    source,
                    UUID.randomUUID(),
                    OrgUnitKind.BEREICH,
                    MembershipRole.BEREICHSLEITER)));

    assertThrows(
        BusinessConflictException.class, () -> reassignment.assertMergeable(source, target));
  }

  private static OrgUnitMembership row(
      UUID user, UUID unit, OrgUnitKind kind, MembershipRole role) {
    OrgUnitMembership row = new OrgUnitMembership();
    row.setId(new OrgUnitMembershipId(user, unit));
    row.setKind(kind);
    row.setRole(role);
    return row;
  }
}
