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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.MembershipDeltaRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.MembershipDeltaRequest.SpecialCommandChange;
import de.greluc.krt.profit.basetool.backend.model.dto.MembershipDeltaRequest.SpecialCommandChange.Action;
import de.greluc.krt.profit.basetool.backend.model.dto.MembershipDeltaRequest.StaffelChange;
import de.greluc.krt.profit.basetool.backend.model.dto.MembershipFlagsPatchRequest;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.RefineryOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.RoleRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests for {@link UserService#applyMembershipDelta} — the SPEZIALKOMMANDO_PLAN.md §7.4 single-POST
 * membership-delta orchestrator (multi-Staffel variant, REQ-ORG-017). Verifies:
 *
 * <ul>
 *   <li>A non-null {@code staffeln} list is forwarded verbatim to {@link
 *       OrgUnitMembershipService#reconcileStaffelMemberships} (which adds / removes / flag-patches
 *       the user's Staffel memberships against that desired set); a {@code null} list leaves the
 *       Staffel side untouched.
 *   <li>An empty {@code staffeln} list still reconciles (it removes every Staffel membership).
 *   <li>SK ADD calls {@link OrgUnitMembershipService#addMember} and adopts initial flags inline via
 *       dirty-checking on the returned row (no second explicit save).
 *   <li>SK REMOVE calls {@link OrgUnitMembershipService#removeMember}.
 *   <li>SK PATCH wraps the change in a {@link MembershipFlagsPatchRequest} that carries the per-row
 *       version and delegates to {@link OrgUnitMembershipService#patchFlags}.
 *   <li>Unknown user surfaces as 404 immediately, before any change is applied.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class UserServiceMembershipDeltaTest {

  @Mock private UserRepository userRepository;
  @Mock private RoleRepository roleRepository;
  @Mock private InventoryItemRepository inventoryItemRepository;
  @Mock private ShipRepository shipRepository;
  @Mock private RefineryOrderRepository refineryOrderRepository;
  @Mock private MissionRepository missionRepository;
  @Mock private JobOrderRepository jobOrderRepository;
  @Mock private MissionParticipantRepository missionParticipantRepository;
  @Mock private SquadronRepository squadronRepository;
  @Mock private AuthHelperService authHelperService;
  @Mock private OwnerScopeService ownerScopeService;
  @Mock private OrgUnitMembershipService orgUnitMembershipService;
  @Mock private OrgUnitMembershipQueryService orgUnitMembershipQueryService;

  @InjectMocks private UserService userService;

  @Test
  void unknownUser_throwsNoSuchElement_andNothingFires() {
    UUID userId = UUID.randomUUID();
    when(userRepository.findById(userId)).thenReturn(Optional.empty());

    assertThrows(
        NoSuchElementException.class,
        () -> userService.applyMembershipDelta(userId, new MembershipDeltaRequest(null, null)));
    verify(orgUnitMembershipService, never()).reconcileStaffelMemberships(any(), any());
    verify(orgUnitMembershipService, never()).addMember(any(), any());
    verify(orgUnitMembershipService, never()).removeMember(any(), any());
    verify(orgUnitMembershipService, never()).patchFlags(any(), any(), any());
  }

  @Test
  void emptyDelta_returnsCurrentStateWithoutMutating() {
    UUID userId = UUID.randomUUID();
    User user = newUser(userId);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(orgUnitMembershipQueryService.findAllMembershipsForUser(userId)).thenReturn(List.of());

    List<OrgUnitMembership> result =
        userService.applyMembershipDelta(userId, new MembershipDeltaRequest(null, null));

    assertEquals(0, result.size());
    verify(orgUnitMembershipService, never()).reconcileStaffelMemberships(any(), any());
    verify(orgUnitMembershipService, never()).addMember(any(), any());
    verify(orgUnitMembershipService, never()).removeMember(any(), any());
    verify(orgUnitMembershipService, never()).patchFlags(any(), any(), any());
  }

  @Test
  void staffelnDelta_forwardsDesiredSetToReconcile() {
    UUID userId = UUID.randomUUID();
    UUID squadronA = UUID.randomUUID();
    UUID squadronB = UUID.randomUUID();
    User user = newUser(userId);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(orgUnitMembershipQueryService.findAllMembershipsForUser(userId)).thenReturn(List.of());

    List<StaffelChange> staffeln =
        List.of(
            new StaffelChange(squadronA, true, false), new StaffelChange(squadronB, false, true));
    userService.applyMembershipDelta(userId, new MembershipDeltaRequest(staffeln, null));

    verify(orgUnitMembershipService).reconcileStaffelMemberships(eq(user), eq(staffeln));
  }

  @Test
  void emptyStaffelnList_stillReconciles_removingAllStaffeln() {
    UUID userId = UUID.randomUUID();
    User user = newUser(userId);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(orgUnitMembershipQueryService.findAllMembershipsForUser(userId)).thenReturn(List.of());

    userService.applyMembershipDelta(userId, new MembershipDeltaRequest(List.of(), null));

    verify(orgUnitMembershipService).reconcileStaffelMemberships(eq(user), eq(List.of()));
  }

  @Test
  void skAdd_callsAddMember_andAdoptsInitialFlagsInline() {
    UUID userId = UUID.randomUUID();
    UUID skId = UUID.randomUUID();
    User user = newUser(userId);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    OrgUnitMembership freshRow = new OrgUnitMembership();
    when(orgUnitMembershipService.addMember(skId, userId)).thenReturn(freshRow);
    when(orgUnitMembershipQueryService.findAllMembershipsForUser(userId))
        .thenReturn(List.of(freshRow));

    MembershipDeltaRequest delta =
        new MembershipDeltaRequest(
            null, List.of(new SpecialCommandChange(skId, Action.ADD, true, true, null)));
    userService.applyMembershipDelta(userId, delta);

    verify(orgUnitMembershipService).addMember(skId, userId);
    // Initial flags set inline on the managed entity (no second save call needed).
    assertEquals(true, freshRow.isLogistician());
    assertEquals(true, freshRow.isMissionManager());
  }

  @Test
  void skAdd_withoutFlags_doesNotMutateRow() {
    UUID userId = UUID.randomUUID();
    UUID skId = UUID.randomUUID();
    User user = newUser(userId);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    OrgUnitMembership freshRow = new OrgUnitMembership();
    when(orgUnitMembershipService.addMember(skId, userId)).thenReturn(freshRow);
    when(orgUnitMembershipQueryService.findAllMembershipsForUser(userId))
        .thenReturn(List.of(freshRow));

    MembershipDeltaRequest delta =
        new MembershipDeltaRequest(
            null, List.of(new SpecialCommandChange(skId, Action.ADD, null, null, null)));
    userService.applyMembershipDelta(userId, delta);

    verify(orgUnitMembershipService).addMember(skId, userId);
    assertEquals(false, freshRow.isLogistician());
    assertEquals(false, freshRow.isMissionManager());
  }

  @Test
  void skRemove_callsRemoveMember() {
    UUID userId = UUID.randomUUID();
    UUID skId = UUID.randomUUID();
    User user = newUser(userId);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(orgUnitMembershipQueryService.findAllMembershipsForUser(userId)).thenReturn(List.of());

    MembershipDeltaRequest delta =
        new MembershipDeltaRequest(
            null, List.of(new SpecialCommandChange(skId, Action.REMOVE, null, null, null)));
    userService.applyMembershipDelta(userId, delta);

    verify(orgUnitMembershipService).removeMember(skId, userId);
    verify(orgUnitMembershipService, never()).addMember(any(), any());
    verify(orgUnitMembershipService, never()).patchFlags(any(), any(), any());
  }

  @Test
  void skPatch_callsPatchFlags_withVersionFromChangeRecord() {
    UUID userId = UUID.randomUUID();
    UUID skId = UUID.randomUUID();
    User user = newUser(userId);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(orgUnitMembershipQueryService.findAllMembershipsForUser(userId)).thenReturn(List.of());

    MembershipDeltaRequest delta =
        new MembershipDeltaRequest(
            null, List.of(new SpecialCommandChange(skId, Action.PATCH, true, false, 4L)));
    userService.applyMembershipDelta(userId, delta);

    verify(orgUnitMembershipService)
        .patchFlags(eq(skId), eq(userId), eq(new MembershipFlagsPatchRequest(true, false, 4L)));
  }

  private User newUser(UUID id) {
    User user = new User();
    user.setId(id);
    user.setVersion(0L);
    return user;
  }
}
