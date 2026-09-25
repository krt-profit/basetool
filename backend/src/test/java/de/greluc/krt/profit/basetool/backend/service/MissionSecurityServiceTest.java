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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionFinanceEntry;
import de.greluc.krt.profit.basetool.backend.model.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@ExtendWith(MockitoExtension.class)
class MissionSecurityServiceTest {

  @Mock private MissionRepository missionRepository;

  @Mock private UserService userService;

  @Mock private MissionParticipantRepository missionParticipantRepository;

  @Mock
  private de.greluc.krt.profit.basetool.backend.repository.MissionFinanceEntryRepository
      missionFinanceEntryRepository;

  @Mock private OwnerScopeService ownerScopeService;

  private RoleHierarchy roleHierarchy;

  @InjectMocks private MissionSecurityService missionSecurityService;

  private UUID missionId;
  private UUID userId;
  private User user;
  private Mission mission;
  private Authentication authentication;

  @BeforeEach
  void setUp() {
    roleHierarchy = de.greluc.krt.profit.basetool.backend.config.SecurityConfig.roleHierarchy();
    missionSecurityService =
        new MissionSecurityService(
            missionRepository,
            userService,
            roleHierarchy,
            missionParticipantRepository,
            missionFinanceEntryRepository,
            ownerScopeService);

    missionId = UUID.randomUUID();
    userId = UUID.randomUUID();
    user = new User();
    user.setId(userId);
    mission = new Mission();
    mission.setId(missionId);
    authentication = mock(Authentication.class);
  }

  @Test
  void canManageManagers_GlobalMissionManager_ShouldReturnTrue() {
    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getAuthorities())
        .thenAnswer(
            i -> Collections.singletonList(new SimpleGrantedAuthority("ROLE_MISSION_MANAGER")));
    when(ownerScopeService.canEditMission(missionId)).thenReturn(true);

