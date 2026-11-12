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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionFinanceEntry;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionFinanceEntryRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitCascadeService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.scope.api.AccessGateService;
import de.greluc.krt.profit.basetool.backend.scope.api.RequestScopeResolver;
import de.greluc.krt.profit.basetool.backend.support.UnboundRequestProxy;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * An admin's mission powers follow the active context like every other role power (REQ-ORG-032):
 * every mission without a pin, only the pinned unit's missions with one, and no ownerless mission
 * under a pin; a mission's owner keeps their personal right under any pin. Outside an HTTP request
 * the same gates answer as without a pin (ADR-0236 §6).
 */
class MissionAccessPolicyPinnedAdminTest {

  private static final UUID UNIT_A = UUID.randomUUID();
  private static final UUID UNIT_B = UUID.randomUUID();

  private final Authentication admin = new TestingAuthenticationToken("admin", "n/a", "ROLE_ADMIN");
  private final Authentication member =
      new TestingAuthenticationToken("member", "n/a", "ROLE_KRT_MEMBER");
  private final MissionRepository missions = mock(MissionRepository.class);
  private final MissionFinanceEntryRepository financeEntries =
      mock(MissionFinanceEntryRepository.class);
  private final UserService userService = mock(UserService.class);
  private final Map<UUID, Mission> missionsById = new HashMap<>();
  private final UUID missionOfA = mission(UNIT_A, null);
  private final UUID missionOfB = mission(UNIT_B, null);
  private final UUID ownerlessMission = mission(null, null);

  private UUID mission(@Nullable UUID unit, @Nullable User owner) {
    Mission mission = new Mission();
    mission.setId(UUID.randomUUID());
    mission.setOwner(owner);
    if (unit != null) {
      Squadron squadron = new Squadron();
      squadron.setId(unit);
      mission.setOwningOrgUnit(squadron);
    }
    when(missions.findByIdForAuthorization(mission.getId())).thenReturn(Optional.of(mission));
    missionsById.put(mission.getId(), mission);
    return mission.getId();
  }

  private UUID financeEntryOf(@NotNull UUID missionId) {
    MissionFinanceEntry entry = new MissionFinanceEntry();
    entry.setId(UUID.randomUUID());
    entry.setMission(missionsById.get(missionId));
    when(financeEntries.findById(entry.getId())).thenReturn(Optional.of(entry));
    return entry.getId();
  }

  private MissionAccessPolicy policyFor(@Nullable UUID pin) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    if (pin != null) {
      request.addHeader(RequestScopeResolver.ACTIVE_ORG_UNIT_HEADER, pin.toString());
    }
    return policy(true, request);
  }

  private MissionAccessPolicy policy(boolean callerIsAdmin, @NotNull HttpServletRequest request) {
    AuthHelperService authHelper = mock(AuthHelperService.class);
    when(authHelper.isAuthenticated()).thenReturn(true);
    when(authHelper.isAdmin()).thenReturn(callerIsAdmin);
    OrgUnitRepository orgUnits = mock(OrgUnitRepository.class);
    when(orgUnits.existsByIdAndActiveTrue(any())).thenReturn(true);
    RequestScopeResolver resolver =
        new RequestScopeResolver(
            authHelper,
            mock(OrgUnitMembershipRepository.class),
            orgUnits,
            mock(OrgUnitCascadeService.class),
            request);
    return new MissionAccessPolicy(
        new AccessGateService(resolver, authHelper),
        authHelper,
        missions,
        userService,
        mock(MissionParticipantRepository.class),
        financeEntries);
  }

  @Test
  void anUnpinnedAdminManagesEveryMission() {
    MissionAccessPolicy policy = policyFor(null);

    assertThat(policy.canManageMission(missionOfA, admin)).isTrue();
    assertThat(policy.canManageMission(missionOfB, admin)).isTrue();
    assertThat(policy.canManageMission(ownerlessMission, admin)).isTrue();
    assertThat(policy.canChangeOwner(missionOfB, admin)).isTrue();
    assertThat(policy.canEditFinanceEntry(financeEntryOf(missionOfB), admin)).isTrue();
  }

  @Test
  void aPinnedAdminManagesOnlyThePinnedUnitsMissions() {
    MissionAccessPolicy policy = policyFor(UNIT_A);

    assertThat(policy.canManageMission(missionOfA, admin)).isTrue();
    assertThat(policy.canManageMission(missionOfB, admin)).isFalse();
    assertThat(policy.canManageMission(ownerlessMission, admin)).isFalse();
    assertThat(policy.canChangeOwner(missionOfA, admin)).isTrue();
    assertThat(policy.canChangeOwner(missionOfB, admin)).isFalse();
  }

  @Test
  void aPinnedAdminEditsOnlyTheFinanceEntriesOfThePinnedUnitsMissions() {
    MissionAccessPolicy policy = policyFor(UNIT_A);

    assertThat(policy.canEditFinanceEntry(financeEntryOf(missionOfA), admin)).isTrue();
    assertThat(policy.canEditFinanceEntry(financeEntryOf(missionOfB), admin)).isFalse();
    assertThat(policy.canEditFinanceEntry(financeEntryOf(ownerlessMission), admin)).isFalse();
  }

  @Test
  void aPinnedAdminKeepsManagingTheirOwnMissionOfAnotherUnit() {
    User self = new User();
    self.setId(UUID.randomUUID());
    UUID ownMissionOfB = mission(UNIT_B, self);
    when(userService.getCurrentUser()).thenReturn(Optional.of(self));
    MissionAccessPolicy policy = policyFor(UNIT_A);

    assertThat(policy.canManageMission(ownMissionOfB, admin)).isTrue();
    assertThat(policy.canChangeOwner(ownMissionOfB, admin)).isTrue();
  }

  @Test
  void outsideAnHttpRequestAnAdminManagesEveryMissionAsWithoutAPin() {
    MissionAccessPolicy policy = policy(true, UnboundRequestProxy.create());

    assertThat(policy.canManageMission(missionOfB, admin)).isTrue();
    assertThat(policy.canManageMission(ownerlessMission, admin)).isTrue();
    assertThat(policy.canManageLoadedMission(missionsById.get(missionOfA), admin)).isTrue();
  }

  @Test
  void outsideAnHttpRequestAnOwnerManagesTheirOwnMissionAndNoOtherOne() {
    User self = new User();
    self.setId(UUID.randomUUID());
    UUID ownMission = mission(UNIT_B, self);
    when(userService.getCurrentUser()).thenReturn(Optional.of(self));
    MissionAccessPolicy policy = policy(false, UnboundRequestProxy.create());

    assertThat(policy.canManageMission(ownMission, member)).isTrue();
    assertThat(policy.canManageMission(missionOfA, member)).isFalse();
  }
}
