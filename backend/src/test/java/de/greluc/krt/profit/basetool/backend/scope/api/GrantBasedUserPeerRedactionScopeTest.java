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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link GrantBasedUserPeerRedactionScope}: the peer redaction lifts only for users
 * who share a unit where the caller holds the Logistician grant.
 */
@ExtendWith(MockitoExtension.class)
class GrantBasedUserPeerRedactionScopeTest {

  private static final UUID UNIT_A = UUID.randomUUID();
  private static final UUID UNIT_B = UUID.randomUUID();
  private static final UUID IN_A = UUID.randomUUID();
  private static final UUID IN_B = UUID.randomUUID();
  private static final UUID IN_BOTH = UUID.randomUUID();
  private static final UUID IN_NONE = UUID.randomUUID();

  @Mock private OrgUnitMembershipRepository membershipRepository;
  @Mock private OwnerScopeService ownerScopeService;
  @Mock private AuthHelperService authHelperService;
  @InjectMocks private GrantBasedUserPeerRedactionScope policy;

  private static OrgUnitMembership membership(UUID userId, UUID unitId) {
    OrgUnitMembership m = new OrgUnitMembership();
    m.setId(new OrgUnitMembershipId(userId, unitId));
    return m;
  }

  private void memberships() {
    lenient()
        .when(membershipRepository.findAllByIdUserIdIn(any()))
        .thenReturn(
            List.of(
                membership(IN_A, UNIT_A),
                membership(IN_B, UNIT_B),
                membership(IN_BOTH, UNIT_A),
                membership(IN_BOTH, UNIT_B)));
  }

  @Test
  void logisticianInAOnly_liftsOnlyForUsersSharingA() {
    memberships();
    when(ownerScopeService.hasRoleInOrgUnit(UNIT_A, Roles.LOGISTICIAN)).thenReturn(true);
    when(ownerScopeService.hasRoleInOrgUnit(UNIT_B, Roles.LOGISTICIAN)).thenReturn(false);

    Set<UUID> lifted = policy.usersWithUnredactedRead(List.of(IN_A, IN_B, IN_BOTH, IN_NONE));

    assertEquals(Set.of(IN_A, IN_BOTH), lifted);
  }

  @Test
  void callerWithoutAnyGrant_liftsNothing() {
    memberships();
    when(ownerScopeService.hasRoleInOrgUnit(any(), any())).thenReturn(false);

    assertTrue(policy.usersWithUnredactedRead(List.of(IN_A, IN_B)).isEmpty());
  }

  @Test
  void admin_liftsForEveryoneWithoutQueryingMemberships() {
    when(authHelperService.isAdmin()).thenReturn(true);

    Set<UUID> lifted = policy.usersWithUnredactedRead(List.of(IN_A, IN_NONE));

    assertEquals(Set.of(IN_A, IN_NONE), lifted);
    verify(membershipRepository, never()).findAllByIdUserIdIn(any());
  }

  @Test
  void emptyInput_yieldsEmptySet() {
    assertTrue(policy.usersWithUnredactedRead(List.of()).isEmpty());
  }
}
