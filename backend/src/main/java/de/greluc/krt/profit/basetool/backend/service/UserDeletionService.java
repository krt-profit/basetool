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
 * Hard-deletes an {@code app_user} row together with every foreign-key reference that points at it,
 * in the exact order the database constraints demand. Split out of {@link UserService} (audit
 * Thema&nbsp;7, #1252) so the load-bearing FK-cascade ordering — a genuine landmine — lives on its
 * own seam with its own focused collaborators instead of diluting the identity/query/self-service
 * surface of {@code UserService}.
 *
 * <p>The single write, {@link #deleteUser(UUID)}, only ever runs for an ex-member already removed
 * from Keycloak -- or, through {@link #deleteUser(UUID, KeycloakPresenceCheck)}, for one whose
 * Keycloak user the calling orchestrator removes itself (#1827); it purges the account-owned data,
 * reassigns the shared aggregates that must outlive the member to a fallback admin, unlinks the
 * nullable back-references, clears the Discord-approval audit trail, and snapshots the bank
 * responsible-holder change around the delete. The identity seam it needs (who is the calling
 * admin) is borrowed from {@link UserService#getCurrentUser()} rather than reimplemented, keeping
 * the JWT-subject resolution in its single canonical place.
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
   * <p>Two callers, two truths. An administrator deleting a departed member has only the cached
   * {@code in_keycloak} flag to go on, and a swallowed sync error can leave that stale — so the
   * probe is the thing standing between a stale flag and an irreversible purge. A consolidation
   * orchestrator, by contrast, removes the Keycloak user itself and does so <em>after</em> the
   * database half for retry safety, so for it the probe reports a presence the caller is in the
   * middle of ending.
   *
   * <p>Spelled as an enum rather than a boolean so neither can be passed by accident: the waiving
   * constant states in its own name what the caller is promising to do.
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
   * The five stores keyed by a plain {@code app_user.id} column rather than by a {@code @ManyToOne
   * User} association. Since V235 each column carries a foreign key with {@code ON DELETE CASCADE}
   * (REQ-DATA-008, ADR-0142), so the database removes them with the account; {@link
   * #deleteUser(UUID)} still deletes them explicitly, because that is where the audit event's row
   * counts come from -- a cascade reports none.
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
   * The OwnerScope-free responsible-holder audit seam, injected as an {@link ObjectProvider} and
   * resolved lazily. Used only by {@link #deleteUser(UUID)} to audit a change of a bank account's
   * derived responsible holder when the deleted user was a leader whose membership the DB cascade
   * removes (REQ-BANK-034, ADR-0070). All bank access stays inside {@link
   * OrgUnitBankResponsibilityService}.
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
   * Hard-deletes a user account and everything that belonged to it, keeping only the shared and
   * historical records the organisation still needs. Used by admins to remove ex-members; only a
   * user no longer present in Keycloak may be deleted. The cascade is explicit (per-table delete /
   * reassign calls) so the order matches the FK constraints; auto-cascading would surface confusing
   * FK errors when the table order changes.
   *
   * <p>The split is deliberate and is the contract of this method:
   *
   * <ul>
   *   <li><b>Purged</b> — the departing member's warehouse rows (with the job-order and mission
   *       allocations the database cascades off them), their hangar, and the five FK-less personal
   *       stores: "Mein Inventar", personal blueprints, notifications, notification-rule selectors
   *       and promotion evaluations. Nothing else in the system can remove those five, so leaving
   *       them behind orphans them permanently and lets a returning Keycloak subject re-adopt them.
   *   <li><b>Reassigned to the fallback admin</b> — refinery orders, missions and the 1:1 {@code
   *       mission_ownership} companion. These are not account data: they carry operation finances
   *       and mission history that must stay readable after the member leaves. The companion
   *       reassignment must stay paired with {@code missionRepository.updateOwner}: the parent
   *       mission survives the delete, so the {@code ON DELETE CASCADE} on {@code mission_id} never
   *       fires to clear the row, and the FK-less {@code owner_id} would dangle and FK-fail
   *       (SQLSTATE 23503).
   *   <li><b>Unlinked</b> — mission managers, job-order assignees, mission participants (the row
   *       survives and renders as the deleted-user placeholder) and the audit-only {@code
   *       material_claim.claimed_by_user_id} stamp.
   *   <li><b>Left to the database</b> — every reference whose FK declares {@code ON DELETE SET
   *       NULL} / {@code CASCADE} (bank tables, org-unit membership, org-chart, …).
   * </ul>
   *
   * <p>Every purge is a set-based statement. Loading any of those rows as managed entities first
   * would leave them referencing the {@code User} removed at the end and abort the flush with
   * {@code TransientPropertyValueException} — the failure class that took the production delete
   * down via the responsible-holder snapshot. Nor may any of them use {@code clearAutomatically},
   * which would detach the {@code User} about to be deleted.
   *
   * @param userId user to delete
   * @throws NoSuchElementException when the user id is unknown
   * @throws BadRequestException when the user is still present in Keycloak — checked both against
   *     the stored {@code in_keycloak} flag and, because that flag is only a cached mirror a
   *     swallowed sync error can leave stale, against Keycloak itself. The second check exempts the
   *     service account of a configured ingest gateway, for which the two views disagree by
   *     construction (see {@link #isConfiguredGatewayServiceAccount})
   * @throws IllegalStateException when no other admin exists to receive the reassigned owner
   *     references — a deployment defect, answered as a 500
   * @throws org.springframework.web.client.RestClientException when Keycloak cannot be reached for
   *     that check; the deletion is refused rather than performed on an unverified assumption
   */
  @Transactional
  public void deleteUser(UUID userId) {
    deleteUser(userId, KeycloakPresenceCheck.ENFORCED);
  }

  /**
   * Deletes {@code userId} as {@link #deleteUser(UUID)} does, but lets a caller that removes the
   * Keycloak user itself waive the presence probe.
   *
   * <p>The probe exists for the <em>admin-initiated</em> deletion of an account believed to be
   * gone, where the only evidence is a cached flag a swallowed sync error can leave stale. A
   * consolidation orchestrator is a different caller: it has just read that Keycloak user, moved
   * its identity away, and will delete it moments later — and it deletes the Keycloak user
   * <em>last</em> on purpose, so a rolled-back database half leaves it intact for a clean retry
   * (REQ-SEC-026, ADR-0111). Under {@link KeycloakPresenceCheck#ENFORCED} those two designs are in
   * direct contradiction and the second always loses: the probe finds the throwaway user present
   * and refuses, which is how the queue's link action came to fail every time from #1460 onward
   * (#1827).
   *
   * <p>Teaching the guard about that caller is deliberately preferred over reordering the
   * orchestrator. Reordering would work — {@code readDiscordLink} maps a 404 to empty and the local
   * {@code discord_user_id} fallback exists precisely for the already-deleted case — but it would
   * contradict an acceptance bullet of REQ-SEC-026 and trade away documented retry semantics to
   * route around a guard rather than to inform it.
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
   * Whether this row belongs to the service account of a configured ingest gateway.
   *
   * <p>The single exemption from the stale-flag re-check above, and it exists because the two
   * checks genuinely disagree for exactly this kind of row. An unfiltered {@code GET /users} omits
   * service accounts, so the roster sync never sees one and {@code markMissingUsers} sets {@code
   * in_keycloak = false}; {@link KeycloakService#userExists} asks by id and finds it. The member
   * list therefore shows such a row as "not in Keycloak" while the delete refuses it as still
   * present — which is precisely the state production is in, because the gateway's first call ran
   * the registration flow on itself before the machine-identity carve-out existed (ADR-0129).
   *
   * <p>The re-check is there to stop an admin hard-deleting a real member on a stale flag, which
   * would destroy their Lager, hangar and personal data. A machine has none of that: the row holds
   * nothing, grants nothing (the authority converter short-circuits on {@code azp} before ever
   * reading it), and should never have existed. So the protection is not weakened here, it is
   * simply not applicable.
   *
   * <p><strong>Keyed on the client's own service-account link, not on the username.</strong> {@code
   * service-account-<clientId>} is a display convention, not a reserved namespace — an ordinary
   * user can be created with that exact name — so matching the prefix would let a hand-made account
   * inherit this exemption. Asking Keycloak which user backs a configured gateway client cannot be
   * spoofed that way, and it reuses the same allowlist that already decides which clients may act
   * for a member, so the two cannot drift apart.
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
