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

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.Role;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.*;
import de.greluc.krt.profit.basetool.backend.support.IngestGatewayProperties;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.ResourceAccessException;

/**
 * Mockito unit tests for {@link UserDeletionService#deleteUser}: the FK-safe ordering of
 * reassignment, unlinking and audit cleanup before the {@code app_user} delete, and the
 * fallback-admin resolution through {@link UserService#getCurrentUser()}.
 */
@ExtendWith(MockitoExtension.class)
class UserDeletionServiceTest {

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
  @Mock private AuditService auditService;
  @Mock private KeycloakService keycloakService;
  @Mock private PersonalInventoryItemRepository personalInventoryItemRepository;
  @Mock private PersonalBlueprintRepository personalBlueprintRepository;
  @Mock private NotificationRepository notificationRepository;
  @Mock private NotificationRuleRepository notificationRuleRepository;
  @Mock private MemberEvaluationRepository memberEvaluationRepository;

  @Mock private UserService userService;

  @Mock
  private ObjectProvider<OrgUnitBankResponsibilityService> orgUnitBankResponsibilityServiceProvider;

  @Mock private OrgUnitBankResponsibilityService orgUnitBankResponsibilityService;

  /** The gateway allowlist a test may extend; the properties record reads it by reference. */
  private final List<String> gatewayClientIds = new ArrayList<>();

  /**
   * A real instance with an empty allowlist, so no user is a gateway service account and the tests
   * exercise the member path (ADR-0129).
   */
  @Spy
  private final IngestGatewayProperties ingestGatewayProperties =
      new IngestGatewayProperties(gatewayClientIds);

  @InjectMocks private UserDeletionService userDeletionService;

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
  void shouldPurgeAccountDataAndReassignSharedAggregates() {
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(userRepository.findAllAdmins()).thenReturn(List.of(admin));

    userDeletionService.deleteUser(userId);

    verify(inventoryItemRepository).deleteByUserId(userId);
    verify(shipRepository).deleteByOwnerId(userId);
    verify(personalInventoryItemRepository).deleteByOwnerUserId(userId);
    verify(personalBlueprintRepository).deleteAllByOwnerUserId(userId);
    verify(notificationRepository).deleteAllForRecipient(userId);
    verify(notificationRuleRepository).deleteSelectorsByUserId(userId);
    verify(memberEvaluationRepository).deleteAllByUserId(userId);
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
  void deleteUser_recordsPurgeAndReassignmentEventsWithRowCounts_whenRowsAffected() {
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(userRepository.findAllAdmins()).thenReturn(List.of(admin));
    when(inventoryItemRepository.deleteByUserId(userId)).thenReturn(3);
    when(shipRepository.deleteByOwnerId(userId)).thenReturn(7);
    when(personalBlueprintRepository.deleteAllByOwnerUserId(userId)).thenReturn(8);
    when(refineryOrderRepository.updateOwner(user, admin)).thenReturn(2);

    userDeletionService.deleteUser(userId);

    verify(auditService)
        .record(
            eq(AuditEventType.INVENTORY_PURGED_ON_USER_DELETION),
            isNull(),
            isNull(),
            eq(userId),
            argThat(
                d ->
                    d != null
                        && d.toString().contains("inventoryRows=3")
                        && d.toString().contains("ships=7")));
    verify(auditService)
        .record(
            eq(AuditEventType.PERSONAL_DATA_PURGED_ON_USER_DELETION),
            isNull(),
            isNull(),
            eq(userId),
            argThat(d -> d != null && d.toString().contains("blueprints=8")));
    verify(auditService)
        .record(
            eq(AuditEventType.REFINERY_ORDERS_REASSIGNED),
            isNull(),
            isNull(),
            eq(userId),
            argThat(d -> d != null && d.toString().contains("rows=2")));
  }

  @Test
  void deleteUser_refusesWhenKeycloakStillHasTheAccount_evenThoughTheStoredFlagSaysOtherwise() {
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(keycloakService.userExists(userId)).thenReturn(true);

    assertThrows(BadRequestException.class, () -> userDeletionService.deleteUser(userId));

    verify(userRepository, never()).delete(any());
    verify(inventoryItemRepository, never()).deleteByUserId(any());
    verify(shipRepository, never()).deleteByOwnerId(any());
    verify(personalBlueprintRepository, never()).deleteAllByOwnerUserId(any());
  }

  /**
   * Verifies that a waived existence check lets the deletion proceed while the Keycloak user still
   * exists, as an orchestrator that removes it afterwards requires (REQ-SEC-026).
   */
  @Test
  void deleteUser_waived_proceedsEvenWhileTheKeycloakUserIsStillThere() {
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(userRepository.findAllAdmins()).thenReturn(List.of(admin));

    userDeletionService.deleteUser(
        userId, UserDeletionService.KeycloakPresenceCheck.WAIVED_CALLER_REMOVES_THE_KEYCLOAK_USER);

    verify(userRepository).delete(user);
    verify(keycloakService, never()).userExists(any());
  }

  /**
   * The waiver must stay a caller's explicit promise, never the default. The admin-facing form is
   * the one an operator reaches, and it keeps refusing an account Keycloak still knows.
   */
  @Test
  void deleteUser_defaultForm_stillEnforcesTheProbe() {
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(keycloakService.userExists(userId)).thenReturn(true);

    assertThrows(BadRequestException.class, () -> userDeletionService.deleteUser(userId));

    verify(userRepository, never()).delete(any());
  }

  /**
   * Waiving the Keycloak probe does not waive the cached flag. A caller that has not yet marked the
   * row absent is not in the middle of disposing of it, so the first guard still applies.
   */
  @Test
  void deleteUser_waived_stillRefusesWhileTheStoredFlagSaysInKeycloak() {
    user.setInKeycloak(true);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));

