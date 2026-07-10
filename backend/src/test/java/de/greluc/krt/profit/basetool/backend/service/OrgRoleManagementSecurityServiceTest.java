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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.Bereich;
import de.greluc.krt.profit.basetool.backend.model.KommandoGroup;
import de.greluc.krt.profit.basetool.backend.model.MembershipRole;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.model.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.dto.BereichLeadershipRole;
import de.greluc.krt.profit.basetool.backend.repository.KommandoGroupRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

/**
 * Mockito unit tests for {@link OrgRoleManagementSecurityService} — the delegated appointment
 * ladder (epic #800, REQ-ROLE-004). Pins the strictly-higher-tier rule (so self-promotion is
 * impossible), the parent-Bereich-derived-from-the-edge scoping (so a foreign unit is denied), and
 * that the verdict <em>never</em> consults {@code isAdmin()} (admin is decided at the
 * {@code @PreAuthorize} layer).
 */
@ExtendWith(MockitoExtension.class)
class OrgRoleManagementSecurityServiceTest {

  @Mock private AuthHelperService authHelperService;
  @Mock private OrgUnitMembershipRepository membershipRepository;
  @Mock private OrgUnitRepository orgUnitRepository;
  @Mock private KommandoGroupRepository kommandoGroupRepository;

  @InjectMocks private OrgRoleManagementSecurityService service;

  private final UUID callerId = UUID.randomUUID();
  private Authentication authed;

  @BeforeEach
  void setUp() {
    authed = mock(Authentication.class);
    lenient().when(authed.isAuthenticated()).thenReturn(true);
  }

  private OrgUnitMembership membership(UUID userId, UUID orgUnitId, MembershipRole role) {
    OrgUnitMembership m = new OrgUnitMembership();
    m.setId(new OrgUnitMembershipId(userId, orgUnitId));
    m.setRole(role);
    return m;
  }

  private Bereich bereich(UUID id) {
    Bereich b = new Bereich();
    b.setId(id);
    return b;
  }

  private Squadron squadronUnder(UUID id, OrgUnit parent) {
    Squadron s = new Squadron();
    s.setId(id);
    s.setParent(parent);
    return s;
  }

  private SpecialCommand skUnder(UUID id, OrgUnit parent) {
    SpecialCommand sc = new SpecialCommand();
    sc.setId(id);
    sc.setParent(parent);
    return sc;
  }

  private KommandoGroup kommandoGroupOf(UUID squadronId) {
    KommandoGroup g = new KommandoGroup();
    g.setSquadron(squadronUnder(squadronId, null));
    return g;
  }

  private void callerIs(UUID orgUnitId, MembershipRole role) {
    when(authHelperService.currentUserId()).thenReturn(Optional.of(callerId));
    when(membershipRepository.findById(new OrgUnitMembershipId(callerId, orgUnitId)))
        .thenReturn(Optional.of(membership(callerId, orgUnitId, role)));
  }

  // --- squadron ranks -------------------------------------------------------

  @Test
  void staffelleiter_appointedByBereichsleiterOfParent_allowed() {
    UUID squadronId = UUID.randomUUID();
    UUID bereichId = UUID.randomUUID();
    when(orgUnitRepository.findById(squadronId))
        .thenReturn(Optional.of(squadronUnder(squadronId, bereich(bereichId))));
    callerIs(bereichId, MembershipRole.BEREICHSLEITER);

    assertTrue(service.canAssignSquadronRank(squadronId, MembershipRole.STAFFELLEITER, authed));
  }

  @Test
  void staffelleiter_cannotBeAppointedByAStaffelleiter_noSelfPromotion() {
    UUID squadronId = UUID.randomUUID();
    UUID bereichId = UUID.randomUUID();
    when(orgUnitRepository.findById(squadronId))
        .thenReturn(Optional.of(squadronUnder(squadronId, bereich(bereichId))));
    when(authHelperService.currentUserId()).thenReturn(Optional.of(callerId));
    // The caller is NOT the parent Bereich's Bereichsleiter — a Staffelleiter has no membership row
    // on the parent Bereich, so the appointment is denied.
    when(membershipRepository.findById(new OrgUnitMembershipId(callerId, bereichId)))
        .thenReturn(Optional.empty());

    assertFalse(service.canAssignSquadronRank(squadronId, MembershipRole.STAFFELLEITER, authed));
  }

