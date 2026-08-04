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
import de.greluc.krt.profit.basetool.backend.model.Role;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.*;
import de.greluc.krt.profit.basetool.backend.support.IngestGatewayProperties;
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
 * Mockito unit tests for {@link UserDeletionService#deleteUser} — the FK-ordered hard-delete
 * cascade extracted out of {@code UserService} (audit Thema&nbsp;7, #1252). Covers the reassignment
 * / unlink / audit-cleanup ordering that keeps the {@code app_user} delete from tripping a
 * foreign-key violation, plus the fallback-admin resolution through {@link
 * UserService#getCurrentUser()}.
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

  // The identity seam. deleteUser borrows getCurrentUser() for the fallback-admin path; the
  // reassign/delete tests never trigger the fallback (findAllAdmins returns a usable admin) so they
  // leave this mock untouched.
  @Mock private UserService userService;

  // The bank seam is injected as an ObjectProvider (ADR-0070); deleteUser resolves it to audit a
  // responsible-holder change when a deleted user was a leader. Stubbed lenient so the early-throw
  // tests (which never reach the delete) do not trip strict-stubs; the seam mock no-ops.
  @Mock
  private ObjectProvider<OrgUnitBankResponsibilityService> orgUnitBankResponsibilityServiceProvider;

  @Mock private OrgUnitBankResponsibilityService orgUnitBankResponsibilityService;

  /**
   * A real instance, not a mock: its default is the empty allowlist, which is the state every
   * pre-existing case here needs — no row is a gateway service account, so the machine-identity
   * exemption never fires and each case exercises the member path it was written for (ADR-0129).
   */
  @Spy
  private final IngestGatewayProperties ingestGatewayProperties = new IngestGatewayProperties();

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
    // Given
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(userRepository.findAllAdmins()).thenReturn(List.of(admin));

    // Then: account-owned data is purged, keyed by id / Keycloak subject...
    userDeletionService.deleteUser(userId);

    verify(inventoryItemRepository).deleteByUserId(userId);
    verify(shipRepository).deleteByOwnerId(userId);
    verify(personalInventoryItemRepository).deleteByOwnerSub(userId.toString());
    verify(personalBlueprintRepository).deleteAllByOwnerSub(userId.toString());
    verify(notificationRepository).deleteAllForRecipient(userId);
    verify(notificationRuleRepository).deleteSelectorsByUserSub(userId);
    verify(memberEvaluationRepository).deleteAllByUserId(userId.toString());
    // ...while the shared/historical aggregates only change owner.
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
    // Given the deleted user owns warehouse rows, ships, personal data and refinery orders.
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(userRepository.findAllAdmins()).thenReturn(List.of(admin));
    when(inventoryItemRepository.deleteByUserId(userId)).thenReturn(3);
    when(shipRepository.deleteByOwnerId(userId)).thenReturn(7);
    when(personalBlueprintRepository.deleteAllByOwnerSub(userId.toString())).thenReturn(8);
    when(refineryOrderRepository.updateOwner(user, admin)).thenReturn(2);

    // When
    userDeletionService.deleteUser(userId);

    // Then each summary event fires, carrying the affected-row counts.
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
    // The stored in_keycloak flag is only a cached mirror; a swallowed sync error can leave it
    // stale at false. Since the deletion now purges rather than reassigns, acting on a stale flag
    // would destroy an ACTIVE member's Lager, hangar and personal data. Keycloak decides.
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(keycloakService.userExists(userId)).thenReturn(true);

    assertThrows(IllegalStateException.class, () -> userDeletionService.deleteUser(userId));

    verify(userRepository, never()).delete(any());
    verify(inventoryItemRepository, never()).deleteByUserId(any());
    verify(shipRepository, never()).deleteByOwnerId(any());
    verify(personalBlueprintRepository, never()).deleteAllByOwnerSub(any());
  }

  @Test
  void deleteUser_removesTheStrayRowOfAConfiguredGatewayServiceAccount() {
    // The one row for which the two Keycloak views disagree by construction: an unfiltered
    // GET /users omits service accounts, so the roster sync marks it gone, while userExists asks
    // by id and finds it. Production is in exactly this state - the gateway's first call ran the
    // registration flow on itself before the machine-identity carve-out existed (ADR-0129) - and
    // without this exemption the row can never be deleted through the admin UI.
    ingestGatewayProperties.setClientIds(List.of("basetool-ingest-gateway"));
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
    // The exemption is keyed on the name KEYCLOAK reports for that id, matched against the exact
    // name a configured client's service account must have. Measured against Keycloak 26.7:
    // `service-account-foo` can be created when no client `foo` exists (201), so the prefix alone
    // proves nothing; the name of an EXISTING service account is refused (409), so the exact name
    // of a configured client cannot be held by a hand-made account.
    ingestGatewayProperties.setClientIds(List.of("basetool-ingest-gateway"));
    // The LOCAL mirror says service-account-…; Keycloak, asked by id, says otherwise. The name is
    // read from Keycloak precisely so a stale or edited local value cannot stand in for it.
    user.setUsername("service-account-basetool-ingest-gateway");
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(keycloakService.userExists(userId)).thenReturn(true);
    when(keycloakService.usernameOf(userId)).thenReturn(Optional.of("Redshift"));

    assertThrows(IllegalStateException.class, () -> userDeletionService.deleteUser(userId));

    verify(userRepository, never()).delete(any());
  }

  @Test
  void deleteUser_refusesCleanlyWhenTheServiceAccountCheckItselfFails() {
    // The regression this closes: the first cut asked Keycloak's CLIENTS endpoint, production
    // answered 403 (the backend's admin client manages users, it does not inspect clients), and
    // that exception escaped as an unexpected 500 on the deletion. A check that cannot establish
    // its answer must refuse, not explode.
    ingestGatewayProperties.setClientIds(List.of("basetool-ingest-gateway"));
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(keycloakService.userExists(userId)).thenReturn(true);
    when(keycloakService.usernameOf(userId))
        .thenThrow(new ResourceAccessException("keycloak admin call refused"));

    assertThrows(IllegalStateException.class, () -> userDeletionService.deleteUser(userId));

    verify(userRepository, never()).delete(any());
  }

  @Test
  void deleteUser_refusesWhenKeycloakCannotBeReached_ratherThanAssumingTheAccountIsGone() {
    // Fail-closed: an unreachable Keycloak is not evidence of absence. The exception propagates and
    // nothing is purged.
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(keycloakService.userExists(userId))
        .thenThrow(new ResourceAccessException("keycloak unreachable"));

    assertThrows(ResourceAccessException.class, () -> userDeletionService.deleteUser(userId));

    verify(userRepository, never()).delete(any());
    verify(inventoryItemRepository, never()).deleteByUserId(any());
    verify(personalInventoryItemRepository, never()).deleteByOwnerSub(any());
  }

  @Test
  void deleteUser_alwaysRecordsTheUserDeletedMarker_evenWhenTheAccountOwnedNothing() {
    // REQ-AUDIT-001: the deletion mutates several audited areas, so it always leaves exactly one
    // marker event to anchor the per-area purge events — unlike those, it is unconditional.
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(userRepository.findAllAdmins()).thenReturn(List.of(admin));

    // When
    userDeletionService.deleteUser(userId);

    // Then the marker fires...
    verify(auditService)
        .record(eq(AuditEventType.USER_DELETED), isNull(), isNull(), eq(userId), any());
    // ...but the per-area events stay silent for an account that owned nothing (all mocks yield 0).
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
    // Given a user that owns missions: the mission_ownership companion (its owner_id FK has no
    // ON DELETE clause) must be reassigned in lock-step with mission.owner and strictly before the
    // app_user row is removed, otherwise the dangling owner_id FK-fails (23503) on delete.
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(userRepository.findAllAdmins()).thenReturn(List.of(admin));

    // When
    userDeletionService.deleteUser(userId);

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

    userDeletionService.deleteUser(userId);

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
    assertThrows(IllegalStateException.class, () -> userDeletionService.deleteUser(userId));
    verify(userRepository, never()).delete(any());
  }

  @Test
  void shouldThrowExceptionIfNoAdminFound() {
    // Given findAllAdmins yields nobody and getCurrentUser() is empty (default Optional mock).
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(userRepository.findAllAdmins()).thenReturn(Collections.emptyList());

    // When & Then
    assertThrows(IllegalStateException.class, () -> userDeletionService.deleteUser(userId));
    verify(userRepository, never()).delete(any());
  }

  @Test
  void shouldThrowExceptionIfUserNotFound() {
    // Given
    when(userRepository.findById(userId)).thenReturn(Optional.empty());

    // When & Then
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
      // The user being deleted IS the only "admin" in findAllAdmins() — they
      // get filtered out of that list. The fallback resolves to the current
      // logged-in admin, which is a different user with the ADMIN role.
      User toDelete = newUser(userId);
      toDelete.setInKeycloak(false);
      // toDelete also has ADMIN role — that's the scenario being tested
      toDelete.setRoles(new HashSet<>(Set.of(roleNamed("ADMIN"))));

      UUID currentAdminId = UUID.randomUUID();
      User currentAdmin = newUser(currentAdminId);
      currentAdmin.setRoles(new HashSet<>(Set.of(roleNamed("ADMIN"))));

      when(userRepository.findById(userId)).thenReturn(Optional.of(toDelete));
      // findAllAdmins returns only the user being deleted -> filtered out.
      when(userRepository.findAllAdmins()).thenReturn(List.of(toDelete));
      when(userService.getCurrentUser()).thenReturn(Optional.of(currentAdmin));

      userDeletionService.deleteUser(userId);

      verify(refineryOrderRepository).updateOwner(toDelete, currentAdmin);
      verify(userRepository).delete(toDelete);
    }

    @Test
    void throws_whenCurrentUserIsAlsoTheUserBeingDeleted() {
      // The user trying to delete themselves can't be their own reassignment
      // target — must throw.
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
      // findAllAdmins has only the user being deleted; current user is logged in
      // but has no ADMIN role -> no fallback, throw.
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
