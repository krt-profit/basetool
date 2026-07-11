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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.*;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class UserServiceDeleteTest {

  @Mock private UserRepository userRepository;
  @Mock private InventoryItemRepository inventoryItemRepository;
  @Mock private ShipRepository shipRepository;
  @Mock private RefineryOrderRepository refineryOrderRepository;
  @Mock private MissionRepository missionRepository;
  @Mock private MissionOwnershipRepository missionOwnershipRepository;
  @Mock private JobOrderRepository jobOrderRepository;
  @Mock private MissionParticipantRepository missionParticipantRepository;
  @Mock private MaterialClaimRepository materialClaimRepository;
  @Mock private UserApprovalEventRepository userApprovalEventRepository;
  @Mock private RoleRepository roleRepository;
  // Required so the AuthHelperService constructor parameter of UserService is
  // satisfied. shouldThrowExceptionIfNoAdminFound exercises the deleteUser
  // fallback path through getCurrentUser(), which dereferences authHelperService —
  // a null mock would surface as a NullPointerException instead of the expected
  // IllegalStateException. Other tests in this class never reach getCurrentUser()
  // so they do not need any stubbing on the mock.
  @Mock private AuthHelperService authHelperService;
  @Mock private OrgUnitMembershipService orgUnitMembershipService;

  @Mock private AuditService auditService;

  // The responsible-holder audit seam is injected as an ObjectProvider (ADR-0070); deleteUser
  // resolves it to audit a responsible-holder change when a deleted user was a leader. Stubbed
  // lenient so the early-throw tests (which never reach the delete) do not trip strict-stubs; the
  // seam mock no-ops.
  @Mock
  private ObjectProvider<OrgUnitBankResponsibilityService> orgUnitBankResponsibilityServiceProvider;

  @Mock private OrgUnitBankResponsibilityService orgUnitBankResponsibilityService;

  @InjectMocks private UserService userService;

  private User user;
  private User admin;
  private UUID userId;
  private UUID adminId;

  @BeforeEach
  void setUp() {
    userId = UUID.randomUUID();
    adminId = UUID.randomUUID();

    user = new User();
    user.setId(userId);
    user.setInKeycloak(false);

    admin = new User();
    admin.setId(adminId);
    admin.setInKeycloak(true);

    lenient()
        .when(orgUnitBankResponsibilityServiceProvider.getObject())
        .thenReturn(orgUnitBankResponsibilityService);
    lenient()
        .when(orgUnitBankResponsibilityService.snapshotResponsibleHoldersForUser(any()))
        .thenReturn(Map.of());
  }

  @Test
  void shouldDeleteUserAndReassignReferences() {
    // Given
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(userRepository.findAllAdmins()).thenReturn(List.of(admin));

    // When
    userService.deleteUser(userId);

    // Then
    verify(inventoryItemRepository).updateOwner(user, admin);
    verify(shipRepository).updateOwner(user, admin);
    verify(refineryOrderRepository).updateOwner(user, admin);
    verify(missionRepository).updateOwner(user, admin);
    verify(missionOwnershipRepository).updateOwner(user, admin);
    verify(missionRepository).removeManager(userId);
    verify(jobOrderRepository).removeAssignee(userId);
    verify(missionParticipantRepository).unlinkUser(userId);
    verify(materialClaimRepository).unlinkClaimedByUser(userId);
    verify(userApprovalEventRepository).deleteByUserId(userId);
    verify(userApprovalEventRepository).clearDecidedBy(userId);
    verify(userRepository).clearApprovedBy(userId);
    verify(userRepository).delete(user);
  }

  @Test
  void deleteUser_recordsReassignmentEventsWithRowCounts_whenRowsMoved() {
    // Given the deleted user owns warehouse rows and refinery orders.
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(userRepository.findAllAdmins()).thenReturn(List.of(admin));
    when(inventoryItemRepository.updateOwner(user, admin)).thenReturn(3);
    when(refineryOrderRepository.updateOwner(user, admin)).thenReturn(2);

    // When
    userService.deleteUser(userId);

    // Then both summary events fire, carrying the affected-row count.
    verify(auditService)
        .record(
            eq(AuditEventType.INVENTORY_OWNER_REASSIGNED),
            isNull(),
            isNull(),
            eq(userId),
            argThat(d -> d != null && d.toString().contains("rows=3")));
    verify(auditService)
        .record(
            eq(AuditEventType.REFINERY_ORDERS_REASSIGNED),
            isNull(),
            isNull(),
            eq(userId),
            argThat(d -> d != null && d.toString().contains("rows=2")));
  }

  @Test
  void deleteUser_skipsReassignmentEvents_whenNoRowsMoved() {
    // Given the deleted user owns nothing (the updateOwner mocks default to 0 rows).
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(userRepository.findAllAdmins()).thenReturn(List.of(admin));

    // When
    userService.deleteUser(userId);

    // Then no inventory/refinery reassignment noise is recorded.
    verify(auditService, never())
        .record(eq(AuditEventType.INVENTORY_OWNER_REASSIGNED), any(), any(), any(), any());
    verify(auditService, never())
        .record(eq(AuditEventType.REFINERY_ORDERS_REASSIGNED), any(), any(), any(), any());
  }

  @Test
  void shouldReassignMissionOwnershipCompanionBeforeDeletingUser() {
    // Given a user that owns missions: the mission_ownership companion (its owner_id FK has no
    // ON DELETE clause) must be reassigned in lock-step with mission.owner and strictly before the
    // app_user row is removed, otherwise the dangling owner_id FK-fails (23503) on delete.
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(userRepository.findAllAdmins()).thenReturn(List.of(admin));

    // When
    userService.deleteUser(userId);

    // Then the companion is reassigned alongside the mission owner, the audit-only material-claim
    // stamp is cleared, and all of that happens strictly before the app_user row is removed.
    var inOrder =
        inOrder(
            missionRepository, missionOwnershipRepository, materialClaimRepository, userRepository);
    inOrder.verify(missionRepository).updateOwner(user, admin);
    inOrder.verify(missionOwnershipRepository).updateOwner(user, admin);
    inOrder.verify(materialClaimRepository).unlinkClaimedByUser(userId);
    inOrder.verify(userRepository).delete(user);
  }

  @Test
  void shouldClearDiscordApprovalAuditBeforeDeletingUser() {
    // Regression (epic #720 / V173): the approval-audit FKs carry no ON DELETE clause, so the audit
    // must be cleared before the app_user row is removed, or the delete 409s on
    // user_approval_event_user_id_fkey — the reported "approved Discord registration can no longer
    // be deleted" failure.
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(userRepository.findAllAdmins()).thenReturn(List.of(admin));

    userService.deleteUser(userId);

    InOrder inOrder = inOrder(userApprovalEventRepository, userRepository);
    inOrder.verify(userApprovalEventRepository).deleteByUserId(userId);
    inOrder.verify(userApprovalEventRepository).clearDecidedBy(userId);
    inOrder.verify(userRepository).clearApprovedBy(userId);
    inOrder.verify(userRepository).delete(user);
  }

  @Test
  void shouldNotDeleteUserStillInKeycloak() {
    // Given
    user.setInKeycloak(true);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));

    // When & Then
    assertThrows(IllegalStateException.class, () -> userService.deleteUser(userId));
    verify(userRepository, never()).delete(any());
  }

  @Test
  void shouldThrowExceptionIfNoAdminFound() {
    // Given
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(userRepository.findAllAdmins()).thenReturn(Collections.emptyList());

    // When & Then
    assertThrows(IllegalStateException.class, () -> userService.deleteUser(userId));
    verify(userRepository, never()).delete(any());
  }

  @Test
  void shouldThrowExceptionIfUserNotFound() {
    // Given
    when(userRepository.findById(userId)).thenReturn(Optional.empty());

    // When & Then
    assertThrows(NoSuchElementException.class, () -> userService.deleteUser(userId));
  }
}