    assertThrows(
        BadRequestException.class,
        () ->
            userDeletionService.deleteUser(
                userId,
                UserDeletionService.KeycloakPresenceCheck.WAIVED_CALLER_REMOVES_THE_KEYCLOAK_USER));

    verify(userRepository, never()).delete(any());
  }

  @Test
  void deleteUser_removesTheStrayRowOfAConfiguredGatewayServiceAccount() {
    gatewayClientIds.add("basetool-ingest-gateway");
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(userRepository.findAllAdmins()).thenReturn(List.of(admin));
    when(keycloakService.userExists(userId)).thenReturn(true);
    when(keycloakService.usernameOf(userId))
        .thenReturn(Optional.of("service-account-basetool-ingest-gateway"));

    userDeletionService.deleteUser(userId);

    verify(userRepository).delete(user);
  }

  @Test
  void deleteUser_stillRefusesAMemberWhoseNameMerelyLooksLikeAServiceAccount() {
    gatewayClientIds.add("basetool-ingest-gateway");
    user.setUsername("service-account-basetool-ingest-gateway");
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(keycloakService.userExists(userId)).thenReturn(true);
    when(keycloakService.usernameOf(userId)).thenReturn(Optional.of("Redshift"));

    assertThrows(BadRequestException.class, () -> userDeletionService.deleteUser(userId));

    verify(userRepository, never()).delete(any());
  }

  @Test
  void deleteUser_refusesCleanlyWhenTheServiceAccountCheckItselfFails() {
    gatewayClientIds.add("basetool-ingest-gateway");
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(keycloakService.userExists(userId)).thenReturn(true);
    when(keycloakService.usernameOf(userId))
        .thenThrow(new ResourceAccessException("keycloak admin call refused"));

    assertThrows(BadRequestException.class, () -> userDeletionService.deleteUser(userId));

    verify(userRepository, never()).delete(any());
  }

  @Test
  void deleteUser_refusesWhenKeycloakCannotBeReached_ratherThanAssumingTheAccountIsGone() {
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(keycloakService.userExists(userId))
        .thenThrow(new ResourceAccessException("keycloak unreachable"));

    assertThrows(ResourceAccessException.class, () -> userDeletionService.deleteUser(userId));

    verify(userRepository, never()).delete(any());
    verify(inventoryItemRepository, never()).deleteByUserId(any());
    verify(personalInventoryItemRepository, never()).deleteByOwnerUserId(any());
  }

  @Test
  void deleteUser_alwaysRecordsTheUserDeletedMarker_evenWhenTheAccountOwnedNothing() {
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(userRepository.findAllAdmins()).thenReturn(List.of(admin));

    userDeletionService.deleteUser(userId);

    verify(auditService)
        .record(eq(AuditEventType.USER_DELETED), isNull(), isNull(), eq(userId), any());
    verify(auditService, never())
        .record(eq(AuditEventType.INVENTORY_PURGED_ON_USER_DELETION), any(), any(), any(), any());
    verify(auditService, never())
        .record(
            eq(AuditEventType.PERSONAL_DATA_PURGED_ON_USER_DELETION), any(), any(), any(), any());
    verify(auditService, never())
        .record(eq(AuditEventType.REFINERY_ORDERS_REASSIGNED), any(), any(), any(), any());
  }

  @Test
  void shouldReassignMissionOwnershipCompanionBeforeDeletingUser() {
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(userRepository.findAllAdmins()).thenReturn(List.of(admin));

    userDeletionService.deleteUser(userId);

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
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(userRepository.findAllAdmins()).thenReturn(List.of(admin));

    userDeletionService.deleteUser(userId);

    InOrder inOrder = inOrder(userApprovalEventRepository, userRepository);
    inOrder.verify(userApprovalEventRepository).deleteByUserId(userId);
    inOrder.verify(userApprovalEventRepository).clearDecidedBy(userId);
    inOrder.verify(userRepository).clearApprovedBy(userId);
    inOrder.verify(userRepository).delete(user);
  }

  @Test
  void shouldNotDeleteUserStillInKeycloak() {
    user.setInKeycloak(true);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));

    assertThrows(BadRequestException.class, () -> userDeletionService.deleteUser(userId));
    verify(userRepository, never()).delete(any());
  }

  @Test
  void shouldThrowExceptionIfNoAdminFound() {
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(userRepository.findAllAdmins()).thenReturn(Collections.emptyList());

    assertThrows(IllegalStateException.class, () -> userDeletionService.deleteUser(userId));
    verify(userRepository, never()).delete(any());
  }

  @Test
  void shouldThrowExceptionIfUserNotFound() {
    when(userRepository.findById(userId)).thenReturn(Optional.empty());

    assertThrows(NoSuchElementException.class, () -> userDeletionService.deleteUser(userId));
  }

  /**
   * deleteUser's fallback-admin path: when {@code findAllAdmins()} yields no <em>other</em> usable
   * admin, the reassignment target is resolved from {@link UserService#getCurrentUser()} — the
   * currently authenticated admin, provided it is a different user carrying the ADMIN role.
   */
  @Nested
  class DeleteUserFallbackTests {

    @Test
    void fallsBackToCurrentUser_whenCurrentUserIsAdmin_andFindAllAdminsHasNoOther() {
      User toDelete = newUser(userId);
      toDelete.setInKeycloak(false);
      toDelete.setRoles(new HashSet<>(Set.of(roleNamed("ADMIN"))));

      UUID currentAdminId = UUID.randomUUID();
      User currentAdmin = newUser(currentAdminId);
      currentAdmin.setRoles(new HashSet<>(Set.of(roleNamed("ADMIN"))));

      when(userRepository.findById(userId)).thenReturn(Optional.of(toDelete));
      when(userRepository.findAllAdmins()).thenReturn(List.of(toDelete));
      when(userService.getCurrentUser()).thenReturn(Optional.of(currentAdmin));

      userDeletionService.deleteUser(userId);

      verify(refineryOrderRepository).updateOwner(toDelete, currentAdmin);
      verify(userRepository).delete(toDelete);
    }

    @Test
    void throws_whenCurrentUserIsAlsoTheUserBeingDeleted() {
      User toDelete = newUser(userId);
      toDelete.setInKeycloak(false);
      toDelete.setRoles(new HashSet<>(Set.of(roleNamed("ADMIN"))));

      when(userRepository.findById(userId)).thenReturn(Optional.of(toDelete));
      when(userRepository.findAllAdmins()).thenReturn(List.of(toDelete));
      when(userService.getCurrentUser()).thenReturn(Optional.of(toDelete));

      assertThrows(IllegalStateException.class, () -> userDeletionService.deleteUser(userId));
      verify(userRepository, never()).delete(any());
    }

    @Test
    void throws_whenCurrentUserIsNotAdmin() {
      User toDelete = newUser(userId);
      toDelete.setInKeycloak(false);

      UUID currentId = UUID.randomUUID();
      User current = newUser(currentId);
      current.setRoles(new HashSet<>(Set.of(roleNamed("KRT_MEMBER"))));

      when(userRepository.findById(userId)).thenReturn(Optional.of(toDelete));
      when(userRepository.findAllAdmins()).thenReturn(List.of(toDelete));
      when(userService.getCurrentUser()).thenReturn(Optional.of(current));

      assertThrows(IllegalStateException.class, () -> userDeletionService.deleteUser(userId));
      verify(userRepository, never()).delete(any());
    }
  }

  private static User newUser(UUID id) {
    User u = new User();
    u.setId(id);
    u.setUsername("user-" + id);
    u.setRoles(new HashSet<>());
    return u;
  }

  private static Role roleNamed(String name) {
    Role r = new Role();
    r.setName(name);
    return r;
  }
}
