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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionFinanceEntry;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.scope.api.OwnerScopeService;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@ExtendWith(MockitoExtension.class)
class MissionAccessPolicyGateTest {

  @Mock private MissionRepository missionRepository;

  @Mock private UserService userService;

  @Mock private MissionParticipantRepository missionParticipantRepository;

  @Mock
  private de.greluc.krt.profit.basetool.backend.mission.api.MissionFinanceEntryRepository
      missionFinanceEntryRepository;

  @Mock private OwnerScopeService ownerScopeService;

  @Mock private AuthHelperService authHelperService;

  private RoleHierarchy roleHierarchy;

  private MissionAccessPolicy missionAccessPolicy;

  private UUID missionId;
  private UUID userId;
  private User user;
  private Mission mission;
  private Authentication authentication;

  @BeforeEach
  void setUp() {
    roleHierarchy = de.greluc.krt.profit.basetool.backend.app.api.SecurityConfig.roleHierarchy();
    missionAccessPolicy =
        spy(
            new MissionAccessPolicy(
                ownerScopeService,
                authHelperService,
                missionRepository,
                userService,
                roleHierarchy,
                missionParticipantRepository,
                missionFinanceEntryRepository));

    missionId = UUID.randomUUID();
    userId = UUID.randomUUID();
    user = new User();
    user.setId(userId);
    mission = new Mission();
    mission.setId(missionId);
    authentication = mock(Authentication.class);
  }

  @Test
  void canManageMission_ManagerList_GlobalMissionManager_ShouldReturnTrue() {
    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getAuthorities())
        .thenAnswer(
            i -> Collections.singletonList(new SimpleGrantedAuthority("ROLE_MISSION_MANAGER")));
    doReturn(true).when(missionAccessPolicy).canEditMission(missionId);

