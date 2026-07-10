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
import jakarta.servlet.http.HttpServletRequest;
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

  @Mock private HttpServletRequest request;

  // Real (deterministic) token service so the M1 hash/verify round-trip is genuinely exercised.
  private final GuestParticipantTokenService guestParticipantTokenService =
      new GuestParticipantTokenService();

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
            ownerScopeService,
            guestParticipantTokenService,
            request);

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
    // Phase-6 follow-up: elevated mission roles now need to also pass canEditMission so a
    // mission-manager from squadron A cannot edit squadron B's managers (covered by a separate
    // negative test below).
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
    // Security audit AUTHZ-1 (the negative case promised alongside
    // canManageManagers_GlobalMissionManager_ShouldReturnTrue): a MISSION_MANAGER whose
    // owning-OrgUnit scope does NOT cover the target mission (canEditMission == false) and who is
    // neither the owner nor a listed co-manager must be denied editing the manager list. Without
    // the
    // `&& ownerScopeService.canEditMission(missionId)` scope gate on the elevated-authority branch
    // a
    // mission-manager of squadron A could add/remove co-managers on squadron B's missions.
    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getAuthorities())
        .thenAnswer(
            i -> Collections.singletonList(new SimpleGrantedAuthority("ROLE_MISSION_MANAGER")));
    when(ownerScopeService.canEditMission(missionId)).thenReturn(false);
    when(missionRepository.findByIdForAuthorization(missionId)).thenReturn(Optional.of(mission));
    when(userService.getCurrentUser()).thenReturn(Optional.of(user));
    // mission has no owner and an empty manager list, so the owner/manager fall-through also fails.

    assertFalse(missionSecurityService.canManageManagers(missionId, authentication));
  }

  // ---------------------------------------------------------------------
  // canAccessParticipant: Self-Edit support for logged-in mission participants.
  // A participant may be edited by its owner (participant.user.id == jwt.sub)
  // or by any user with elevated MISSION_MANAGER / OFFICER / ADMIN authority.
  // ---------------------------------------------------------------------

  @Test
  void canAccessParticipant_Owner_ShouldReturnTrue() {
    // Given: participant belongs to current user, no elevated role
    UUID participantId = UUID.randomUUID();
    MissionParticipant participant = new MissionParticipant();
    participant.setId(participantId);
    participant.setMission(mission);
    participant.setUser(user);

    when(missionParticipantRepository.findById(participantId)).thenReturn(Optional.of(participant));
    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getPrincipal()).thenReturn("some-jwt-principal");
    when(userService.getCurrentUser()).thenReturn(Optional.of(user));

    // When / Then: self-edit allowed without any mission-management authority (the self-edit branch
    // returns before the scope gate, so no authorities are inspected).
    assertTrue(
        missionSecurityService.canAccessParticipant(missionId, participantId, authentication));
  }

  @Test
  void canAccessParticipant_ForeignUserWithoutPrivilege_ShouldReturnFalse() {
    // Given: participant belongs to a DIFFERENT user
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

    // When / Then: foreign edit forbidden
    assertFalse(
        missionSecurityService.canAccessParticipant(missionId, participantId, authentication));
  }

  @Test
  void canAccessParticipant_MissionManagerOwnOrgUnit_ShouldReturnTrue() {
    // Given: participant belongs to a DIFFERENT user, but the caller is a MISSION_MANAGER whose
    // owning-OrgUnit scope DOES cover the target mission (canEditMission == true).
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

    // When / Then: scoped mission-manager may manage the participant
    assertTrue(
        missionSecurityService.canAccessParticipant(missionId, participantId, authentication));
  }

  @Test
  void canAccessParticipant_MissionManagerForeignOrgUnit_ShouldReturnFalse() {
    // Security audit AUTHZ-1 regression: a MISSION_MANAGER of a DIFFERENT OrgUnit must NOT manage a
    // user-linked participant on a mission outside their owning-OrgUnit scope. canEditMission is
    // false for the target mission and the caller is neither the owner nor a co-manager, so the
    // only
    // remaining path (self-edit) fails because the participant belongs to someone else.
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

    // When / Then: cross-OrgUnit mission-manager is denied
    assertFalse(
        missionSecurityService.canAccessParticipant(missionId, participantId, authentication));
  }

  @Test
  void canAccessParticipant_AnonymousCaller_ShouldReturnFalse() {
    // Given: registered participant, anonymous caller
    UUID participantId = UUID.randomUUID();
    MissionParticipant participant = new MissionParticipant();
    participant.setId(participantId);
    participant.setMission(mission);
    participant.setUser(user);

    when(missionParticipantRepository.findById(participantId)).thenReturn(Optional.of(participant));
    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getPrincipal()).thenReturn("anonymousUser");

    // When / Then
    assertFalse(
        missionSecurityService.canAccessParticipant(missionId, participantId, authentication));
  }

  // ---------------------------------------------------------------------
  // M1 / REQ-SEC-018: a guest (unlinked) participant is editable only by the
  // anonymous creator presenting the per-row capability token, or by a mission
  // manager / officer / admin. Before M1 a guest row was editable by anyone who
  // knew its id (cross-actor vandalism / payout tampering on public missions).
  // ---------------------------------------------------------------------

  @Test
  void canAccessParticipant_GuestWithValidToken_ShouldReturnTrue() {
    UUID participantId = UUID.randomUUID();
    String token = guestParticipantTokenService.generateToken();
    MissionParticipant participant = new MissionParticipant();
    participant.setId(participantId);
    participant.setMission(mission);
    participant.setUser(null);
    participant.setGuestName("Somebody");
    participant.setGuestEditTokenHash(guestParticipantTokenService.hashToken(token));

    when(missionParticipantRepository.findById(participantId)).thenReturn(Optional.of(participant));
    when(request.getHeader(MissionSecurityService.GUEST_EDIT_TOKEN_HEADER)).thenReturn(token);

    // The creator's matching token authorises the edit without any mission-management role.
    assertTrue(
        missionSecurityService.canAccessParticipant(missionId, participantId, authentication));
  }

  @Test
  void canAccessParticipant_GuestWrongTokenNotManager_ShouldReturnFalse() {
    UUID participantId = UUID.randomUUID();
    String token = guestParticipantTokenService.generateToken();
    MissionParticipant participant = new MissionParticipant();
    participant.setId(participantId);
    participant.setMission(mission);
    participant.setUser(null);
    participant.setGuestName("Somebody");
    participant.setGuestEditTokenHash(guestParticipantTokenService.hashToken(token));

    when(missionParticipantRepository.findById(participantId)).thenReturn(Optional.of(participant));
    // A third party presents a foreign token and is not a mission manager → denied (the core M1
    // fix: knowing the public participant id is no longer enough to mutate the guest row).
    when(request.getHeader(MissionSecurityService.GUEST_EDIT_TOKEN_HEADER))
        .thenReturn("not-the-real-token");
    when(authentication.isAuthenticated()).thenReturn(false);

    assertFalse(
        missionSecurityService.canAccessParticipant(missionId, participantId, authentication));
  }

  @Test
  void canAccessParticipant_GuestNoTokenButManager_ShouldReturnTrue() {
    UUID participantId = UUID.randomUUID();
    MissionParticipant participant = new MissionParticipant();
    participant.setId(participantId);
    participant.setMission(mission);
    participant.setUser(null);
    participant.setGuestName("Somebody");
    // pre-V177-style guest row: no token hash at all — only an elevated caller may edit it.

    when(missionParticipantRepository.findById(participantId)).thenReturn(Optional.of(participant));
    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getAuthorities())
        .thenAnswer(i -> Collections.singletonList(new SimpleGrantedAuthority("ROLE_ADMIN")));

    assertTrue(
        missionSecurityService.canAccessParticipant(missionId, participantId, authentication));
  }

  @Test
  void canAccessParticipant_MissingParticipant_ShouldThrow404() {
    // Regression: a stale frontend row (participant concurrently deleted) must
    // surface as 404 Not Found, not as a generic 500 Internal Server Error.
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
    // Given: participant belongs to a different mission than the one addressed
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

  // ---------------------------------------------------------------------
  // canChangeOwner — tighter than canManageManagers: only the current owner
  // or ROLE_ADMIN / ROLE_OFFICER may transfer mission ownership. Regular
  // co-managers and MISSION_MANAGER role holders MUST NOT be permitted to
  // displace the owner. Each branch below maps to a documented invariant
  // in the production Javadoc (MissionSecurityService.java:172-179).
  // ---------------------------------------------------------------------

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
    // Phase-6 follow-up: officer needs to additionally pass canEditMission so a cross-staffel
    // officer cannot transfer ownership of another squadron's mission.
    when(ownerScopeService.canEditMission(missionId)).thenReturn(true);

    assertTrue(missionSecurityService.canChangeOwner(missionId, authentication));
  }

  @Test
  void canChangeOwner_MissionManagerRole_ShouldReturnFalse() {
    // CRITICAL invariant: MISSION_MANAGER must NOT be permitted to change
    // ownership — that would let a co-manager grab any mission they
    // already manage.
    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getPrincipal()).thenReturn("real-jwt-sub");
    when(authentication.getAuthorities())
        .thenAnswer(
            i -> Collections.singletonList(new SimpleGrantedAuthority("ROLE_MISSION_MANAGER")));
    when(missionRepository.findByIdForAuthorization(missionId)).thenReturn(Optional.of(mission));
    when(userService.getCurrentUser()).thenReturn(Optional.of(user));
    // user is NOT the owner.

    assertFalse(missionSecurityService.canChangeOwner(missionId, authentication));
  }

  @Test
  void canChangeOwner_CoManagerWithoutOfficerRole_ShouldReturnFalse() {
    // Same invariant from the other direction: a manager added to
    // mission.managers but with only ROLE_KRT_MEMBER cannot
    // transfer ownership.
    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getPrincipal()).thenReturn("real-jwt-sub");
    when(authentication.getAuthorities())
        .thenAnswer(i -> Collections.singletonList(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")));
    when(missionRepository.findByIdForAuthorization(missionId)).thenReturn(Optional.of(mission));
    when(userService.getCurrentUser()).thenReturn(Optional.of(user));
    mission.getManagers().add(user); // user is a co-manager
    // user is NOT the owner

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
    // Defensive: even with a valid Authentication, if the local user table
    // doesn't have a matching row (e.g. a freshly-issued JWT before
    // syncUser ran), refuse the operation rather than silently bypassing
    // the owner check.
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

  // ---------------------------------------------------------------------
  // canEditFinanceEntry — security audit H1: ADMIN edits any finance entry,
  // but an OFFICER may edit/delete only within their owning-OrgUnit scope
  // (canEditMission). A cross-OrgUnit officer must be denied — this was the
  // one mission write path left with a global-OFFICER short-circuit.
  // ---------------------------------------------------------------------

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
    // The H1 regression: a cross-OrgUnit officer must NOT mutate another squadron's finance entry.
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
    // The final "still a participant" guard: the caller owns the finance entry (its participant is
    // linked to the current user) but has since been removed from the mission, so
    // findByMissionIdAndUserId returns empty. Per the Javadoc this must deny the edit — otherwise a
    // member expelled from a mission could still tamper with their own payout finance entry. If the
    // `.isPresent()` check were dropped or inverted this would silently pass.
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