  @Test
  void kommandoleiter_appointedByOwnStaffelleiter_allowed() {
    UUID squadronId = UUID.randomUUID();
    callerIs(squadronId, MembershipRole.STAFFELLEITER);

    assertTrue(service.canAssignSquadronRank(squadronId, MembershipRole.KOMMANDOLEITER, authed));
  }

  @Test
  void kommandoleiter_appointedByForeignStaffelleiter_denied() {
    UUID squadronId = UUID.randomUUID();
    when(authHelperService.currentUserId()).thenReturn(Optional.of(callerId));
    when(membershipRepository.findById(new OrgUnitMembershipId(callerId, squadronId)))
        .thenReturn(Optional.empty());

    assertFalse(service.canAssignSquadronRank(squadronId, MembershipRole.KOMMANDOLEITER, authed));
  }

  @Test
  void assignSquadronRank_nonSquadronRank_denied() {
    assertFalse(
        service.canAssignSquadronRank(UUID.randomUUID(), MembershipRole.BEREICHSLEITER, authed));
  }

  @Test
  void assignSquadronRank_unauthenticated_denied() {
    Authentication anon = mock(Authentication.class);
    when(anon.isAuthenticated()).thenReturn(false);
    assertFalse(
        service.canAssignSquadronRank(UUID.randomUUID(), MembershipRole.KOMMANDOLEITER, anon));
  }

  @Test
  void removeSquadronRank_routesByTargetRank_kommandoleiterRemovableByStaffelleiter() {
    UUID squadronId = UUID.randomUUID();
    UUID targetUser = UUID.randomUUID();
    when(membershipRepository.findById(new OrgUnitMembershipId(targetUser, squadronId)))
        .thenReturn(Optional.of(membership(targetUser, squadronId, MembershipRole.KOMMANDOLEITER)));
    callerIs(squadronId, MembershipRole.STAFFELLEITER);

    assertTrue(service.canRemoveSquadronRank(squadronId, targetUser, authed));
  }

  // --- Bereich ranks --------------------------------------------------------

  @Test
  void bereichsleiter_appointedByPureOlMember_allowed() {
    UUID bereichId = UUID.randomUUID();
    UUID olId = UUID.randomUUID();
    when(authHelperService.currentUserId()).thenReturn(Optional.of(callerId));
    when(membershipRepository.findAllByIdUserId(callerId))
        .thenReturn(List.of(membership(callerId, olId, MembershipRole.OL_MEMBER)));

    assertTrue(service.canAppointBereichRole(bereichId, BereichLeadershipRole.LEITER, authed));
  }

  @Test
  void bereichsleiter_cannotBeAppointedByABereichsleiter_noSelfPromotion() {
    UUID bereichId = UUID.randomUUID();
    when(authHelperService.currentUserId()).thenReturn(Optional.of(callerId));
    when(membershipRepository.findAllByIdUserId(callerId))
        .thenReturn(List.of(membership(callerId, bereichId, MembershipRole.BEREICHSLEITER)));

    assertFalse(service.canAppointBereichRole(bereichId, BereichLeadershipRole.LEITER, authed));
  }

  @Test
  void koordinator_appointedByOwnBereichsleiter_allowed() {
    UUID bereichId = UUID.randomUUID();
    callerIs(bereichId, MembershipRole.BEREICHSLEITER);

    assertTrue(service.canAppointBereichRole(bereichId, BereichLeadershipRole.KOORDINATOR, authed));
  }

  @Test
  void koordinator_appointedByForeignBereichsleiter_denied() {
    UUID bereichId = UUID.randomUUID();
    when(authHelperService.currentUserId()).thenReturn(Optional.of(callerId));
    when(membershipRepository.findById(new OrgUnitMembershipId(callerId, bereichId)))
        .thenReturn(Optional.empty());

    assertFalse(service.canAppointBereichRole(bereichId, BereichLeadershipRole.OPERATOR, authed));
  }