    assertTrue(missionAccessPolicy.canManageMission(missionId, authentication));
  }

  @Test
  void canManageMission_ManagerList_MissionManagePermission_ShouldReturnTrue() {
    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getAuthorities())
        .thenAnswer(i -> Collections.singletonList(new SimpleGrantedAuthority("MISSION_MANAGE")));
    doReturn(true).when(missionAccessPolicy).canEditMission(missionId);

    assertTrue(missionAccessPolicy.canManageMission(missionId, authentication));
  }

  @Test
  void canManageMission_ManagerList_Owner_ShouldReturnTrue() {
    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getAuthorities()).thenReturn(Collections.emptyList());
    when(missionRepository.findByIdForAuthorization(missionId)).thenReturn(Optional.of(mission));
    when(userService.getCurrentUser()).thenReturn(Optional.of(user));
    mission.setOwner(user);

    assertTrue(missionAccessPolicy.canManageMission(missionId, authentication));
  }

  @Test
  void canManageMission_ManagerList_CoManager_ShouldReturnTrue() {
    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getAuthorities()).thenReturn(Collections.emptyList());
    when(missionRepository.findByIdForAuthorization(missionId)).thenReturn(Optional.of(mission));
    when(userService.getCurrentUser()).thenReturn(Optional.of(user));
    mission.getManagers().add(user);

    assertTrue(missionAccessPolicy.canManageMission(missionId, authentication));
  }

  @Test
  void canManageMission_ManagerList_RegularUser_ShouldReturnFalse() {
    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getAuthorities()).thenReturn(Collections.emptyList());
    when(missionRepository.findByIdForAuthorization(missionId)).thenReturn(Optional.of(mission));
    when(userService.getCurrentUser()).thenReturn(Optional.of(user));

    assertFalse(missionAccessPolicy.canManageMission(missionId, authentication));
  }

  @Test
  void canManageMission_ManagerList_MissionManagerForeignOrgUnit_ShouldReturnFalse() {
    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getAuthorities())
        .thenAnswer(
            i -> Collections.singletonList(new SimpleGrantedAuthority("ROLE_MISSION_MANAGER")));
    doReturn(false).when(missionAccessPolicy).canEditMission(missionId);
    when(missionRepository.findByIdForAuthorization(missionId)).thenReturn(Optional.of(mission));
    when(userService.getCurrentUser()).thenReturn(Optional.of(user));

    assertFalse(missionAccessPolicy.canManageMission(missionId, authentication));
  }

  @Test
  void canEdit_ManageGate_AdmitsTheOwner() {
    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getAuthorities()).thenReturn(Collections.emptyList());
    when(userService.getCurrentUser()).thenReturn(Optional.of(user));
    mission.setOwner(user);

    assertTrue(missionAccessPolicy.canEdit(mission, MissionGate.MANAGE, authentication));
  }

  @Test
  void canEdit_ManageGate_RefusesACallWithoutAuthentication() {
    assertFalse(missionAccessPolicy.canEdit(mission, MissionGate.MANAGE, null));
  }

  @Test
  void canEdit_SeeGate_ReadsThePublicMissionChain() {
    mission.setIsInternal(false);

    assertTrue(missionAccessPolicy.canEdit(mission, MissionGate.SEE, null));
  }

  @Test
  void canAccessLoadedParticipant_Owner_ShouldReturnTrue() {
    UUID participantId = UUID.randomUUID();
    MissionParticipant participant = new MissionParticipant();
    participant.setId(participantId);
    participant.setMission(mission);
    participant.setUser(user);

    when(missionParticipantRepository.findById(participantId)).thenReturn(Optional.of(participant));
    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getPrincipal()).thenReturn("some-jwt-principal");
    when(userService.getCurrentUser()).thenReturn(Optional.of(user));

    assertTrue(
        missionAccessPolicy.canAccessLoadedParticipant(mission, participantId, authentication));
  }

  @Test
  void canAccessLoadedParticipant_ForeignUserWithoutPrivilege_ShouldReturnFalse() {
    UUID participantId = UUID.randomUUID();
    User otherUser = new User();
    otherUser.setId(UUID.randomUUID());
    MissionParticipant participant = new MissionParticipant();
    participant.setId(participantId);
    participant.setMission(mission);
    participant.setUser(otherUser);

    when(missionParticipantRepository.findById(participantId)).thenReturn(Optional.of(participant));
    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getPrincipal()).thenReturn("some-jwt-principal");
    when(authentication.getAuthorities())
        .thenAnswer(i -> Collections.singletonList(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")));
    when(userService.getCurrentUser()).thenReturn(Optional.of(user));

    assertFalse(
        missionAccessPolicy.canAccessLoadedParticipant(mission, participantId, authentication));
  }

  @Test
  void canAccessLoadedParticipant_MissionManagerOwnOrgUnit_ShouldReturnTrue() {
    UUID participantId = UUID.randomUUID();
    User otherUser = new User();
    otherUser.setId(UUID.randomUUID());
    MissionParticipant participant = new MissionParticipant();
    participant.setId(participantId);
    participant.setMission(mission);
    participant.setUser(otherUser);

    when(missionParticipantRepository.findById(participantId)).thenReturn(Optional.of(participant));
    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getPrincipal()).thenReturn("some-jwt-principal");
    when(authentication.getAuthorities())
        .thenAnswer(
            i -> Collections.singletonList(new SimpleGrantedAuthority("ROLE_MISSION_MANAGER")));
    doReturn(true).when(missionAccessPolicy).canEditMission(missionId);

    assertTrue(
        missionAccessPolicy.canAccessLoadedParticipant(mission, participantId, authentication));
  }

  @Test
  void canAccessLoadedParticipant_MissionManagerForeignOrgUnit_ShouldReturnFalse() {
    UUID participantId = UUID.randomUUID();
    User otherUser = new User();
    otherUser.setId(UUID.randomUUID());
    MissionParticipant participant = new MissionParticipant();
    participant.setId(participantId);
    participant.setMission(mission);
    participant.setUser(otherUser);

    when(missionParticipantRepository.findById(participantId)).thenReturn(Optional.of(participant));
    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getPrincipal()).thenReturn("some-jwt-principal");
    when(authentication.getAuthorities())
        .thenAnswer(
            i -> Collections.singletonList(new SimpleGrantedAuthority("ROLE_MISSION_MANAGER")));
    doReturn(false).when(missionAccessPolicy).canEditMission(missionId);
    when(userService.getCurrentUser()).thenReturn(Optional.of(user));

    assertFalse(
        missionAccessPolicy.canAccessLoadedParticipant(mission, participantId, authentication));
  }

  @Test
  void canAccessLoadedParticipant_AnonymousCaller_ShouldReturnFalse() {
    UUID participantId = UUID.randomUUID();
    MissionParticipant participant = new MissionParticipant();
    participant.setId(participantId);
    participant.setMission(mission);
    participant.setUser(user);

    when(missionParticipantRepository.findById(participantId)).thenReturn(Optional.of(participant));
    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getPrincipal()).thenReturn("anonymousUser");

    assertFalse(
        missionAccessPolicy.canAccessLoadedParticipant(mission, participantId, authentication));
  }

  /** An external row is not editable by a member who cannot manage the mission. */
  @Test
  void canAccessLoadedParticipant_ExternalRowPlainMember_ShouldReturnFalse() {
    UUID participantId = UUID.randomUUID();
    MissionParticipant participant = new MissionParticipant();
    participant.setId(participantId);
    participant.setMission(mission);
    participant.setUser(null);
    participant.setGuestName("Somebody");

    when(missionParticipantRepository.findById(participantId)).thenReturn(Optional.of(participant));
    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getAuthorities())
        .thenAnswer(i -> Collections.singletonList(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")));

    assertFalse(
        missionAccessPolicy.canAccessLoadedParticipant(mission, participantId, authentication));
  }

  /**
   * An external participant row is not editable by an anonymous caller, independent of the security
   * matrix.
   */
  @Test
  void canAccessLoadedParticipant_ExternalRowAnonymous_ShouldReturnFalse() {
    UUID participantId = UUID.randomUUID();
    MissionParticipant participant = new MissionParticipant();
    participant.setId(participantId);
    participant.setMission(mission);
    participant.setUser(null);
    participant.setGuestName("Somebody");

    when(missionParticipantRepository.findById(participantId)).thenReturn(Optional.of(participant));
    when(authentication.isAuthenticated()).thenReturn(true);

    assertFalse(
        missionAccessPolicy.canAccessLoadedParticipant(mission, participantId, authentication));
  }

  /** The leadership may edit an external row — the rule D4 kept. */
  @Test
  void canAccessLoadedParticipant_ExternalRowManager_ShouldReturnTrue() {
    UUID participantId = UUID.randomUUID();
    MissionParticipant participant = new MissionParticipant();
    participant.setId(participantId);
    participant.setMission(mission);
    participant.setUser(null);
    participant.setGuestName("Somebody");

    when(missionParticipantRepository.findById(participantId)).thenReturn(Optional.of(participant));
    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getAuthorities())
        .thenAnswer(i -> Collections.singletonList(new SimpleGrantedAuthority("ROLE_ADMIN")));

    assertTrue(
        missionAccessPolicy.canAccessLoadedParticipant(mission, participantId, authentication));
  }

  @Test
  void canAccessLoadedParticipant_MissingParticipant_ShouldThrow404() {
    UUID participantId = UUID.randomUUID();
    when(missionParticipantRepository.findById(participantId)).thenReturn(Optional.empty());

    assertThrows(
        NotFoundException.class,
        () ->
            missionAccessPolicy.canAccessLoadedParticipant(mission, participantId, authentication));
  }

  @Test
  void canAccessLoadedParticipant_MissionMismatch_ShouldReturnFalse() {
    UUID participantId = UUID.randomUUID();
    Mission otherMission = new Mission();
    otherMission.setId(UUID.randomUUID());
    MissionParticipant participant = new MissionParticipant();
    participant.setId(participantId);
    participant.setMission(otherMission);
    participant.setUser(user);

    when(missionParticipantRepository.findById(participantId)).thenReturn(Optional.of(participant));

    assertFalse(
        missionAccessPolicy.canAccessLoadedParticipant(mission, participantId, authentication));
  }

  @Test
  void canChangeOwner_NullAuthentication_ShouldReturnFalse() {
    assertFalse(missionAccessPolicy.canChangeOwner(missionId, null));
  }

  @Test
  void canChangeOwner_NotAuthenticated_ShouldReturnFalse() {
    when(authentication.isAuthenticated()).thenReturn(false);

    assertFalse(missionAccessPolicy.canChangeOwner(missionId, authentication));
  }

  @Test
  void canChangeOwner_AnonymousPrincipal_ShouldReturnFalse() {
    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getPrincipal()).thenReturn("anonymousUser");

    assertFalse(missionAccessPolicy.canChangeOwner(missionId, authentication));
  }

  @Test
  void canChangeOwner_AdminRole_ShouldReturnTrue() {
    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getPrincipal()).thenReturn("real-jwt-sub");
    when(authentication.getAuthorities())
        .thenAnswer(i -> Collections.singletonList(new SimpleGrantedAuthority("ROLE_ADMIN")));

    assertTrue(missionAccessPolicy.canChangeOwner(missionId, authentication));
  }

  @Test
  void canChangeOwner_SeatInOwningUnit_ShouldReturnTrue() {
    UUID unitId = givenOwnedMission();
    givenAuthenticatedCaller("ROLE_KRT_MEMBER");
    when(ownerScopeService.canActAsOfficerInOwner(unitId)).thenReturn(true);

    assertTrue(missionAccessPolicy.canChangeOwner(missionId, authentication));
  }

  @Test
  void canChangeOwner_SeatInOtherUnit_ShouldReturnFalse() {
    UUID unitId = givenOwnedMission();
    givenAuthenticatedCaller("ROLE_KRT_MEMBER");
    when(ownerScopeService.canActAsOfficerInOwner(unitId)).thenReturn(false);
    when(userService.getCurrentUser()).thenReturn(Optional.of(user));

    assertFalse(missionAccessPolicy.canChangeOwner(missionId, authentication));
  }

  @Test
  void canChangeOwner_FlatOfficerRoleWithoutReach_ShouldReturnFalse() {
    UUID unitId = givenOwnedMission();
    givenAuthenticatedCaller("ROLE_OFFICER");
    when(ownerScopeService.canActAsOfficerInOwner(unitId)).thenReturn(false);
    when(userService.getCurrentUser()).thenReturn(Optional.of(user));

    assertFalse(missionAccessPolicy.canChangeOwner(missionId, authentication));
  }

  @Test
  void canChangeOwner_OwnerlessMissionWithoutReach_ShouldReturnFalse() {
    when(missionRepository.findByIdForAuthorization(missionId)).thenReturn(Optional.of(mission));
    givenAuthenticatedCaller("ROLE_KRT_MEMBER");
    when(ownerScopeService.canActAsOfficerInOwner(null)).thenReturn(false);
    when(userService.getCurrentUser()).thenReturn(Optional.of(user));

    assertFalse(missionAccessPolicy.canChangeOwner(missionId, authentication));
  }

  @Test
  void canChangeOwner_MissionManagerRole_ShouldReturnFalse() {
    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getPrincipal()).thenReturn("real-jwt-sub");
    when(authentication.getAuthorities())
        .thenAnswer(
            i -> Collections.singletonList(new SimpleGrantedAuthority("ROLE_MISSION_MANAGER")));
    when(missionRepository.findByIdForAuthorization(missionId)).thenReturn(Optional.of(mission));
    when(userService.getCurrentUser()).thenReturn(Optional.of(user));

    assertFalse(missionAccessPolicy.canChangeOwner(missionId, authentication));
  }

  @Test
  void canChangeOwner_CoManagerWithoutOfficerRole_ShouldReturnFalse() {
    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getPrincipal()).thenReturn("real-jwt-sub");
    when(authentication.getAuthorities())
        .thenAnswer(i -> Collections.singletonList(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")));
    when(missionRepository.findByIdForAuthorization(missionId)).thenReturn(Optional.of(mission));
    when(userService.getCurrentUser()).thenReturn(Optional.of(user));
    mission.getManagers().add(user);

    assertFalse(
        missionAccessPolicy.canChangeOwner(missionId, authentication),
        "co-manager status must not grant the right to change ownership");
  }

  @Test
  void canChangeOwner_OwnerOnly_ShouldReturnTrue() {
    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getPrincipal()).thenReturn("real-jwt-sub");
    when(authentication.getAuthorities())
        .thenAnswer(i -> Collections.singletonList(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")));
    when(missionRepository.findByIdForAuthorization(missionId)).thenReturn(Optional.of(mission));
    when(userService.getCurrentUser()).thenReturn(Optional.of(user));
    mission.setOwner(user);

    assertTrue(missionAccessPolicy.canChangeOwner(missionId, authentication));
  }

  @Test
  void canChangeOwner_OwnerButGetCurrentUserReturnsEmpty_ShouldReturnFalse() {
    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getPrincipal()).thenReturn("real-jwt-sub");
    when(authentication.getAuthorities())
        .thenAnswer(i -> Collections.singletonList(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")));
    when(missionRepository.findByIdForAuthorization(missionId)).thenReturn(Optional.of(mission));
    mission.setOwner(user);
    when(userService.getCurrentUser()).thenReturn(Optional.empty());

    assertFalse(missionAccessPolicy.canChangeOwner(missionId, authentication));
  }

  @Test
  void canChangeOwner_MissionNotFound_ShouldReturnFalse() {
    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getPrincipal()).thenReturn("real-jwt-sub");
    when(authentication.getAuthorities())
        .thenAnswer(i -> Collections.singletonList(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")));
    when(missionRepository.findByIdForAuthorization(missionId)).thenReturn(Optional.empty());

    assertFalse(
        missionAccessPolicy.canChangeOwner(missionId, authentication),
        "a missing mission must NOT default to true (orElse(false))");
  }

  @Test
  void canChangeOwner_NotOwner_ShouldReturnFalse() {
    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getPrincipal()).thenReturn("real-jwt-sub");
    when(authentication.getAuthorities())
        .thenAnswer(i -> Collections.singletonList(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")));
    User differentOwner = new User();
    differentOwner.setId(UUID.randomUUID());
    mission.setOwner(differentOwner);

    when(missionRepository.findByIdForAuthorization(missionId)).thenReturn(Optional.of(mission));
    when(userService.getCurrentUser()).thenReturn(Optional.of(user));

    assertFalse(missionAccessPolicy.canChangeOwner(missionId, authentication));
  }

  @Test
  void canCreateFinanceEntry_MissionManagerInScope_ShouldReturnTrue() {
    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getAuthorities())
        .thenAnswer(
            i -> Collections.singletonList(new SimpleGrantedAuthority("ROLE_MISSION_MANAGER")));
    doReturn(true).when(missionAccessPolicy).canEditMission(missionId);

    assertTrue(
        missionAccessPolicy.canCreateFinanceEntry(missionId, UUID.randomUUID(), authentication));
  }

  @Test
  void canCreateFinanceEntry_MemberBookingOwnParticipantRow_ShouldReturnTrue() {
    UUID participantId = UUID.randomUUID();
    MissionParticipant own = new MissionParticipant();
    own.setId(participantId);
    own.setUser(user);

    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getAuthorities())
        .thenAnswer(i -> Collections.singletonList(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")));
    when(missionRepository.findByIdForAuthorization(missionId)).thenReturn(Optional.empty());
    when(userService.getCurrentUser()).thenReturn(Optional.of(user));
    when(missionParticipantRepository.findByMissionIdAndUserId(missionId, userId))
        .thenReturn(Optional.of(own));

    assertTrue(missionAccessPolicy.canCreateFinanceEntry(missionId, participantId, authentication));
  }

  @Test
  void canCreateFinanceEntry_MemberAttributingToAnotherParticipant_ShouldReturnFalse() {
    MissionParticipant own = new MissionParticipant();
    own.setId(UUID.randomUUID());
    own.setUser(user);

    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getAuthorities())
        .thenAnswer(i -> Collections.singletonList(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")));
    when(missionRepository.findByIdForAuthorization(missionId)).thenReturn(Optional.empty());
    when(userService.getCurrentUser()).thenReturn(Optional.of(user));
    when(missionParticipantRepository.findByMissionIdAndUserId(missionId, userId))
        .thenReturn(Optional.of(own));

    assertFalse(
        missionAccessPolicy.canCreateFinanceEntry(missionId, UUID.randomUUID(), authentication));
  }

  @Test
  void canCreateFinanceEntry_MemberOfAnotherSquadronNotOnTheMission_ShouldReturnFalse() {
    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getAuthorities())
        .thenAnswer(i -> Collections.singletonList(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")));
    when(missionRepository.findByIdForAuthorization(missionId)).thenReturn(Optional.empty());
    when(userService.getCurrentUser()).thenReturn(Optional.of(user));
    when(missionParticipantRepository.findByMissionIdAndUserId(missionId, userId))
        .thenReturn(Optional.empty());

    assertFalse(
        missionAccessPolicy.canCreateFinanceEntry(missionId, UUID.randomUUID(), authentication));
  }

  @Test
  void canCreateFinanceEntry_NullArguments_ShouldReturnFalse() {
    assertFalse(
        missionAccessPolicy.canCreateFinanceEntry(missionId, UUID.randomUUID(), null),
        "an unauthenticated caller must never pass the create gate");
    assertFalse(missionAccessPolicy.canCreateFinanceEntry(null, UUID.randomUUID(), authentication));
    assertFalse(missionAccessPolicy.canCreateFinanceEntry(missionId, null, authentication));
  }

  @Test
  void canEditFinanceEntry_Admin_ShouldReturnTrue() {
    UUID entryId = UUID.randomUUID();
    MissionFinanceEntry entry = new MissionFinanceEntry();
    when(missionFinanceEntryRepository.findById(entryId)).thenReturn(Optional.of(entry));
    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getAuthorities())
        .thenAnswer(i -> Collections.singletonList(new SimpleGrantedAuthority("ROLE_ADMIN")));

    assertTrue(missionAccessPolicy.canEditFinanceEntry(entryId, authentication));
  }

  @Test
  void canEditFinanceEntry_SeatInOwningUnit_ShouldReturnTrue() {
    UUID entryId = UUID.randomUUID();
    UUID unitId = givenOwnedMission();
    MissionFinanceEntry entry = new MissionFinanceEntry();
    entry.setMission(mission);
    when(missionFinanceEntryRepository.findById(entryId)).thenReturn(Optional.of(entry));
    givenAuthenticatedCaller("ROLE_KRT_MEMBER");
    when(ownerScopeService.canActAsOfficerInOwner(unitId)).thenReturn(true);

    assertTrue(missionAccessPolicy.canEditFinanceEntry(entryId, authentication));
  }

  @Test
  void canEditFinanceEntry_PlainMemberOfOwningUnit_ShouldReturnFalse() {
    UUID entryId = UUID.randomUUID();
    UUID unitId = givenOwnedMission();
    User otherUser = new User();
    otherUser.setId(UUID.randomUUID());
    MissionParticipant participant = new MissionParticipant();
    participant.setUser(otherUser);
    MissionFinanceEntry entry = new MissionFinanceEntry();
    entry.setMission(mission);
    entry.setParticipant(participant);
    when(missionFinanceEntryRepository.findById(entryId)).thenReturn(Optional.of(entry));
    givenAuthenticatedCaller("ROLE_KRT_MEMBER");
    when(ownerScopeService.canActAsOfficerInOwner(unitId)).thenReturn(false);
    when(userService.getCurrentUser()).thenReturn(Optional.of(user));

    assertFalse(missionAccessPolicy.canEditFinanceEntry(entryId, authentication));
  }

  @Test
  void canEditFinanceEntry_SeatInOtherUnit_ShouldReturnFalse() {
    UUID entryId = UUID.randomUUID();
    UUID unitId = givenOwnedMission();
    User otherUser = new User();
    otherUser.setId(UUID.randomUUID());
    MissionParticipant participant = new MissionParticipant();
    participant.setUser(otherUser);
    MissionFinanceEntry entry = new MissionFinanceEntry();
    entry.setMission(mission);
    entry.setParticipant(participant);

    when(missionFinanceEntryRepository.findById(entryId)).thenReturn(Optional.of(entry));
    givenAuthenticatedCaller("ROLE_OFFICER");
    when(ownerScopeService.canActAsOfficerInOwner(unitId)).thenReturn(false);
    when(userService.getCurrentUser()).thenReturn(Optional.of(user));

    assertFalse(missionAccessPolicy.canEditFinanceEntry(entryId, authentication));
  }

  @Test
  void canEditFinanceEntry_OwnerStillParticipant_ShouldReturnTrue() {
    UUID entryId = UUID.randomUUID();
    MissionParticipant participant = new MissionParticipant();
    participant.setUser(user);
    MissionFinanceEntry entry = new MissionFinanceEntry();
    entry.setMission(mission);
    entry.setParticipant(participant);

    when(missionFinanceEntryRepository.findById(entryId)).thenReturn(Optional.of(entry));
    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getAuthorities())
        .thenAnswer(i -> Collections.singletonList(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")));
    when(userService.getCurrentUser()).thenReturn(Optional.of(user));
    when(missionParticipantRepository.findByMissionIdAndUserId(missionId, userId))
        .thenReturn(Optional.of(participant));

    assertTrue(missionAccessPolicy.canEditFinanceEntry(entryId, authentication));
  }

  @Test
  void canEditFinanceEntry_OwnerButNoLongerParticipant_ShouldReturnFalse() {
    UUID entryId = UUID.randomUUID();
    MissionParticipant participant = new MissionParticipant();
    participant.setUser(user);
    MissionFinanceEntry entry = new MissionFinanceEntry();
    entry.setMission(mission);
    entry.setParticipant(participant);

    when(missionFinanceEntryRepository.findById(entryId)).thenReturn(Optional.of(entry));
    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getAuthorities())
        .thenAnswer(i -> Collections.singletonList(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")));
    when(userService.getCurrentUser()).thenReturn(Optional.of(user));
    when(missionParticipantRepository.findByMissionIdAndUserId(missionId, userId))
        .thenReturn(Optional.empty());

    assertFalse(missionAccessPolicy.canEditFinanceEntry(entryId, authentication));
  }

  private UUID givenOwnedMission() {
    UUID unitId = UUID.randomUUID();
    OrgUnit unit = new Squadron();
    unit.setId(unitId);
    mission.setOwningOrgUnit(unit);
    lenient()
        .when(missionRepository.findByIdForAuthorization(missionId))
        .thenReturn(Optional.of(mission));
    return unitId;
  }

  private void givenAuthenticatedCaller(String authority) {
    when(authentication.isAuthenticated()).thenReturn(true);
    lenient().when(authentication.getPrincipal()).thenReturn("real-jwt-sub");
    when(authentication.getAuthorities())
        .thenAnswer(i -> Collections.singletonList(new SimpleGrantedAuthority(authority)));
  }
}