    assertTrue(missionSecurityService.canManageManagers(missionId, authentication));
  }

  @Test
  void canManageManagers_OfficerRole_ShouldReturnTrue() {
    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getAuthorities())
        .thenAnswer(i -> Collections.singletonList(new SimpleGrantedAuthority("ROLE_OFFICER")));
    when(ownerScopeService.canEditMission(missionId)).thenReturn(true);

    assertTrue(missionSecurityService.canManageManagers(missionId, authentication));
  }

  @Test
  void canManageManagers_MissionManagePermission_ShouldReturnTrue() {
    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getAuthorities())
        .thenAnswer(i -> Collections.singletonList(new SimpleGrantedAuthority("MISSION_MANAGE")));
    when(ownerScopeService.canEditMission(missionId)).thenReturn(true);

    assertTrue(missionSecurityService.canManageManagers(missionId, authentication));
  }

  @Test
  void canManageManagers_Owner_ShouldReturnTrue() {
    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getAuthorities()).thenReturn(Collections.emptyList());
    when(missionRepository.findByIdForAuthorization(missionId)).thenReturn(Optional.of(mission));
    when(userService.getCurrentUser()).thenReturn(Optional.of(user));
    mission.setOwner(user);

    assertTrue(missionSecurityService.canManageManagers(missionId, authentication));
  }

  @Test
  void canManageManagers_CoManager_ShouldReturnTrue() {
    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getAuthorities()).thenReturn(Collections.emptyList());
    when(missionRepository.findByIdForAuthorization(missionId)).thenReturn(Optional.of(mission));
    when(userService.getCurrentUser()).thenReturn(Optional.of(user));
    mission.getManagers().add(user);

    assertTrue(missionSecurityService.canManageManagers(missionId, authentication));
  }

  @Test
  void canManageManagers_RegularUser_ShouldReturnFalse() {
    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getAuthorities()).thenReturn(Collections.emptyList());
    when(missionRepository.findByIdForAuthorization(missionId)).thenReturn(Optional.of(mission));
    when(userService.getCurrentUser()).thenReturn(Optional.of(user));

    assertFalse(missionSecurityService.canManageManagers(missionId, authentication));
  }

  @Test
  void canManageManagers_MissionManagerForeignOrgUnit_ShouldReturnFalse() {
    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getAuthorities())
        .thenAnswer(
            i -> Collections.singletonList(new SimpleGrantedAuthority("ROLE_MISSION_MANAGER")));
    when(ownerScopeService.canEditMission(missionId)).thenReturn(false);
    when(missionRepository.findByIdForAuthorization(missionId)).thenReturn(Optional.of(mission));
    when(userService.getCurrentUser()).thenReturn(Optional.of(user));

    assertFalse(missionSecurityService.canManageManagers(missionId, authentication));
  }

  @Test
  void canAccessParticipant_Owner_ShouldReturnTrue() {
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
        missionSecurityService.canAccessParticipant(missionId, participantId, authentication));
  }

  @Test
  void canAccessParticipant_ForeignUserWithoutPrivilege_ShouldReturnFalse() {
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
    when(missionRepository.findByIdForAuthorization(missionId)).thenReturn(Optional.of(mission));
    when(userService.getCurrentUser()).thenReturn(Optional.of(user));

    assertFalse(
        missionSecurityService.canAccessParticipant(missionId, participantId, authentication));
  }

  @Test
  void canAccessParticipant_MissionManagerOwnOrgUnit_ShouldReturnTrue() {
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
    when(ownerScopeService.canEditMission(missionId)).thenReturn(true);

    assertTrue(
        missionSecurityService.canAccessParticipant(missionId, participantId, authentication));
  }

  @Test
  void canAccessParticipant_MissionManagerForeignOrgUnit_ShouldReturnFalse() {
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
    when(ownerScopeService.canEditMission(missionId)).thenReturn(false);
    when(missionRepository.findByIdForAuthorization(missionId)).thenReturn(Optional.of(mission));
    when(userService.getCurrentUser()).thenReturn(Optional.of(user));

    assertFalse(
        missionSecurityService.canAccessParticipant(missionId, participantId, authentication));
  }

  @Test
  void canAccessParticipant_AnonymousCaller_ShouldReturnFalse() {
    UUID participantId = UUID.randomUUID();
    MissionParticipant participant = new MissionParticipant();
    participant.setId(participantId);
    participant.setMission(mission);
    participant.setUser(user);

    when(missionParticipantRepository.findById(participantId)).thenReturn(Optional.of(participant));
    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getPrincipal()).thenReturn("anonymousUser");

    assertFalse(
        missionSecurityService.canAccessParticipant(missionId, participantId, authentication));
  }

  /** An external row is not editable by a member who cannot manage the mission. */
  @Test
  void canAccessParticipant_ExternalRowPlainMember_ShouldReturnFalse() {
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
        missionSecurityService.canAccessParticipant(missionId, participantId, authentication));
  }

  /**
   * An external row is not editable by an anonymous caller either.
   *
   * <p>Nothing anonymous reaches this service any more — the security matrix refuses first — but
   * the gate must not depend on that. {@code canManageMission} spells the principal test out for
   * exactly this reason: an {@code AnonymousAuthenticationToken} IS authenticated and carries
   * {@code ROLE_ANONYMOUS}, so a bare {@code isAuthenticated()} check would let it through.
   */
  @Test
  void canAccessParticipant_ExternalRowAnonymous_ShouldReturnFalse() {
    UUID participantId = UUID.randomUUID();
    MissionParticipant participant = new MissionParticipant();
    participant.setId(participantId);
    participant.setMission(mission);
    participant.setUser(null);
    participant.setGuestName("Somebody");

    when(missionParticipantRepository.findById(participantId)).thenReturn(Optional.of(participant));
    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getPrincipal()).thenReturn("anonymousUser");

    assertFalse(
        missionSecurityService.canAccessParticipant(missionId, participantId, authentication));
  }

  /** The leadership may edit an external row — the rule D4 kept. */
  @Test
  void canAccessParticipant_ExternalRowManager_ShouldReturnTrue() {
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
        missionSecurityService.canAccessParticipant(missionId, participantId, authentication));
  }

  @Test
  void canAccessParticipant_MissingParticipant_ShouldThrow404() {
    UUID participantId = UUID.randomUUID();
    when(missionParticipantRepository.findById(participantId)).thenReturn(Optional.empty());

    NotFoundException ex =
        assertThrows(
            NotFoundException.class,
            () ->
                missionSecurityService.canAccessParticipant(
                    missionId, participantId, authentication));
  }

  @Test
  void canAccessParticipant_MissionMismatch_ShouldReturnFalse() {
    UUID participantId = UUID.randomUUID();
    Mission otherMission = new Mission();
    otherMission.setId(UUID.randomUUID());
    MissionParticipant participant = new MissionParticipant();
    participant.setId(participantId);
    participant.setMission(otherMission);
    participant.setUser(user);

    when(missionParticipantRepository.findById(participantId)).thenReturn(Optional.of(participant));

    assertFalse(
        missionSecurityService.canAccessParticipant(missionId, participantId, authentication));
  }

  @Test
  void canChangeOwner_NullAuthentication_ShouldReturnFalse() {
    assertFalse(missionSecurityService.canChangeOwner(missionId, null));
  }

  @Test
  void canChangeOwner_NotAuthenticated_ShouldReturnFalse() {
    when(authentication.isAuthenticated()).thenReturn(false);

    assertFalse(missionSecurityService.canChangeOwner(missionId, authentication));
  }

  @Test
  void canChangeOwner_AnonymousPrincipal_ShouldReturnFalse() {
    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getPrincipal()).thenReturn("anonymousUser");

    assertFalse(missionSecurityService.canChangeOwner(missionId, authentication));
  }

  @Test
  void canChangeOwner_AdminRole_ShouldReturnTrue() {
    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getPrincipal()).thenReturn("real-jwt-sub");
    when(authentication.getAuthorities())
        .thenAnswer(i -> Collections.singletonList(new SimpleGrantedAuthority("ROLE_ADMIN")));

    assertTrue(missionSecurityService.canChangeOwner(missionId, authentication));
  }

  @Test
  void canChangeOwner_OfficerRole_ShouldReturnTrue() {
    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getPrincipal()).thenReturn("real-jwt-sub");
    when(authentication.getAuthorities())
        .thenAnswer(i -> Collections.singletonList(new SimpleGrantedAuthority("ROLE_OFFICER")));
    when(ownerScopeService.canEditMission(missionId)).thenReturn(true);

    assertTrue(missionSecurityService.canChangeOwner(missionId, authentication));
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

    assertFalse(missionSecurityService.canChangeOwner(missionId, authentication));
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
        missionSecurityService.canChangeOwner(missionId, authentication),
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

    assertTrue(missionSecurityService.canChangeOwner(missionId, authentication));
  }

  @Test
  void canChangeOwner_OwnerButGetCurrentUserReturnsEmpty_ShouldReturnFalse() {
    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getPrincipal()).thenReturn("real-jwt-sub");
    when(authentication.getAuthorities())
        .thenAnswer(i -> Collections.singletonList(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")));
    when(userService.getCurrentUser()).thenReturn(Optional.empty());

    assertFalse(missionSecurityService.canChangeOwner(missionId, authentication));
  }

  @Test
  void canChangeOwner_MissionNotFound_ShouldReturnFalse() {
    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getPrincipal()).thenReturn("real-jwt-sub");
    when(authentication.getAuthorities())
        .thenAnswer(i -> Collections.singletonList(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")));
    when(missionRepository.findByIdForAuthorization(missionId)).thenReturn(Optional.empty());
    when(userService.getCurrentUser()).thenReturn(Optional.of(user));

    assertFalse(
        missionSecurityService.canChangeOwner(missionId, authentication),
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

    assertFalse(missionSecurityService.canChangeOwner(missionId, authentication));
  }

  @Test
  void canCreateFinanceEntry_MissionManagerInScope_ShouldReturnTrue() {
    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getAuthorities())
        .thenAnswer(
            i -> Collections.singletonList(new SimpleGrantedAuthority("ROLE_MISSION_MANAGER")));
    when(ownerScopeService.canEditMission(missionId)).thenReturn(true);

    assertTrue(
        missionSecurityService.canCreateFinanceEntry(missionId, UUID.randomUUID(), authentication));
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

    assertTrue(
        missionSecurityService.canCreateFinanceEntry(missionId, participantId, authentication));
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
        missionSecurityService.canCreateFinanceEntry(missionId, UUID.randomUUID(), authentication));
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
        missionSecurityService.canCreateFinanceEntry(missionId, UUID.randomUUID(), authentication));
  }

  @Test
  void canCreateFinanceEntry_NullArguments_ShouldReturnFalse() {
    assertFalse(
        missionSecurityService.canCreateFinanceEntry(missionId, UUID.randomUUID(), null),
        "an unauthenticated caller must never pass the create gate");
    assertFalse(
        missionSecurityService.canCreateFinanceEntry(null, UUID.randomUUID(), authentication));
    assertFalse(missionSecurityService.canCreateFinanceEntry(missionId, null, authentication));
  }

  @Test
  void canEditFinanceEntry_Admin_ShouldReturnTrue() {
    UUID entryId = UUID.randomUUID();
    MissionFinanceEntry entry = new MissionFinanceEntry();
    when(missionFinanceEntryRepository.findById(entryId)).thenReturn(Optional.of(entry));
    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getAuthorities())
        .thenAnswer(i -> Collections.singletonList(new SimpleGrantedAuthority("ROLE_ADMIN")));

    assertTrue(missionSecurityService.canEditFinanceEntry(entryId, authentication));
  }

  @Test
  void canEditFinanceEntry_OfficerInScope_ShouldReturnTrue() {
    UUID entryId = UUID.randomUUID();
    MissionFinanceEntry entry = new MissionFinanceEntry();
    entry.setMission(mission);
    when(missionFinanceEntryRepository.findById(entryId)).thenReturn(Optional.of(entry));
    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getAuthorities())
        .thenAnswer(i -> Collections.singletonList(new SimpleGrantedAuthority("ROLE_OFFICER")));
    when(ownerScopeService.canEditMission(missionId)).thenReturn(true);

    assertTrue(missionSecurityService.canEditFinanceEntry(entryId, authentication));
  }

  @Test
  void canEditFinanceEntry_OfficerForeignOrgUnit_ShouldReturnFalse() {
    UUID entryId = UUID.randomUUID();
    User otherUser = new User();
    otherUser.setId(UUID.randomUUID());
    MissionParticipant participant = new MissionParticipant();
    participant.setUser(otherUser);
    MissionFinanceEntry entry = new MissionFinanceEntry();
    entry.setMission(mission);
    entry.setParticipant(participant);

    when(missionFinanceEntryRepository.findById(entryId)).thenReturn(Optional.of(entry));
    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getAuthorities())
        .thenAnswer(i -> Collections.singletonList(new SimpleGrantedAuthority("ROLE_OFFICER")));
    when(ownerScopeService.canEditMission(missionId)).thenReturn(false);
    when(userService.getCurrentUser()).thenReturn(Optional.of(user));

    assertFalse(missionSecurityService.canEditFinanceEntry(entryId, authentication));
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

    assertTrue(missionSecurityService.canEditFinanceEntry(entryId, authentication));
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

    assertFalse(missionSecurityService.canEditFinanceEntry(entryId, authentication));
  }
}
