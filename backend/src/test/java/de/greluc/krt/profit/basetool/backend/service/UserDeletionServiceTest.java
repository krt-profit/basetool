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

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exchange.api.IngestGatewayProperties;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeClientDirectory;
import de.greluc.krt.profit.basetool.backend.exchange.internal.KnownExchangeClients;
import de.greluc.krt.profit.basetool.backend.identity.api.events.DiscordRegistrationDecidedEvent;
import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.Role;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.orgunit.api.ResponsibleHolderTracker;
import de.greluc.krt.profit.basetool.backend.platform.api.ClientDirectory;
import de.greluc.krt.profit.basetool.backend.platform.api.UserErasureParticipant;
import de.greluc.krt.profit.basetool.backend.platform.api.UserErasureParticipant.Erased;
import de.greluc.krt.profit.basetool.backend.platform.api.UserErasureParticipant.Phase;
import de.greluc.krt.profit.basetool.backend.repository.UserApprovalEventRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
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
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.client.ResourceAccessException;

/**
 * Mockito unit tests for {@link UserDeletionService#deleteUser}: the FK-safe ordering of
 * reassignment, unlinking and audit cleanup before the {@code app_user} delete, and the
 * fallback-admin resolution through {@link UserService#getCurrentUser()}.
 */
@ExtendWith(MockitoExtension.class)
class UserDeletionServiceTest {

  @Mock private UserRepository userRepository;
  @Mock private UserApprovalEventRepository userApprovalEventRepository;
  @Mock private AuditService auditService;
  @Mock private KeycloakService keycloakService;

  @Mock private UserService userService;

  @Mock private ObjectProvider<ResponsibleHolderTracker> responsibleHolderTrackerProvider;

  @Mock private ResponsibleHolderTracker responsibleHolderTracker;

  @Mock private ApplicationEventPublisher eventPublisher;

  /** The gateway allowlist a test may extend; the properties record reads it by reference. */
  private final List<String> gatewayClientIds = new ArrayList<>();

  /**
   * A real instance with an empty allowlist, so no user is a gateway service account and the tests
   * exercise the member path (ADR-0129).
   */
  @Spy
  private final ClientDirectory clientDirectory =
      new ExchangeClientDirectory(
          new IngestGatewayProperties(gatewayClientIds), Mockito.mock(KnownExchangeClients.class));

  /** What the fake participants and the user repository did, in the order it happened. */
  private final List<String> calls = new ArrayList<>();

  private final List<UserErasureParticipant> participants = new ArrayList<>();

  private UserDeletionService userDeletionService;

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
        .when(responsibleHolderTrackerProvider.getObject())
        .thenReturn(responsibleHolderTracker);
    lenient()
        .when(responsibleHolderTracker.snapshotResponsibleHoldersForUser(any()))
        .thenReturn(Map.of());
    lenient()
        .doAnswer(
            _ -> {
              calls.add("delete user");
              return null;
            })
        .when(userRepository)
        .delete(any());