  // --- SK lead --------------------------------------------------------------

  @Test
  void skLead_appointedByBereichsleiterOfParent_allowed() {
    UUID skId = UUID.randomUUID();
    UUID bereichId = UUID.randomUUID();
    when(orgUnitRepository.findById(skId))
        .thenReturn(Optional.of(skUnder(skId, bereich(bereichId))));
    callerIs(bereichId, MembershipRole.BEREICHSLEITER);

    assertTrue(service.canAppointSkLead(skId, authed));
  }

  @Test
  void skLead_appointedByForeignBereichsleiter_denied() {
    UUID skId = UUID.randomUUID();
    UUID bereichId = UUID.randomUUID();
    when(orgUnitRepository.findById(skId))
        .thenReturn(Optional.of(skUnder(skId, bereich(bereichId))));
    when(authHelperService.currentUserId()).thenReturn(Optional.of(callerId));
    when(membershipRepository.findById(new OrgUnitMembershipId(callerId, bereichId)))
        .thenReturn(Optional.empty());

    assertFalse(service.canAppointSkLead(skId, authed));
  }

  // --- Kommandogruppe management + the never-admin invariant -----------------

  @Test
  void manageKommandoGroups_byOwnStaffelleiter_allowed() {
    UUID squadronId = UUID.randomUUID();
    callerIs(squadronId, MembershipRole.STAFFELLEITER);

    assertTrue(service.canManageKommandoGroups(squadronId, authed));
  }

  @Test
  void verdictNeverConsultsIsAdmin() {
    UUID squadronId = UUID.randomUUID();
    callerIs(squadronId, MembershipRole.STAFFELLEITER);

    service.canAssignSquadronRank(squadronId, MembershipRole.ENSIGN, authed);

    // The delegated verdict is rank-derived only; admin is decided at @PreAuthorize, never here.
    verify(authHelperService, never()).isAdmin();
  }

  // --- Bereich role removal (routing by the target's current rank) -----------

  @Test
  void removeBereichRole_bereichsleiterRemovableByPureOlMember_allowed() {
    UUID bereichId = UUID.randomUUID();
    UUID targetUser = UUID.randomUUID();
    UUID olId = UUID.randomUUID();
    when(membershipRepository.findById(new OrgUnitMembershipId(targetUser, bereichId)))
        .thenReturn(Optional.of(membership(targetUser, bereichId, MembershipRole.BEREICHSLEITER)));
    when(authHelperService.currentUserId()).thenReturn(Optional.of(callerId));
    when(membershipRepository.findAllByIdUserId(callerId))
        .thenReturn(List.of(membership(callerId, olId, MembershipRole.OL_MEMBER)));

    assertTrue(service.canRemoveBereichRole(bereichId, targetUser, authed));
  }

  @Test
  void removeBereichRole_koordinatorRemovableByOwnBereichsleiter_allowed() {
    UUID bereichId = UUID.randomUUID();
    UUID targetUser = UUID.randomUUID();
    when(membershipRepository.findById(new OrgUnitMembershipId(targetUser, bereichId)))
        .thenReturn(
            Optional.of(membership(targetUser, bereichId, MembershipRole.BEREICHSKOORDINATOR)));
    callerIs(bereichId, MembershipRole.BEREICHSLEITER);

    assertTrue(service.canRemoveBereichRole(bereichId, targetUser, authed));
  }

  @Test
  void removeBereichRole_koordinatorByForeignBereichsleiter_denied() {
    UUID bereichId = UUID.randomUUID();
    UUID targetUser = UUID.randomUUID();
    when(membershipRepository.findById(new OrgUnitMembershipId(targetUser, bereichId)))
        .thenReturn(
            Optional.of(membership(targetUser, bereichId, MembershipRole.BEREICHSOPERATOR)));
    when(authHelperService.currentUserId()).thenReturn(Optional.of(callerId));
    // The caller does not lead this Bereich (no membership row on it), so removing an Operator of a
    // Bereich they do not lead is denied.
    when(membershipRepository.findById(new OrgUnitMembershipId(callerId, bereichId)))
        .thenReturn(Optional.empty());

    assertFalse(service.canRemoveBereichRole(bereichId, targetUser, authed));
  }

