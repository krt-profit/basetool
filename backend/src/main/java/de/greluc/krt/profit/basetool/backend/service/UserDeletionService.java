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

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialClaimRepository;
import de.greluc.krt.profit.basetool.backend.repository.MemberEvaluationRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionOwnershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.NotificationRepository;
import de.greluc.krt.profit.basetool.backend.repository.NotificationRuleRepository;
import de.greluc.krt.profit.basetool.backend.repository.PersonalBlueprintRepository;
import de.greluc.krt.profit.basetool.backend.repository.PersonalInventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.RefineryOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserApprovalEventRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import de.greluc.krt.profit.basetool.backend.support.IngestGatewayProperties;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

/**
 * Hard-deletes an {@code app_user} row with every foreign-key reference to it, in the order the
 * database constraints demand.
 *
 * <p>Runs only for an ex-member already removed from Keycloak: purges the account-owned data,
 * reassigns shared aggregates to a fallback admin, unlinks nullable back-references and audits bank
 * responsible-holder changes. The calling admin is resolved through {@link
 * UserService#getCurrentUser()}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserDeletionService {

  /**
   * Keycloak's generated username for a client's service account: {@code service-account-<id>}.
   * Shared with the gauge that has to exclude such a row, so the two cannot spell it differently.
   */
  private static final String SERVICE_ACCOUNT_PREFIX =
      IngestGatewayProperties.SERVICE_ACCOUNT_PREFIX;

  /**
   * I18n key of the 400 detail for deleting an account that still exists in Keycloak — by the
   * stored flag or by the live probe; the caller is told the same either way.
   */
  static final String ERROR_STILL_IN_KEYCLOAK = "error.user.still_in_keycloak";

  /**
   * Whether {@link #deleteUser(UUID, KeycloakPresenceCheck)} must verify against Keycloak that the
   * account is really gone before purging it.
   *
   * <p>An admin deletion relies on a cached flag that may be stale, so it is probed; an
   * orchestrator that deletes the Keycloak user itself afterwards waives the probe.
   */
  public enum KeycloakPresenceCheck {

    /**
     * Verify against Keycloak that the account is gone. The default, and every admin-facing path.
     */
    ENFORCED,

    /**
     * Skip the probe: the caller removes the Keycloak user as part of this same operation. Only
     * legitimate for an orchestrator that has already moved the account's identity away and will
     * delete its Keycloak user once this transaction commits.
     */
    WAIVED_CALLER_REMOVES_THE_KEYCLOAK_USER
  }

  private final IngestGatewayProperties ingestGatewayProperties;
  private final UserRepository userRepository;
  private final InventoryItemRepository inventoryItemRepository;
  private final ShipRepository shipRepository;
  private final RefineryOrderRepository refineryOrderRepository;
  private final MissionRepository missionRepository;
  private final MissionOwnershipRepository missionOwnershipRepository;
  private final JobOrderRepository jobOrderRepository;
  private final MissionParticipantRepository missionParticipantRepository;
  private final MaterialClaimRepository materialClaimRepository;
  private final UserApprovalEventRepository userApprovalEventRepository;
  private final AuditService auditService;

  /**
   * The five stores keyed by a plain {@code app_user.id} column. Their foreign keys cascade on
   * delete (REQ-DATA-008), but {@link #deleteUser(UUID)} deletes them explicitly to count the rows
   * for the audit event.
   */
  private final PersonalInventoryItemRepository personalInventoryItemRepository;

  private final PersonalBlueprintRepository personalBlueprintRepository;
  private final NotificationRepository notificationRepository;
  private final NotificationRuleRepository notificationRuleRepository;
  private final MemberEvaluationRepository memberEvaluationRepository;

  /**
   * The authoritative answer to "is this account really gone from Keycloak?". Consulted by {@link
   * #deleteUser(UUID)} so the irreversible purge never rests on the locally cached {@code
   * in_keycloak} flag alone.
   */
  private final KeycloakService keycloakService;

  /**
   * Lazily resolved audit seam for bank responsible-holder changes, used by {@link
   * #deleteUser(UUID)} when the deleted user led an org unit (REQ-BANK-034).
   */
  private final ObjectProvider<OrgUnitBankResponsibilityService>
      orgUnitBankResponsibilityServiceProvider;

  /**
   * The identity seam. Consulted only for the fallback admin in {@link #deleteUser(UUID)} (the
   * currently authenticated admin when {@code findAllAdmins()} yields no other usable target), so
   * the JWT-subject resolution stays in {@link UserService}'s single canonical accessor rather than
   * being duplicated here.
   */
  private final UserService userService;

  /**
   * Hard-deletes a user no longer present in Keycloak, keeping only the shared and historical
   * records the organisation still needs.
   *
   * <p>The account's rows are handled as follows:
   *
   * <ul>
   *   <li><b>Purged</b>: warehouse rows, hangar, "Mein Inventar", personal blueprints,
   *       notifications, notification-rule selectors and promotion evaluations.
   *   <li><b>Reassigned to the fallback admin</b>: refinery orders, missions and their {@code
   *       mission_ownership} companion.
   *   <li><b>Unlinked</b>: mission managers, job-order assignees, mission participants and {@code
   *       material_claim.claimed_by_user_id}.
   *   <li><b>Left to the database</b>: every reference whose FK declares {@code ON DELETE SET NULL}
   *       or {@code CASCADE}.
   * </ul>
   *
   * <p>Every purge is a set-based statement without {@code clearAutomatically}; loading those rows
   * as managed entities would abort the flush.
   *
   * @param userId user to delete
   * @throws NoSuchElementException when the user id is unknown
   * @throws BadRequestException when the user is still present in Keycloak, by the stored flag or a
   *     live check; the service account of a configured ingest gateway is exempt from the live
   *     check
   * @throws IllegalStateException when no other admin exists to receive the reassigned references
   * @throws org.springframework.web.client.RestClientException when Keycloak cannot be reached for
   *     the live check; the deletion is refused
   */
  @Transactional
  public void deleteUser(UUID userId) {
    deleteUser(userId, KeycloakPresenceCheck.ENFORCED);
  }

  /**
   * Deletes {@code userId} as {@link #deleteUser(UUID)} does, but lets a caller that removes the
   * Keycloak user itself afterwards waive the presence probe (REQ-SEC-026).
   *
   * @param userId user to delete
   * @param presenceCheck whether the Keycloak presence probe applies; {@link
   *     KeycloakPresenceCheck#ENFORCED} for every admin-facing deletion
   * @throws NoSuchElementException when the user id is unknown
   * @throws BadRequestException as {@link #deleteUser(UUID)}, except that the Keycloak probe is
   *     skipped under {@link KeycloakPresenceCheck#WAIVED_CALLER_REMOVES_THE_KEYCLOAK_USER}
   * @throws IllegalStateException as {@link #deleteUser(UUID)}
   */
  @Transactional
  public void deleteUser(UUID userId, @NotNull KeycloakPresenceCheck presenceCheck) {
    User user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new NoSuchElementException("User not found"));

    if (user.isInKeycloak()) {
      throw new BadRequestException(ERROR_STILL_IN_KEYCLOAK);
    }
    if (presenceCheck == KeycloakPresenceCheck.ENFORCED
        && keycloakService.userExists(userId)
        && !isConfiguredGatewayServiceAccount(userId)) {
      throw new BadRequestException(ERROR_STILL_IN_KEYCLOAK);
    }

    User admin =
        userRepository.findAllAdmins().stream()
            .filter(u -> !u.getId().equals(userId))
            .findFirst()
            .orElseGet(
                () ->
                    userService
                        .getCurrentUser()
                        .filter(u -> !u.getId().equals(userId))
                        .filter(
                            u ->
                                u.getRoles().stream()
                                    .anyMatch(r -> r.getName().equalsIgnoreCase(Roles.ADMIN)))
                        .orElseThrow(
                            () ->
                                new IllegalStateException("No admin user found to reassign data")));

    int inventoryDeleted = inventoryItemRepository.deleteByUserId(userId);
    int shipsDeleted = shipRepository.deleteByOwnerId(userId);

    int personalInventoryDeleted = personalInventoryItemRepository.deleteByOwnerUserId(userId);
    int blueprintsDeleted = personalBlueprintRepository.deleteAllByOwnerUserId(userId);
    int notificationsDeleted = notificationRepository.deleteAllForRecipient(userId);
    int ruleSelectorsDeleted = notificationRuleRepository.deleteSelectorsByUserId(userId);
    int evaluationsDeleted = memberEvaluationRepository.deleteAllByUserId(userId);

    missionRepository.updateOwner(user, admin);

    if (inventoryDeleted > 0 || shipsDeleted > 0) {
      auditService.record(
          AuditEventType.INVENTORY_PURGED_ON_USER_DELETION,
          null,
          null,
          userId,
          AuditDetails.of("reason", "user-deletion")
              .with("inventoryRows", inventoryDeleted)
              .with("ships", shipsDeleted)
              .with("fromUser", userId));
    }
    if (personalInventoryDeleted > 0
        || blueprintsDeleted > 0
        || notificationsDeleted > 0
        || ruleSelectorsDeleted > 0
        || evaluationsDeleted > 0) {
      auditService.record(
          AuditEventType.PERSONAL_DATA_PURGED_ON_USER_DELETION,
          null,
          null,
          userId,
          AuditDetails.of("reason", "user-deletion")
              .with("personalInventory", personalInventoryDeleted)
              .with("blueprints", blueprintsDeleted)
              .with("notifications", notificationsDeleted)
              .with("ruleSelectors", ruleSelectorsDeleted)
              .with("evaluations", evaluationsDeleted));
    }
    int refineryReassigned = refineryOrderRepository.updateOwner(user, admin);
    if (refineryReassigned > 0) {
      auditService.record(
          AuditEventType.REFINERY_ORDERS_REASSIGNED,
          null,
          null,
          userId,
          AuditDetails.of("reason", "user-deletion")
              .with("rows", refineryReassigned)
              .with("fromUser", userId)
              .with("toAdmin", admin.getId()));
    }
    missionOwnershipRepository.updateOwner(user, admin);

    missionRepository.removeManager(userId);
    jobOrderRepository.removeAssignee(userId);
    missionParticipantRepository.unlinkUser(userId);
    materialClaimRepository.unlinkClaimedByUser(userId);

    userApprovalEventRepository.deleteByUserId(userId);
    userApprovalEventRepository.clearDecidedBy(userId);
    userRepository.clearApprovedBy(userId);

    final Map<UUID, Set<UUID>> responsibleBefore =
        orgUnitBankResponsibilityServiceProvider
            .getObject()
            .snapshotResponsibleHoldersForUser(userId);

    auditService.record(
        AuditEventType.USER_DELETED,
        null,
        null,
        userId,
        AuditDetails.of("inventoryRows", inventoryDeleted)
            .with("ships", shipsDeleted)
            .with("personalInventory", personalInventoryDeleted)
            .with("blueprints", blueprintsDeleted)
            .with("notifications", notificationsDeleted)
            .with("ruleSelectors", ruleSelectorsDeleted)
            .with("evaluations", evaluationsDeleted)
            .with("refineryOrdersReassignedTo", admin.getId()));

    userRepository.delete(user);
    userRepository.flush();
    orgUnitBankResponsibilityServiceProvider
        .getObject()
        .recordResponsibleHolderChanges(responsibleBefore);
    log.info(
        "User {} deleted: purged {} inventory rows, {} ships, {} personal inventory, {} blueprints,"
            + " {} notifications, {} rule selectors, {} evaluations; missions and refinery orders"
            + " reassigned to admin {}",
        userId,
        inventoryDeleted,
        shipsDeleted,
        personalInventoryDeleted,
        blueprintsDeleted,
        notificationsDeleted,
        ruleSelectorsDeleted,
        evaluationsDeleted,
        admin.getId());
  }

  /**
   * Whether this row belongs to the service account of a configured ingest gateway, the single
   * exemption from the live Keycloak presence check.
   *
   * <p>Such a row is marked missing by the roster sync yet found by id, and holds no member data.
   * Keyed on the client's service-account link as reported by Keycloak, not on the spoofable {@code
   * service-account-} username prefix.
   *
   * @param userId the row being deleted
   * @return {@code true} when Keycloak reports this user as the service account of a configured
   *     gateway client
   */
  private boolean isConfiguredGatewayServiceAccount(UUID userId) {
    Optional<String> username;
    try {
      username = keycloakService.usernameOf(userId);
    } catch (RestClientException unreachable) {
      log.warn("Could not determine whether the row is a gateway service account; refusing");
      return false;
    }
    boolean isMachine =
        username
            .filter(
                name ->
                    ingestGatewayProperties.clientIds().stream()
                        .anyMatch(clientId -> name.equals(SERVICE_ACCOUNT_PREFIX + clientId)))
            .isPresent();
    if (isMachine) {
      log.warn(
          "Deleting the stray app_user row of a configured ingest gateway's service account; "
              + "the stale in_keycloak flag is expected here because an unfiltered user listing "
              + "omits service accounts");
    }
    return isMachine;
  }
}
