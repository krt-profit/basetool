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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.identity.api.MembershipOperations;
import de.greluc.krt.profit.basetool.backend.identity.api.SquadronVisibility;
import de.greluc.krt.profit.basetool.backend.identity.api.StaffelAssignment;
import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.MembershipDeltaRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.MembershipDeltaRequest.SpecialCommandChange;
import de.greluc.krt.profit.basetool.backend.model.dto.MembershipDeltaRequest.SpecialCommandChange.Action;
import de.greluc.krt.profit.basetool.backend.model.dto.MembershipDeltaRequest.StaffelChange;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.refinery.internal.RefineryOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
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
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests for {@link UserService#applyMembershipDelta}, the single-request membership-delta
 * orchestrator (REQ-ORG-017):
 *
 * <ul>
 *   <li>A non-null {@code staffeln} list, even an empty one, is reconciled via {@link
 *       MembershipOperations#reconcileStaffelMemberships}; {@code null} leaves Staffeln untouched.
 *   <li>SK ADD calls {@link MembershipOperations#addMember} and applies initial flags by
 *       dirty-checking.
 *   <li>SK REMOVE calls {@link MembershipOperations#removeMember}.
 *   <li>SK PATCH delegates a versioned flag patch to {@link MembershipOperations#patchFlags}.
 *   <li>An unknown user yields 404 before any change.
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
  @Mock private SquadronVisibility squadronVisibility;
  @Mock private MembershipOperations membershipOperations;

  @InjectMocks private UserService userService;

  @Test
  void unknownUser_throwsNoSuchElement_andNothingFires() {
    UUID userId = UUID.randomUUID();
    when(userRepository.findById(userId)).thenReturn(Optional.empty());

    assertThrows(
        NoSuchElementException.class,
        () -> userService.applyMembershipDelta(userId, new MembershipDeltaRequest(null, null)));
    verify(membershipOperations, never()).reconcileStaffelMemberships(any(), any());
    verify(membershipOperations, never()).addMember(any(), any(), any(), any());
    verify(membershipOperations, never()).removeMember(any(), any());
    verify(membershipOperations, never()).patchFlags(any(), any(), any(), any(), any());
  }

  @Test
  void emptyDelta_flushesWithoutMutating() {
    UUID userId = UUID.randomUUID();
    User user = newUser(userId);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));

    userService.applyMembershipDelta(userId, new MembershipDeltaRequest(null, null));

    verify(membershipOperations).flushMembershipChanges(userId);
    verify(membershipOperations, never()).reconcileStaffelMemberships(any(), any());
    verify(membershipOperations, never()).addMember(any(), any(), any(), any());
    verify(membershipOperations, never()).removeMember(any(), any());
    verify(membershipOperations, never()).patchFlags(any(), any(), any(), any(), any());
  }

  @Test
  void staffelnDelta_forwardsDesiredSetToReconcile() {
    UUID userId = UUID.randomUUID();
    UUID squadronA = UUID.randomUUID();
    UUID squadronB = UUID.randomUUID();
    User user = newUser(userId);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));

    List<StaffelChange> staffeln =
        List.of(
            new StaffelChange(squadronA, true, false), new StaffelChange(squadronB, false, true));
    userService.applyMembershipDelta(userId, new MembershipDeltaRequest(staffeln, null));

    verify(membershipOperations)
        .reconcileStaffelMemberships(
            eq(user),
            eq(
                List.of(
                    new StaffelAssignment(squadronA, true, false),
                    new StaffelAssignment(squadronB, false, true))));
  }

  @Test
  void emptyStaffelnList_stillReconciles_removingAllStaffeln() {
    UUID userId = UUID.randomUUID();
    User user = newUser(userId);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));

    userService.applyMembershipDelta(userId, new MembershipDeltaRequest(List.of(), null));

    verify(membershipOperations).reconcileStaffelMemberships(eq(user), eq(List.of()));
  }

  @Test
  void skAdd_callsAddMember_withTheInitialFlags() {
    UUID userId = UUID.randomUUID();
    UUID skId = UUID.randomUUID();
    User user = newUser(userId);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));

    MembershipDeltaRequest delta =
        new MembershipDeltaRequest(
            null, List.of(new SpecialCommandChange(skId, Action.ADD, true, true, null)));
    userService.applyMembershipDelta(userId, delta);

    verify(membershipOperations).addMember(skId, userId, true, true);
  }

  @Test
  void skAdd_withoutFlags_passesNoFlags() {
    UUID userId = UUID.randomUUID();
    UUID skId = UUID.randomUUID();
    User user = newUser(userId);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));

    MembershipDeltaRequest delta =
        new MembershipDeltaRequest(
            null, List.of(new SpecialCommandChange(skId, Action.ADD, null, null, null)));
    userService.applyMembershipDelta(userId, delta);

    verify(membershipOperations).addMember(skId, userId, null, null);
  }

  @Test
  void skRemove_callsRemoveMember() {
    UUID userId = UUID.randomUUID();
    UUID skId = UUID.randomUUID();
    User user = newUser(userId);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));

    MembershipDeltaRequest delta =
        new MembershipDeltaRequest(
            null, List.of(new SpecialCommandChange(skId, Action.REMOVE, null, null, null)));
    userService.applyMembershipDelta(userId, delta);

    verify(membershipOperations).removeMember(skId, userId);
    verify(membershipOperations, never()).addMember(any(), any(), any(), any());
    verify(membershipOperations, never()).patchFlags(any(), any(), any(), any(), any());
  }

  @Test
  void skPatch_callsPatchFlags_withVersionFromChangeRecord() {
    UUID userId = UUID.randomUUID();
    UUID skId = UUID.randomUUID();
    User user = newUser(userId);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));

    MembershipDeltaRequest delta =
        new MembershipDeltaRequest(
            null, List.of(new SpecialCommandChange(skId, Action.PATCH, true, false, 4L)));
    userService.applyMembershipDelta(userId, delta);

    verify(membershipOperations).patchFlags(eq(skId), eq(userId), eq(true), eq(false), eq(4L));
  }

  @Test
  void theFlushIsTheLastCallOfTheDelta_afterEveryMutation() {
    UUID userId = UUID.randomUUID();
    UUID skA = UUID.randomUUID();
    UUID skB = UUID.randomUUID();
    User user = newUser(userId);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));

    MembershipDeltaRequest delta =
        new MembershipDeltaRequest(
            List.of(),
            List.of(
                new SpecialCommandChange(skA, Action.ADD, true, null, null),
                new SpecialCommandChange(skB, Action.PATCH, null, true, 2L),
                new SpecialCommandChange(skA, Action.REMOVE, null, null, null)));
    userService.applyMembershipDelta(userId, delta);

    InOrder order = inOrder(membershipOperations);
    order.verify(membershipOperations).reconcileStaffelMemberships(eq(user), eq(List.of()));
    order.verify(membershipOperations).addMember(skA, userId, true, null);
    order.verify(membershipOperations).patchFlags(skB, userId, null, true, 2L);
    order.verify(membershipOperations).removeMember(skA, userId);
    order.verify(membershipOperations).flushMembershipChanges(userId);
    order.verifyNoMoreInteractions();
  }

  @Test
  void aFailingMutationSkipsTheFlush() {
    UUID userId = UUID.randomUUID();
    UUID skId = UUID.randomUUID();
    User user = newUser(userId);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    IllegalStateException refusal = new IllegalStateException("refused");
    doThrow(refusal).when(membershipOperations).removeMember(skId, userId);

    MembershipDeltaRequest delta =
        new MembershipDeltaRequest(
            null, List.of(new SpecialCommandChange(skId, Action.REMOVE, null, null, null)));

    assertThrows(
        IllegalStateException.class, () -> userService.applyMembershipDelta(userId, delta));
    verify(membershipOperations, never()).flushMembershipChanges(any());
  }

  private User newUser(UUID id) {
    User user = new User();
    user.setId(id);
    user.setVersion(0L);
    return user;
  }
}
