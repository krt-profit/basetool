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

package de.greluc.krt.profit.basetool.backend.mission.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionFinanceEntryRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.scope.api.OwnerScopeService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;

/**
 * Unit tests for {@link MissionAccessPolicy#liftsPeerRedaction}: a Logistician in unit A reads the
 * participants of A's missions unredacted and those of B's missions redacted.
 */
@ExtendWith(MockitoExtension.class)
class MissionPeerRedactionLiftTest {

  @Mock private OwnerScopeService ownerScopeService;
  @Mock private AuthHelperService authHelperService;
  @Mock private MissionRepository missionRepository;
  @Mock private UserService userService;
  @Mock private RoleHierarchy roleHierarchy;
  @Mock private MissionParticipantRepository missionParticipantRepository;
  @Mock private MissionFinanceEntryRepository missionFinanceEntryRepository;

  private MissionAccessPolicy policy;

  @BeforeEach
  void setUp() {
    policy =
        new MissionAccessPolicy(
            ownerScopeService,
            authHelperService,
            missionRepository,
            userService,
            roleHierarchy,
            missionParticipantRepository,
            missionFinanceEntryRepository);
  }

  private Mission missionOwnedBy(UUID unitId) {
    Squadron unit = new Squadron();
    unit.setId(unitId);
    Mission mission = new Mission();
    mission.setId(UUID.randomUUID());
    mission.setOwningOrgUnit(unit);
    return mission;
  }

  @Test
  void logisticianInA_liftsForAMissionAndNotForBMission() {
    UUID unitA = UUID.randomUUID();
    UUID unitB = UUID.randomUUID();
    when(ownerScopeService.hasRoleInOrgUnit(unitA, Roles.LOGISTICIAN)).thenReturn(true);
    when(ownerScopeService.hasRoleInOrgUnit(unitB, Roles.LOGISTICIAN)).thenReturn(false);

    assertTrue(policy.liftsPeerRedaction(missionOwnedBy(unitA)));
    assertFalse(policy.liftsPeerRedaction(missionOwnedBy(unitB)));
  }

  @Test
  void ownerlessMission_liftsForAdminOnly() {
    Mission ownerless = new Mission();
    when(authHelperService.isAdmin()).thenReturn(false, true);

    assertFalse(policy.liftsPeerRedaction(ownerless));
    assertTrue(policy.liftsPeerRedaction(ownerless));
  }

  @Test
  void unknownMissionId_doesNotLift() {
    UUID id = UUID.randomUUID();
    when(missionRepository.findByIdForAuthorization(id)).thenReturn(Optional.empty());

    assertFalse(policy.liftsPeerRedaction(id));
  }

  @Test
  void missionId_resolvesTheOwningUnitBeforeAskingTheGrant() {
    UUID unitA = UUID.randomUUID();
    Mission mission = missionOwnedBy(unitA);
    when(missionRepository.findByIdForAuthorization(mission.getId()))
        .thenReturn(Optional.of(mission));
    when(ownerScopeService.hasRoleInOrgUnit(unitA, Roles.LOGISTICIAN)).thenReturn(true);

    assertTrue(policy.liftsPeerRedaction(mission.getId()));
  }
}
