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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.DuplicateEntityException;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mapper.OrgUnitMembershipMapper;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.Bereich;
import de.greluc.krt.profit.basetool.backend.model.KommandoGroup;
import de.greluc.krt.profit.basetool.backend.model.MembershipRole;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.model.Organisationsleitung;
import de.greluc.krt.profit.basetool.backend.model.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.BereichLeadershipRole;
import de.greluc.krt.profit.basetool.backend.model.dto.MembershipDeltaRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.MembershipFlagsPatchRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.MembershipLeadToggleRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgUnitMembershipDto;
import de.greluc.krt.profit.basetool.backend.repository.KommandoGroupRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Mockito unit tests for {@link OrgUnitMembershipService}. Pins the CRUD contract that the SK
 * member-management UI relies on: listing through the SK existence guard, add/remove happy paths
 * plus the duplicate-409 and not-found-404 paths, the flag-patch semantics including
 * optimistic-lock failures, and the dedicated lead toggle. The {@code …Dto} projection wrappers
 * (L4, #923, ADR-0067) are covered against the real MapStruct mapper so the wire shape — incl. the
 * flushed {@code @Version} the client must echo back (REQ-FE-003) — is asserted, not mocked.
 */
@ExtendWith(MockitoExtension.class)
class OrgUnitMembershipServiceTest {

  @Mock private OrgUnitMembershipRepository membershipRepository;
  @Mock private SpecialCommandService specialCommandService;
  @Mock private UserRepository userRepository;
  @Mock private SquadronRepository squadronRepository;
  @Mock private OrgUnitRepository orgUnitRepository;
  @Mock private KommandoGroupRepository kommandoGroupRepository;
  @Mock private InventoryOrgUnitReconciler inventoryReconciler;
  @Mock private AuditService auditService;
  @Mock private OrgChartService orgChartService;

  // The responsible-holder audit seam is injected as an ObjectProvider (ADR-0070); the leadership
  // mutations resolve it to snapshot/record a responsible-holder change. Stubbed lenient below so
  // the
  // non-leadership tests do not trip strict-stubs; the seam mock's default (null snapshot, no-op
  // record) leaves those flows unaffected.
  @Mock
  private ObjectProvider<OrgUnitBankResponsibilityService> orgUnitBankResponsibilityServiceProvider;

  @Mock private OrgUnitBankResponsibilityService orgUnitBankResponsibilityService;

  // Real MapStruct implementation (not a mock): the …Dto projection tests below assert the actual
  // entity→DTO mapping the controllers ship to the client, incl. the user.effectiveName read and
  // the derived isLead flag (L4, #923, ADR-0067).
  @Spy
  private OrgUnitMembershipMapper orgUnitMembershipMapper =
      Mappers.getMapper(OrgUnitMembershipMapper.class);

  @InjectMocks private OrgUnitMembershipService membershipService;

  private SpecialCommand sc;
  private UUID scId;
  private User user;
  private UUID userId;
  private OrgUnitMembershipId id;

  @BeforeEach
  void setUp() {
    scId = UUID.randomUUID();
    sc = new SpecialCommand();
    sc.setId(scId);
    sc.setName("Alpha");
    sc.setShorthand("ALF");

    userId = UUID.randomUUID();
    user = new User();
    user.setId(userId);
    user.setUsername("alice");
    user.setDisplayName("Alice");

    id = new OrgUnitMembershipId(userId, scId);

    // The leadership mutations resolve the bank seam through the provider (ADR-0070). Lenient so
    // the
    // many non-leadership tests do not trip strict-stubs; the seam mock no-ops the snapshot/record.
    lenient()
        .when(orgUnitBankResponsibilityServiceProvider.getObject())
        .thenReturn(orgUnitBankResponsibilityService);
    // The membership-removal paths (removeMember, reconcileStaffelMemberships) merge the seam's
    // per-org-unit snapshot; a non-null map keeps the reconcile putAll from NPE-ing on the mock.
    lenient()
        .when(orgUnitBankResponsibilityService.snapshotResponsibleHolders(any()))
        .thenReturn(Map.of());
  }

  // --- addMember ------------------------------------------------------------

  @Test
  void addMember_freshUser_persistsMembership() {
    when(specialCommandService.getSpecialCommandById(scId)).thenReturn(sc);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(membershipRepository.existsByIdUserIdAndIdOrgUnitId(userId, scId)).thenReturn(false);
    when(membershipRepository.save(any(OrgUnitMembership.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    OrgUnitMembership saved = membershipService.addMember(scId, userId);

    assertSame(user, saved.getUser());
    assertEquals(OrgUnitKind.SPECIAL_COMMAND, saved.getKind());
    assertNotNull(saved.getJoinedAt());
    assertEquals(userId, saved.getId().getUserId());
    assertEquals(scId, saved.getId().getOrgUnitId());
    verify(membershipRepository).save(any(OrgUnitMembership.class));
    verify(auditService)
        .record(eq(AuditEventType.MEMBERSHIP_GRANTED), eq(scId), any(), eq(userId), any());
  }

  @Test
  void addMember_alreadyMember_throwsDuplicate() {
    when(specialCommandService.getSpecialCommandById(scId)).thenReturn(sc);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(membershipRepository.existsByIdUserIdAndIdOrgUnitId(userId, scId)).thenReturn(true);

    assertThrows(DuplicateEntityException.class, () -> membershipService.addMember(scId, userId));
    verify(membershipRepository, never()).save(any());
  }

  @Test
  void addMember_unknownUser_throwsNotFound() {
    when(specialCommandService.getSpecialCommandById(scId)).thenReturn(sc);
    when(userRepository.findById(userId)).thenReturn(Optional.empty());

    assertThrows(NotFoundException.class, () -> membershipService.addMember(scId, userId));
    verify(membershipRepository, never()).save(any());
  }

  @Test
  void addMember_unknownSc_throwsNotFound() {
    when(specialCommandService.getSpecialCommandById(scId))
        .thenThrow(new NotFoundException("SpecialCommand not found"));

    assertThrows(NotFoundException.class, () -> membershipService.addMember(scId, userId));
    verify(userRepository, never()).findById(any());
    verify(membershipRepository, never()).save(any());
  }

  @Test
  void addMember_firstMembership_promotesOwnerlessInventory() {
    when(specialCommandService.getSpecialCommandById(scId)).thenReturn(sc);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(membershipRepository.existsByIdUserIdAndIdOrgUnitId(userId, scId)).thenReturn(false);
    when(membershipRepository.countByIdUserId(userId)).thenReturn(0L);
    when(membershipRepository.save(any(OrgUnitMembership.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    membershipService.addMember(scId, userId);

    verify(inventoryReconciler).onUserGainedFirstOrgUnit(userId, sc);
  }

  @Test
  void addMember_userAlreadyHadMemberships_doesNotPromoteInventory() {
    when(specialCommandService.getSpecialCommandById(scId)).thenReturn(sc);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(membershipRepository.existsByIdUserIdAndIdOrgUnitId(userId, scId)).thenReturn(false);
    when(membershipRepository.countByIdUserId(userId)).thenReturn(2L);
    when(membershipRepository.save(any(OrgUnitMembership.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    membershipService.addMember(scId, userId);

    verify(inventoryReconciler, never()).onUserGainedFirstOrgUnit(any(), any());
  }

  // --- removeMember ---------------------------------------------------------

  @Test
  void removeMember_existing_deletes() {
    when(specialCommandService.getSpecialCommandById(scId)).thenReturn(sc);
    when(membershipRepository.existsById(id)).thenReturn(true);

    membershipService.removeMember(scId, userId);

    verify(membershipRepository).deleteById(id);
    verify(orgChartService).mirrorRemoveUnitSeat(scId, userId);
    verify(auditService)
        .record(eq(AuditEventType.MEMBERSHIP_REVOKED), eq(scId), any(), eq(userId), any());
  }

  @Test
  void removeMember_nonMember_throwsNotFound() {
    when(specialCommandService.getSpecialCommandById(scId)).thenReturn(sc);
    when(membershipRepository.existsById(id)).thenReturn(false);

    assertThrows(NotFoundException.class, () -> membershipService.removeMember(scId, userId));
    verify(membershipRepository, never()).deleteById(any(OrgUnitMembershipId.class));
  }

  @Test
  void removeMember_lastMembership_demotesInventoryToPersonal() {
    when(specialCommandService.getSpecialCommandById(scId)).thenReturn(sc);
    when(membershipRepository.existsById(id)).thenReturn(true);
    when(membershipRepository.countByIdUserId(userId)).thenReturn(0L);

    membershipService.removeMember(scId, userId);

    verify(inventoryReconciler).onUserLostLastOrgUnit(userId);
  }

  @Test
  void removeMember_userStillHasMemberships_doesNotDemoteInventory() {
    when(specialCommandService.getSpecialCommandById(scId)).thenReturn(sc);
    when(membershipRepository.existsById(id)).thenReturn(true);
    when(membershipRepository.countByIdUserId(userId)).thenReturn(1L);

    membershipService.removeMember(scId, userId);

    verify(inventoryReconciler, never()).onUserLostLastOrgUnit(any());
  }

  // --- patchFlags -----------------------------------------------------------

  @Test
  void patchFlags_bothFlagsSet_updatesBoth() {
    OrgUnitMembership m = new OrgUnitMembership();
    m.setVersion(3L);
    m.setLogistician(false);
    m.setMissionManager(false);
    MembershipFlagsPatchRequest request = new MembershipFlagsPatchRequest(true, true, 3L);
    when(specialCommandService.getSpecialCommandById(scId)).thenReturn(sc);
    when(membershipRepository.findById(id)).thenReturn(Optional.of(m));
    when(membershipRepository.saveAndFlush(m)).thenReturn(m);

    OrgUnitMembership updated = membershipService.patchFlags(scId, userId, request);

    assertTrue(updated.isLogistician());
    assertTrue(updated.isMissionManager());
    verify(auditService)
        .record(eq(AuditEventType.CAPABILITY_FLAGS_CHANGED), eq(scId), any(), eq(userId), any());
  }

  @Test
  void patchFlags_onlyLogistician_leavesMissionManagerAlone() {
    OrgUnitMembership m = new OrgUnitMembership();
    m.setVersion(0L);
    m.setLogistician(false);
    m.setMissionManager(true); // pre-existing true
    MembershipFlagsPatchRequest request = new MembershipFlagsPatchRequest(true, null, 0L);
    when(specialCommandService.getSpecialCommandById(scId)).thenReturn(sc);
    when(membershipRepository.findById(id)).thenReturn(Optional.of(m));
    when(membershipRepository.saveAndFlush(m)).thenReturn(m);

    OrgUnitMembership updated = membershipService.patchFlags(scId, userId, request);

    assertTrue(updated.isLogistician());
    assertTrue(updated.isMissionManager(), "missionManager must stay true when not in payload");
  }

  @Test
  void patchFlags_staleVersion_throwsOptimisticLock() {
    OrgUnitMembership m = new OrgUnitMembership();
    m.setVersion(5L);
    MembershipFlagsPatchRequest request = new MembershipFlagsPatchRequest(true, null, 0L);
    when(specialCommandService.getSpecialCommandById(scId)).thenReturn(sc);
    when(membershipRepository.findById(id)).thenReturn(Optional.of(m));

    assertThrows(
        ObjectOptimisticLockingFailureException.class,
        () -> membershipService.patchFlags(scId, userId, request));
    verify(membershipRepository, never()).saveAndFlush(any());
  }

  @Test
  void patchFlags_unknownMembership_throwsNotFound() {
    MembershipFlagsPatchRequest request = new MembershipFlagsPatchRequest(true, null, 0L);
    when(specialCommandService.getSpecialCommandById(scId)).thenReturn(sc);
    when(membershipRepository.findById(id)).thenReturn(Optional.empty());

    assertThrows(
        NotFoundException.class, () -> membershipService.patchFlags(scId, userId, request));
  }

  // --- toggleLead -----------------------------------------------------------

  @Test
  void toggleLead_promotes() {
    OrgUnitMembership m = new OrgUnitMembership();
    m.setVersion(0L);
    MembershipLeadToggleRequest request = new MembershipLeadToggleRequest(true, 0L);
    when(specialCommandService.getSpecialCommandById(scId)).thenReturn(sc);
    when(membershipRepository.findById(id)).thenReturn(Optional.of(m));
    when(membershipRepository.saveAndFlush(m)).thenReturn(m);

    OrgUnitMembership updated = membershipService.toggleLead(scId, userId, request);

    assertEquals(MembershipRole.SK_LEAD, updated.getRole());
    verify(orgChartService).mirrorSkLead(scId, userId, true);
    verify(auditService)
        .record(eq(AuditEventType.ROLE_GRANTED), eq(scId), any(), eq(userId), any());
  }

  @Test
  void toggleLead_demotes() {
    OrgUnitMembership m = new OrgUnitMembership();
    m.setVersion(2L);
    m.setRole(MembershipRole.SK_LEAD);
    MembershipLeadToggleRequest request = new MembershipLeadToggleRequest(false, 2L);
    when(specialCommandService.getSpecialCommandById(scId)).thenReturn(sc);
    when(membershipRepository.findById(id)).thenReturn(Optional.of(m));
    when(membershipRepository.saveAndFlush(m)).thenReturn(m);

    OrgUnitMembership updated = membershipService.toggleLead(scId, userId, request);

    assertEquals(MembershipRole.MEMBER, updated.getRole());
    verify(orgChartService).mirrorSkLead(scId, userId, false);
    verify(auditService)
        .record(eq(AuditEventType.ROLE_REVOKED), eq(scId), any(), eq(userId), any());
  }

  @Test
  void toggleLead_staleVersion_throwsOptimisticLock() {
    OrgUnitMembership m = new OrgUnitMembership();
    m.setVersion(5L);
    MembershipLeadToggleRequest request = new MembershipLeadToggleRequest(true, 0L);
    when(specialCommandService.getSpecialCommandById(scId)).thenReturn(sc);
    when(membershipRepository.findById(id)).thenReturn(Optional.of(m));

    assertThrows(
        ObjectOptimisticLockingFailureException.class,
        () -> membershipService.toggleLead(scId, userId, request));
  }

  @Test
  void toggleLead_userHoldsStaffel_throwsBadRequest() {
    // REQ-ORG-017: an SK-Leiter holds no Staffel — promoting a user who still belongs to a Staffel
    // is rejected with a clean 400 (the V165 trigger is the DB backstop).
    OrgUnitMembership m = new OrgUnitMembership();
    m.setVersion(0L);
    MembershipLeadToggleRequest request = new MembershipLeadToggleRequest(true, 0L);
    when(specialCommandService.getSpecialCommandById(scId)).thenReturn(sc);
    when(membershipRepository.findById(id)).thenReturn(Optional.of(m));
    when(membershipRepository.findAllByIdUserIdAndKind(userId, OrgUnitKind.SQUADRON))
        .thenReturn(List.of(new OrgUnitMembership()));

    assertThrows(
        BadRequestException.class, () -> membershipService.toggleLead(scId, userId, request));
    verify(membershipRepository, never()).saveAndFlush(any());
  }

  // --- reconcileStaffelMemberships (REQ-ORG-017: up to two Staffeln) ---------

  @Test
  void reconcileStaffelMemberships_addsFirstStaffel_promotesInventoryAndAuditsGranted() {
    UUID squadronA = UUID.randomUUID();
    Squadron sqA = new Squadron();
    sqA.setId(squadronA);
    sqA.setShorthand("IRI");
    when(membershipRepository.findAllByIdUserId(userId)).thenReturn(List.of());
    when(squadronRepository.findById(squadronA)).thenReturn(Optional.of(sqA));
    when(membershipRepository.countByIdUserId(userId)).thenReturn(0L);
    when(membershipRepository.findAllByIdUserIdAndKind(userId, OrgUnitKind.SQUADRON))
        .thenReturn(List.of());

    membershipService.reconcileStaffelMemberships(
        user, List.of(new MembershipDeltaRequest.StaffelChange(squadronA, true, false)));

    verify(membershipRepository).save(any(OrgUnitMembership.class));
    verify(auditService)
        .record(eq(AuditEventType.MEMBERSHIP_GRANTED), eq(squadronA), any(), eq(userId), any());
    verify(inventoryReconciler).onUserGainedFirstOrgUnit(userId, sqA);
  }

  @Test
  void reconcileStaffelMemberships_addsTwoStaffelnToZeroMembership_adoptsNameSortedPrimary() {
    // REQ-ORG-017: a brand-new member assigned two Staffeln at once must have their ownerless
    // inventory adopted by the name-sorted PRIMARY of the two — not whichever Staffel the client
    // listed first — so inventory ownership matches UserDto.squadron and the create-time
    // auto-stamp.
    UUID squadronZeta = UUID.randomUUID();
    UUID squadronAlpha = UUID.randomUUID();
    Squadron sqZeta = new Squadron();
    sqZeta.setId(squadronZeta);
    sqZeta.setName("Zeta");
    Squadron sqAlpha = new Squadron();
    sqAlpha.setId(squadronAlpha);
    sqAlpha.setName("Alpha");
    when(membershipRepository.findAllByIdUserId(userId)).thenReturn(List.of());
    when(membershipRepository.countByIdUserId(userId)).thenReturn(0L);
    when(membershipRepository.findAllByIdUserIdAndKind(userId, OrgUnitKind.SQUADRON))
        .thenReturn(List.of());
    when(squadronRepository.findById(squadronZeta)).thenReturn(Optional.of(sqZeta));
    when(squadronRepository.findById(squadronAlpha)).thenReturn(Optional.of(sqAlpha));

    // Client lists Zeta FIRST, Alpha second: the request-order-first is Zeta, but Alpha is the
    // name-sorted primary — the inventory must adopt Alpha.
    membershipService.reconcileStaffelMemberships(
        user,
        List.of(
            new MembershipDeltaRequest.StaffelChange(squadronZeta, false, false),
            new MembershipDeltaRequest.StaffelChange(squadronAlpha, false, false)));

    verify(inventoryReconciler).onUserGainedFirstOrgUnit(userId, sqAlpha);
    verify(inventoryReconciler, never()).onUserGainedFirstOrgUnit(userId, sqZeta);
  }

  @Test
  void reconcileStaffelMemberships_duplicateSquadron_throwsBadRequest() {
    UUID squadronA = UUID.randomUUID();
    assertThrows(
        BadRequestException.class,
        () ->
            membershipService.reconcileStaffelMemberships(
                user,
                List.of(
                    new MembershipDeltaRequest.StaffelChange(squadronA, false, false),
                    new MembershipDeltaRequest.StaffelChange(squadronA, true, false))));
    verify(membershipRepository, never()).save(any());
  }

  @Test
  void reconcileStaffelMemberships_moreThanTwoSquadrons_throwsBadRequest() {
    assertThrows(
        BadRequestException.class,
        () ->
            membershipService.reconcileStaffelMemberships(
                user,
                List.of(
                    new MembershipDeltaRequest.StaffelChange(UUID.randomUUID(), false, false),
                    new MembershipDeltaRequest.StaffelChange(UUID.randomUUID(), false, false),
                    new MembershipDeltaRequest.StaffelChange(UUID.randomUUID(), false, false))));
    verify(membershipRepository, never()).save(any());
  }

  @Test
  void reconcileStaffelMemberships_userHoldsLeadershipRole_throwsBadRequest() {
    UUID squadronA = UUID.randomUUID();
    OrgUnitMembership leadRow = new OrgUnitMembership();
    leadRow.setRole(MembershipRole.SK_LEAD);
    when(membershipRepository.findAllByIdUserId(userId)).thenReturn(List.of(leadRow));

    assertThrows(
        BadRequestException.class,
        () ->
            membershipService.reconcileStaffelMemberships(
                user, List.of(new MembershipDeltaRequest.StaffelChange(squadronA, false, false))));
    verify(squadronRepository, never()).findById(any());
    verify(membershipRepository, never()).save(any());
  }

  @Test
  void reconcileStaffelMemberships_removesAbsentStaffel_demotesAndAuditsRevoked() {
    UUID squadronA = UUID.randomUUID();
    OrgUnitMembership rowA = new OrgUnitMembership();
    rowA.setId(new OrgUnitMembershipId(userId, squadronA));
    // before = 1, after the delete = 0 → the inventory demotes back to ownerless-personal.
    when(membershipRepository.countByIdUserId(userId)).thenReturn(1L, 0L);
    when(membershipRepository.findAllByIdUserIdAndKind(userId, OrgUnitKind.SQUADRON))
        .thenReturn(List.of(rowA));

    membershipService.reconcileStaffelMemberships(user, List.of());

    verify(membershipRepository).deleteAll(List.of(rowA));
    verify(auditService)
        .record(eq(AuditEventType.MEMBERSHIP_REVOKED), eq(squadronA), any(), eq(userId), any());
    verify(orgChartService).mirrorRemoveSquadronRank(squadronA, userId);
    verify(inventoryReconciler).onUserLostLastOrgUnit(userId);
  }

  @Test
  void reconcileStaffelMemberships_patchesFlagsInPlace_whenSquadronStays() {
    UUID squadronA = UUID.randomUUID();
    Squadron sqA = new Squadron();
    sqA.setId(squadronA);
    OrgUnitMembership rowA = new OrgUnitMembership();
    rowA.setId(new OrgUnitMembershipId(userId, squadronA));
    rowA.setLogistician(false);
    rowA.setMissionManager(false);
    when(membershipRepository.findAllByIdUserId(userId)).thenReturn(List.of(rowA));
    when(squadronRepository.findById(squadronA)).thenReturn(Optional.of(sqA));
    when(membershipRepository.countByIdUserId(userId)).thenReturn(1L);
    when(membershipRepository.findAllByIdUserIdAndKind(userId, OrgUnitKind.SQUADRON))
        .thenReturn(List.of(rowA));
    when(membershipRepository.saveAndFlush(rowA)).thenReturn(rowA);

    membershipService.reconcileStaffelMemberships(
        user, List.of(new MembershipDeltaRequest.StaffelChange(squadronA, true, false)));

    assertTrue(rowA.isLogistician());
    verify(membershipRepository).saveAndFlush(rowA);
    verify(membershipRepository, never()).save(any());
    verify(auditService)
        .record(
            eq(AuditEventType.CAPABILITY_FLAGS_CHANGED), eq(squadronA), any(), eq(userId), any());
  }

  // --- Bereich / OL leadership membership -----------------------------------

  @Test
  void addBereichLeader_setsExactlyOneRoleFlag() {
    UUID bereichId = UUID.randomUUID();
    Bereich bereich = new Bereich();
    bereich.setId(bereichId);
    when(orgUnitRepository.findById(bereichId)).thenReturn(Optional.of(bereich));
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(membershipRepository.findAllByIdUserIdAndKind(userId, OrgUnitKind.SQUADRON))
        .thenReturn(List.of());
    when(membershipRepository.findById(any(OrgUnitMembershipId.class)))
        .thenReturn(Optional.empty());
    when(membershipRepository.saveAndFlush(any(OrgUnitMembership.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    OrgUnitMembership m =
        membershipService.addBereichLeader(bereichId, userId, BereichLeadershipRole.KOORDINATOR);

    assertEquals(MembershipRole.BEREICHSKOORDINATOR, m.getRole());
    verify(orgChartService).mirrorBereichRole(bereichId, userId, BereichLeadershipRole.KOORDINATOR);
    verify(auditService)
        .record(eq(AuditEventType.ROLE_GRANTED), eq(bereichId), any(), eq(userId), any());
  }

  @Test
  void addBereichLeader_existingRank_recordsRoleChanged() {
    UUID bereichId = UUID.randomUUID();
    Bereich bereich = new Bereich();
    bereich.setId(bereichId);
    OrgUnitMembership existing = new OrgUnitMembership();
    existing.setId(new OrgUnitMembershipId(userId, bereichId));
    existing.setKind(OrgUnitKind.BEREICH);
    existing.setRole(MembershipRole.BEREICHSLEITER);
    when(orgUnitRepository.findById(bereichId)).thenReturn(Optional.of(bereich));
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(membershipRepository.findAllByIdUserIdAndKind(userId, OrgUnitKind.SQUADRON))
        .thenReturn(List.of());
    when(membershipRepository.findById(any(OrgUnitMembershipId.class)))
        .thenReturn(Optional.of(existing));
    when(membershipRepository.saveAndFlush(any(OrgUnitMembership.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    membershipService.addBereichLeader(bereichId, userId, BereichLeadershipRole.KOORDINATOR);

    // An existing leadership rank changed (BEREICHSLEITER -> BEREICHSKOORDINATOR) records CHANGED.
    verify(auditService)
        .record(eq(AuditEventType.ROLE_CHANGED), eq(bereichId), any(), eq(userId), any());
  }

  @Test
  void addBereichLeader_userHoldsStaffel_throwsBadRequest() {
    UUID bereichId = UUID.randomUUID();
    Bereich bereich = new Bereich();
    bereich.setId(bereichId);
    when(orgUnitRepository.findById(bereichId)).thenReturn(Optional.of(bereich));
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(membershipRepository.findAllByIdUserIdAndKind(userId, OrgUnitKind.SQUADRON))
        .thenReturn(List.of(new OrgUnitMembership()));

    assertThrows(
        BadRequestException.class,
        () -> membershipService.addBereichLeader(bereichId, userId, BereichLeadershipRole.LEITER));
    verify(membershipRepository, never()).saveAndFlush(any());
    // A rejected assignment must not write an audit event.
    verify(auditService, never()).record(any(), any(), any(), any(), any());
  }

  @Test
  void addBereichLeader_notABereich_throwsBadRequest() {
    UUID notBereichId = UUID.randomUUID();
    Squadron squadron = new Squadron();
    squadron.setId(notBereichId);
    when(orgUnitRepository.findById(notBereichId)).thenReturn(Optional.of(squadron));

    assertThrows(
        BadRequestException.class,
        () ->
            membershipService.addBereichLeader(notBereichId, userId, BereichLeadershipRole.LEITER));
    verify(membershipRepository, never()).saveAndFlush(any());
  }

  @Test
  void addOlMember_setsOlFlag() {
    UUID olId = UUID.randomUUID();
    Organisationsleitung ol = new Organisationsleitung();
    ol.setId(olId);
    when(orgUnitRepository.findById(olId)).thenReturn(Optional.of(ol));
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(membershipRepository.findAllByIdUserIdAndKind(userId, OrgUnitKind.SQUADRON))
        .thenReturn(List.of());
    when(membershipRepository.existsByIdUserIdAndIdOrgUnitId(userId, olId)).thenReturn(false);
    when(membershipRepository.saveAndFlush(any(OrgUnitMembership.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    OrgUnitMembership m = membershipService.addOlMember(olId, userId);

    assertEquals(MembershipRole.OL_MEMBER, m.getRole());
    verify(orgChartService).mirrorOlMember(olId, userId);
    verify(auditService)
        .record(eq(AuditEventType.ROLE_GRANTED), eq(olId), any(), eq(userId), any());
  }

  @Test
  void addOlMember_duplicate_throws() {
    UUID olId = UUID.randomUUID();
    Organisationsleitung ol = new Organisationsleitung();
    ol.setId(olId);
    when(orgUnitRepository.findById(olId)).thenReturn(Optional.of(ol));
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(membershipRepository.findAllByIdUserIdAndKind(userId, OrgUnitKind.SQUADRON))
        .thenReturn(List.of());
    when(membershipRepository.existsByIdUserIdAndIdOrgUnitId(userId, olId)).thenReturn(true);

    assertThrows(DuplicateEntityException.class, () -> membershipService.addOlMember(olId, userId));
    verify(membershipRepository, never()).saveAndFlush(any());
  }

  @Test
  void setGrandAdmiral_existingMember_designatesAndAudits() {
    UUID olId = UUID.randomUUID();
    Organisationsleitung ol = new Organisationsleitung();
    ol.setId(olId);
    when(orgUnitRepository.findById(olId)).thenReturn(Optional.of(ol));
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(membershipRepository.existsByIdUserIdAndIdOrgUnitId(userId, olId)).thenReturn(true);

    membershipService.setGrandAdmiral(olId, userId);

    assertEquals(userId, ol.getGrandAdmiralUserId());
    // Already an OL member (REQ-ORG-021 keeps the OL_MEMBER rank) — no auto-add membership write.
    verify(membershipRepository, never()).saveAndFlush(any());
    verify(auditService)
        .record(eq(AuditEventType.ROLE_CHANGED), eq(olId), any(), eq(userId), any());
  }

  @Test
  void removeGrandAdmiral_clearsDesignationAndAudits() {
    UUID olId = UUID.randomUUID();
    Organisationsleitung ol = new Organisationsleitung();
    ol.setId(olId);
    ol.setGrandAdmiralUserId(userId);
    when(orgUnitRepository.findById(olId)).thenReturn(Optional.of(ol));

    membershipService.removeGrandAdmiral(olId);

    assertNull(ol.getGrandAdmiralUserId());
    verify(auditService)
        .record(eq(AuditEventType.ROLE_CHANGED), eq(olId), any(), eq(userId), any());
  }

  @Test
  void setGrandAdmiralFreeText_setsTrimmedNameAndSupersedesAccount() {
    UUID olId = UUID.randomUUID();
    Organisationsleitung ol = new Organisationsleitung();
    ol.setId(olId);
    ol.setGrandAdmiralUserId(userId); // was an account Grand Admiral
    when(orgUnitRepository.findById(olId)).thenReturn(Optional.of(ol));

    membershipService.setGrandAdmiralFreeText(olId, "  Admiral ohne Account  ");

    assertEquals("Admiral ohne Account", ol.getGrandAdmiralDisplayName());
    assertNull(ol.getGrandAdmiralUserId());
    // Free-text is a descriptive chart entry: no membership write (REQ-ORG-021).
    verify(membershipRepository, never()).saveAndFlush(any());
  }

  // --- assign/remove squadron rank (epic #800 Phase 3) ----------------------

  /** A Staffel membership row for {@link #userId} on the given squadron with the given rank. */
  private OrgUnitMembership squadronMember(UUID squadronId, MembershipRole role) {
    OrgUnitMembership m = new OrgUnitMembership();
    m.setId(new OrgUnitMembershipId(userId, squadronId));
    m.setKind(OrgUnitKind.SQUADRON);
    m.setRole(role);
    m.setVersion(0L);
    return m;
  }

  @Test
  void assignSquadronRank_staffelleiter_grantsAndAudits() {
    UUID squadronId = UUID.randomUUID();
    OrgUnitMembership m = squadronMember(squadronId, MembershipRole.MEMBER);
    when(membershipRepository.findById(any(OrgUnitMembershipId.class))).thenReturn(Optional.of(m));
    when(membershipRepository.findAllByIdOrgUnitId(squadronId)).thenReturn(List.of(m));
    when(membershipRepository.saveAndFlush(any(OrgUnitMembership.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    OrgUnitMembership saved =
        membershipService.assignSquadronRank(
            squadronId, userId, MembershipRole.STAFFELLEITER, null, 0L);

    assertEquals(MembershipRole.STAFFELLEITER, saved.getRole());
    verify(orgChartService)
        .mirrorSquadronRank(eq(squadronId), eq(userId), eq(MembershipRole.STAFFELLEITER), isNull());
    verify(auditService)
        .record(eq(AuditEventType.ROLE_GRANTED), eq(squadronId), any(), eq(userId), any());
  }

  @Test
  void assignSquadronRank_nonSquadronRank_throwsBadRequest() {
    UUID squadronId = UUID.randomUUID();
    assertThrows(
        BadRequestException.class,
        () ->
            membershipService.assignSquadronRank(
                squadronId, userId, MembershipRole.BEREICHSLEITER, null, 0L));
    verify(membershipRepository, never()).saveAndFlush(any());
  }

  @Test
  void assignSquadronRank_notMember_throwsNotFound() {
    UUID squadronId = UUID.randomUUID();
    when(membershipRepository.findById(any(OrgUnitMembershipId.class)))
        .thenReturn(Optional.empty());
    assertThrows(
        NotFoundException.class,
        () ->
            membershipService.assignSquadronRank(
                squadronId, userId, MembershipRole.STAFFELLEITER, null, 0L));
  }

  @Test
  void assignSquadronRank_kommandoleiterWithoutGroup_throwsBadRequest() {
    UUID squadronId = UUID.randomUUID();
    OrgUnitMembership m = squadronMember(squadronId, MembershipRole.MEMBER);
    when(membershipRepository.findById(any(OrgUnitMembershipId.class))).thenReturn(Optional.of(m));

    assertThrows(
        BadRequestException.class,
        () ->
            membershipService.assignSquadronRank(
                squadronId, userId, MembershipRole.KOMMANDOLEITER, null, 0L));
    verify(membershipRepository, never()).saveAndFlush(any());
  }

  @Test
  void assignSquadronRank_kommandoleiterWithGroup_grants() {
    UUID squadronId = UUID.randomUUID();
    UUID groupId = UUID.randomUUID();
    Squadron squadron = new Squadron();
    squadron.setId(squadronId);
    KommandoGroup group =
        KommandoGroup.builder().squadron(squadron).name("Alpha").sortIndex(0).build();
    group.setId(groupId);
    OrgUnitMembership m = squadronMember(squadronId, MembershipRole.MEMBER);
    when(membershipRepository.findById(any(OrgUnitMembershipId.class))).thenReturn(Optional.of(m));
    when(kommandoGroupRepository.findById(groupId)).thenReturn(Optional.of(group));
    when(membershipRepository.findAllByIdOrgUnitId(squadronId)).thenReturn(List.of(m));
    when(membershipRepository.saveAndFlush(any(OrgUnitMembership.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    OrgUnitMembership saved =
        membershipService.assignSquadronRank(
            squadronId, userId, MembershipRole.KOMMANDOLEITER, groupId, 0L);

    assertEquals(MembershipRole.KOMMANDOLEITER, saved.getRole());
    assertSame(group, saved.getKommandoGroup());
    verify(orgChartService)
        .mirrorSquadronRank(squadronId, userId, MembershipRole.KOMMANDOLEITER, group);
    verify(auditService)
        .record(eq(AuditEventType.ROLE_GRANTED), eq(squadronId), any(), eq(userId), any());
  }

  @Test
  void assignSquadronRank_secondStaffelleiter_throwsBadRequest() {
    UUID squadronId = UUID.randomUUID();
    OrgUnitMembership target = squadronMember(squadronId, MembershipRole.MEMBER);
    OrgUnitMembership existingLead = new OrgUnitMembership();
    existingLead.setId(new OrgUnitMembershipId(UUID.randomUUID(), squadronId));
    existingLead.setKind(OrgUnitKind.SQUADRON);
    existingLead.setRole(MembershipRole.STAFFELLEITER);
    when(membershipRepository.findById(any(OrgUnitMembershipId.class)))
        .thenReturn(Optional.of(target));
    when(membershipRepository.findAllByIdOrgUnitId(squadronId))
        .thenReturn(List.of(target, existingLead));

    assertThrows(
        BadRequestException.class,
        () ->
            membershipService.assignSquadronRank(
                squadronId, userId, MembershipRole.STAFFELLEITER, null, 0L));
    verify(membershipRepository, never()).saveAndFlush(any());
  }

  @Test
  void removeSquadronRank_clearsAndAudits() {
    UUID squadronId = UUID.randomUUID();
    OrgUnitMembership m = squadronMember(squadronId, MembershipRole.STAFFELLEITER);
    when(membershipRepository.findById(any(OrgUnitMembershipId.class))).thenReturn(Optional.of(m));
    when(membershipRepository.saveAndFlush(any(OrgUnitMembership.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    OrgUnitMembership saved = membershipService.removeSquadronRank(squadronId, userId, 0L);

    assertEquals(MembershipRole.MEMBER, saved.getRole());
    verify(orgChartService).mirrorRemoveSquadronRank(squadronId, userId);
    verify(auditService)
        .record(eq(AuditEventType.ROLE_REVOKED), eq(squadronId), any(), eq(userId), any());
  }

  @Test
  void removeSquadronRank_noRank_throwsBadRequest() {
    UUID squadronId = UUID.randomUUID();
    OrgUnitMembership m = squadronMember(squadronId, MembershipRole.MEMBER);
    when(membershipRepository.findById(any(OrgUnitMembershipId.class))).thenReturn(Optional.of(m));

    assertThrows(
        BadRequestException.class,
        () -> membershipService.removeSquadronRank(squadronId, userId, 0L));
    verify(membershipRepository, never()).saveAndFlush(any());
  }

  // --- …Dto projections (L4, #923, ADR-0067) ---------------------------------
  // These run the REAL MapStruct mapper (see the @Spy field) so they pin the wire shape the
  // controllers ship: userDisplayName from user.effectiveName, isLead derived from the unified
  // rank, and — for the write wrappers — the @Version the flush bumped (REQ-FE-003).

  @Test
  void addMemberDto_mapsThePersistedRow() {
    when(specialCommandService.getSpecialCommandById(scId)).thenReturn(sc);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(membershipRepository.existsByIdUserIdAndIdOrgUnitId(userId, scId)).thenReturn(false);
    when(membershipRepository.save(any(OrgUnitMembership.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    OrgUnitMembershipDto dto = membershipService.addMemberDto(scId, userId);

    assertEquals(userId, dto.userId());
    assertEquals(scId, dto.orgUnitId());
    assertEquals("Alice", dto.userDisplayName());
    assertEquals(Boolean.FALSE, dto.isLogistician());
    assertEquals(Boolean.FALSE, dto.isMissionManager());
    assertEquals(Boolean.FALSE, dto.isLead());
  }

  @Test
  void patchFlagsDto_carriesTheFlushedVersion() {
    OrgUnitMembership m = new OrgUnitMembership();
    m.setId(id);
    m.setKind(OrgUnitKind.SPECIAL_COMMAND);
    m.setUser(user);
    m.setVersion(3L);
    MembershipFlagsPatchRequest request = new MembershipFlagsPatchRequest(true, true, 3L);
    when(specialCommandService.getSpecialCommandById(scId)).thenReturn(sc);
    when(membershipRepository.findById(id)).thenReturn(Optional.of(m));
    // Simulate the flush bumping the row's @Version — the DTO must carry the bumped value, not
    // the stale pre-flush one, or the client's next echo 409s (REQ-FE-003).
    when(membershipRepository.saveAndFlush(m))
        .thenAnswer(
            inv -> {
              m.setVersion(4L);
              return m;
            });

    OrgUnitMembershipDto dto = membershipService.patchFlagsDto(scId, userId, request);

    assertEquals(Boolean.TRUE, dto.isLogistician());
    assertEquals(Boolean.TRUE, dto.isMissionManager());
    assertEquals(4L, dto.version().longValue());
  }

  @Test
  void patchSquadronMemberFlagsDto_carriesTheFlushedVersion() {
    UUID squadronId = UUID.randomUUID();
    Squadron squadron = new Squadron();
    squadron.setId(squadronId);
    squadron.setName("Alpha");
    squadron.setShorthand("ALF");
    OrgUnitMembership m = new OrgUnitMembership();
    m.setId(new OrgUnitMembershipId(userId, squadronId));
    m.setKind(OrgUnitKind.SQUADRON);
    m.setUser(user);
    m.setVersion(1L);
    MembershipFlagsPatchRequest request = new MembershipFlagsPatchRequest(true, null, 1L);
    when(squadronRepository.findById(squadronId)).thenReturn(Optional.of(squadron));
    when(membershipRepository.findById(new OrgUnitMembershipId(userId, squadronId)))
        .thenReturn(Optional.of(m));
    when(membershipRepository.saveAndFlush(m))
        .thenAnswer(
            inv -> {
              m.setVersion(2L);
              return m;
            });

    OrgUnitMembershipDto dto =
        membershipService.patchSquadronMemberFlagsDto(squadronId, userId, request);

    assertEquals(Boolean.TRUE, dto.isLogistician());
    assertEquals(2L, dto.version().longValue());
  }

  @Test
  void toggleLeadDto_mapsTheDerivedLeadFlagAndFlushedVersion() {
    OrgUnitMembership m = new OrgUnitMembership();
    m.setId(id);
    m.setKind(OrgUnitKind.SPECIAL_COMMAND);
    m.setUser(user);
    m.setVersion(0L);
    MembershipLeadToggleRequest request = new MembershipLeadToggleRequest(true, 0L);
    when(specialCommandService.getSpecialCommandById(scId)).thenReturn(sc);
    when(membershipRepository.findById(id)).thenReturn(Optional.of(m));
    when(membershipRepository.saveAndFlush(m))
        .thenAnswer(
            inv -> {
              m.setVersion(1L);
              return m;
            });

    OrgUnitMembershipDto dto = membershipService.toggleLeadDto(scId, userId, request);

    assertEquals(Boolean.TRUE, dto.isLead());
    assertEquals(1L, dto.version().longValue());
  }

  @Test
  void assignSquadronRankDto_mapsThePersistedRank() {
    UUID squadronId = UUID.randomUUID();
    OrgUnitMembership m = squadronMember(squadronId, MembershipRole.MEMBER);
    m.setUser(user);
    when(membershipRepository.findById(any(OrgUnitMembershipId.class))).thenReturn(Optional.of(m));
    when(membershipRepository.findAllByIdOrgUnitId(squadronId)).thenReturn(List.of(m));
    when(membershipRepository.saveAndFlush(any(OrgUnitMembership.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    OrgUnitMembershipDto dto =
        membershipService.assignSquadronRankDto(
            squadronId, userId, MembershipRole.STAFFELLEITER, null, 0L);

    assertEquals(userId, dto.userId());
    assertEquals(squadronId, dto.orgUnitId());
    assertEquals("Alice", dto.userDisplayName());
    assertEquals(Boolean.FALSE, dto.isLead(), "a squadron rank is not the SK lead");
  }

  @Test
  void removeSquadronRankDto_mapsTheClearedRank() {
    UUID squadronId = UUID.randomUUID();
    OrgUnitMembership m = squadronMember(squadronId, MembershipRole.STAFFELLEITER);
    m.setUser(user);
    when(membershipRepository.findById(any(OrgUnitMembershipId.class))).thenReturn(Optional.of(m));
    when(membershipRepository.saveAndFlush(any(OrgUnitMembership.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    OrgUnitMembershipDto dto = membershipService.removeSquadronRankDto(squadronId, userId, 0L);

    assertEquals(userId, dto.userId());
    assertEquals("Alice", dto.userDisplayName());
    assertEquals(Boolean.FALSE, dto.isLead());
  }
}