  // --- singular Kommandogruppe management (squadron from the group's edge) ----

  @Test
  void manageKommandoGroup_byStaffelleiterOfGroupsSquadron_allowed() {
    UUID groupId = UUID.randomUUID();
    UUID squadronId = UUID.randomUUID();
    when(kommandoGroupRepository.findById(groupId))
        .thenReturn(Optional.of(kommandoGroupOf(squadronId)));
    callerIs(squadronId, MembershipRole.STAFFELLEITER);

    assertTrue(service.canManageKommandoGroup(groupId, authed));
  }

  @Test
  void manageKommandoGroup_byForeignStaffelleiter_denied() {
    UUID groupId = UUID.randomUUID();
    UUID squadronId = UUID.randomUUID();
    when(kommandoGroupRepository.findById(groupId))
        .thenReturn(Optional.of(kommandoGroupOf(squadronId)));
    when(authHelperService.currentUserId()).thenReturn(Optional.of(callerId));
    // The squadron is read from the group's persisted edge; the caller is not that squadron's
    // Staffelleiter (no membership row on it), so renaming / deleting a foreign group is denied.
    when(membershipRepository.findById(new OrgUnitMembershipId(callerId, squadronId)))
        .thenReturn(Optional.empty());

    assertFalse(service.canManageKommandoGroup(groupId, authed));
  }

  @Test
  void manageKommandoGroup_missingGroup_denied() {
    UUID groupId = UUID.randomUUID();
    when(kommandoGroupRepository.findById(groupId)).thenReturn(Optional.empty());

    assertFalse(service.canManageKommandoGroup(groupId, authed));
  }

  // --- squadron rank removal: the STAFFELLEITER target routes to the parent ---

  @Test
  void removeSquadronRank_staffelleiterRemovableByParentBereichsleiter_allowed() {
    UUID squadronId = UUID.randomUUID();
    UUID bereichId = UUID.randomUUID();
    UUID targetUser = UUID.randomUUID();
    when(membershipRepository.findById(new OrgUnitMembershipId(targetUser, squadronId)))
        .thenReturn(Optional.of(membership(targetUser, squadronId, MembershipRole.STAFFELLEITER)));
    when(orgUnitRepository.findById(squadronId))
        .thenReturn(Optional.of(squadronUnder(squadronId, bereich(bereichId))));
    callerIs(bereichId, MembershipRole.BEREICHSLEITER);

    assertTrue(service.canRemoveSquadronRank(squadronId, targetUser, authed));
  }

  @Test
  void removeSquadronRank_staffelleiterByOwnSquadronStaffelleiter_denied() {
    UUID squadronId = UUID.randomUUID();
    UUID bereichId = UUID.randomUUID();
    UUID targetUser = UUID.randomUUID();
    when(membershipRepository.findById(new OrgUnitMembershipId(targetUser, squadronId)))
        .thenReturn(Optional.of(membership(targetUser, squadronId, MembershipRole.STAFFELLEITER)));
    when(orgUnitRepository.findById(squadronId))
        .thenReturn(Optional.of(squadronUnder(squadronId, bereich(bereichId))));
    when(authHelperService.currentUserId()).thenReturn(Optional.of(callerId));
    // Removing a Staffelleiter routes to the parent Bereichsleiter, never the squadron's own
    // Staffelleiter — a caller who only leads the squadron has no membership row on the parent
    // Bereich and is denied (no self / peer demotion at the same tier).
    when(membershipRepository.findById(new OrgUnitMembershipId(callerId, bereichId)))
        .thenReturn(Optional.empty());

    assertFalse(service.canRemoveSquadronRank(squadronId, targetUser, authed));
  }
}