    userDeletionService =
        new UserDeletionService(
            clientDirectory,
            userRepository,
            userApprovalEventRepository,
            auditService,
            participants,
            keycloakService,
            responsibleHolderTrackerProvider,
            userService,
            eventPublisher);
  }

  /**
   * Registers a participant that records its run and returns the given counts.
   *
   * @param name how the run appears in {@link #calls}
   * @param phase the participant's phase
   * @param position its position within the phase
   * @param result the counts it reports
   */
  private void participant(String name, Phase phase, int position, Map<Erased, Integer> result) {
    participants.add(
        new UserErasureParticipant() {
          @Override
          public Phase phase() {
            return phase;
          }

          @Override
          public int position() {
            return position;
          }

          @Override
          public Map<Erased, Integer> erase(UUID erasedUserId, UUID fallbackAdminId) {
            assertEquals(userId, erasedUserId);
            calls.add(name + " -> " + fallbackAdminId);
            return result;
          }
        });
  }

  @Test
  void shouldRunEveryParticipantByPhaseThenPositionBeforeDeletingTheUser() {
    participant("unlink b", Phase.UNLINK, 20, Map.of());
    participant("reassign", Phase.REASSIGN, 10, Map.of());
    participant("delete b", Phase.DELETE, 20, Map.of());
    participant("unlink a", Phase.UNLINK, 10, Map.of());
    participant("delete a", Phase.DELETE, 10, Map.of());
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(userRepository.findAllAdmins()).thenReturn(List.of(admin));

    userDeletionService.deleteUser(userId);

    assertEquals(
        List.of(
            "delete a -> " + adminId,
            "delete b -> " + adminId,
            "reassign -> " + adminId,
            "unlink a -> " + adminId,
            "unlink b -> " + adminId,
            "delete user"),
        calls);
  }

  @Test
  void shouldClearTheApprovalTrailAfterTheParticipantsAndBeforeDeletingTheUser() {
    participant("only", Phase.UNLINK, 10, Map.of());
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(userRepository.findAllAdmins()).thenReturn(List.of(admin));

    userDeletionService.deleteUser(userId);

    InOrder order = inOrder(userApprovalEventRepository, userRepository);
    order.verify(userApprovalEventRepository).deleteByUserId(userId);
    order.verify(userApprovalEventRepository).clearDecidedBy(userId);
    order.verify(userRepository).clearApprovedBy(userId);
    order.verify(userRepository).delete(user);
    assertEquals(List.of("only -> " + adminId, "delete user"), calls);
  }

  @Test
  void deleteUser_recordsPurgeAndReassignmentEventsWithRowCounts_whenRowsAffected() {
    participant(
        "lager and hangar", Phase.DELETE, 10, Map.of(Erased.INVENTORY_ROWS, 3, Erased.SHIPS, 7));
    participant("blueprints", Phase.DELETE, 20, Map.of(Erased.BLUEPRINTS, 8));
    participant("refinery", Phase.REASSIGN, 10, Map.of(Erased.REFINERY_ORDERS_REASSIGNED, 2));
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(userRepository.findAllAdmins()).thenReturn(List.of(admin));

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
                        && d.toString()
                            .equals(
                                "reason=user-deletion inventoryRows=3 ships=7 fromUser="
                                    + userId)));
    verify(auditService)
        .record(
            eq(AuditEventType.PERSONAL_DATA_PURGED_ON_USER_DELETION),
            isNull(),
            isNull(),
            eq(userId),
            argThat(
                d ->
                    d != null
                        && d.toString()
                            .equals(
                                "reason=user-deletion personalInventory=0 blueprints=8"
                                    + " notifications=0 ruleSelectors=0 evaluations=0")));
    verify(auditService)
        .record(
            eq(AuditEventType.REFINERY_ORDERS_REASSIGNED),
            isNull(),
            isNull(),
            eq(userId),
            argThat(
                d ->
                    d != null
                        && d.toString()
                            .equals(
                                "reason=user-deletion rows=2 fromUser="
                                    + userId
                                    + " toAdmin="
                                    + adminId)));
    verify(auditService)
        .record(
            eq(AuditEventType.USER_DELETED),
            isNull(),
            isNull(),
            eq(userId),
            argThat(
                d ->
                    d != null
                        && d.toString()
                            .equals(
                                "inventoryRows=3 ships=7 personalInventory=0 blueprints=8"
                                    + " notifications=0 ruleSelectors=0 evaluations=0"
                                    + " refineryOrdersReassignedTo="
                                    + adminId)));
  }

  @Test
  void deleteUser_sumsTheCountsOfParticipantsReportingTheSameKind() {
    participant("first", Phase.DELETE, 10, Map.of(Erased.NOTIFICATIONS, 2));
    participant("second", Phase.DELETE, 20, Map.of(Erased.NOTIFICATIONS, 3));
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(userRepository.findAllAdmins()).thenReturn(List.of(admin));

    userDeletionService.deleteUser(userId);

    verify(auditService)
        .record(
            eq(AuditEventType.PERSONAL_DATA_PURGED_ON_USER_DELETION),
            isNull(),
            isNull(),
            eq(userId),
            argThat(d -> d != null && d.toString().contains("notifications=5")));
  }

  @Test
  void deleteUser_refusesWhenKeycloakStillHasTheAccount_evenThoughTheStoredFlagSaysOtherwise() {
    participant("never", Phase.DELETE, 10, Map.of());
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(keycloakService.userExists(userId)).thenReturn(true);

    assertThrows(BadRequestException.class, () -> userDeletionService.deleteUser(userId));

    verify(userRepository, never()).delete(any());
    assertEquals(List.of(), calls);
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
    participant("never", Phase.DELETE, 10, Map.of());
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(keycloakService.userExists(userId))
        .thenThrow(new ResourceAccessException("keycloak unreachable"));

    assertThrows(ResourceAccessException.class, () -> userDeletionService.deleteUser(userId));

    verify(userRepository, never()).delete(any());
    assertEquals(List.of(), calls);
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
  void deleteUser_ofAPendingRegistration_clearsTheAdminsPendingNotices() {
    user.setApprovalStatus(ApprovalStatus.PENDING);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(userRepository.findAllAdmins()).thenReturn(List.of(admin));

    userDeletionService.deleteUser(
        userId, UserDeletionService.KeycloakPresenceCheck.WAIVED_CALLER_REMOVES_THE_KEYCLOAK_USER);

    verify(eventPublisher).publishEvent(new DiscordRegistrationDecidedEvent(userId, null));
    verify(userRepository).delete(user);
  }

  @Test
  void deleteUser_ofAnActiveMember_publishesNoRegistrationEvent() {
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(userRepository.findAllAdmins()).thenReturn(List.of(admin));

    userDeletionService.deleteUser(userId);

    verify(eventPublisher, never()).publishEvent(any(Object.class));
  }

  @Test
  void deleteUser_refusedWhileInKeycloak_publishesNothing() {
    user.setApprovalStatus(ApprovalStatus.PENDING);
    user.setInKeycloak(true);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));

    assertThrows(BadRequestException.class, () -> userDeletionService.deleteUser(userId));

    verify(eventPublisher, never()).publishEvent(any(Object.class));
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

      participant("reassign", Phase.REASSIGN, 10, Map.of());

      userDeletionService.deleteUser(userId);

      assertEquals(List.of("reassign -> " + currentAdminId, "delete user"), calls);
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
