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
 * from Keycloak; it purges the account-owned data, reassigns the shared aggregates that must
 * outlive the member to a fallback admin, unlinks the nullable back-references, clears the
 * Discord-approval audit trail, and snapshots the bank responsible-holder change around the delete.
 * The identity seam it needs (who is the calling admin) is borrowed from {@link
 * UserService#getCurrentUser()} rather than reimplemented, keeping the JWT-subject resolution in
 * its single canonical place.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserDeletionService {

  /** Keycloak's generated username for a client's service account: {@code service-account-<id>}. */
  private static final String SERVICE_ACCOUNT_PREFIX = "service-account-";

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
   * The five stores keyed by the departing user's Keycloak subject rather than by a foreign key to
   * {@code app_user}. Nothing in the database links them to the account, so {@link
   * #deleteUser(UUID)} is the only thing that can ever remove them (REQ-DATA-008).
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
   * @throws IllegalStateException when the user is still present in Keycloak — checked both against
   *     the stored {@code in_keycloak} flag and, because that flag is only a cached mirror a
   *     swallowed sync error can leave stale, against Keycloak itself — or when no other admin
   *     exists to receive the reassigned owner references. The second check exempts the service
   *     account of a configured ingest gateway, for which the two views disagree by construction
   *     (see {@link #isConfiguredGatewayServiceAccount})
   * @throws org.springframework.web.client.RestClientException when Keycloak cannot be reached for
   *     that check; the deletion is refused rather than performed on an unverified assumption
   */
  @Transactional
  public void deleteUser(UUID userId) {
    User user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new NoSuchElementException("User not found"));

    if (user.isInKeycloak()) {
      throw new IllegalStateException("Cannot delete user that is still in Keycloak");
    }
    // The stored flag is only a cached mirror of Keycloak, maintained by the sync. A single
    // swallowed sync error leaves it stale at false, which used to be enough for an admin to
    // irreversibly hard-delete an ACTIVE member — and since this method purges rather than
    // reassigns, that now destroys their Lager, hangar and personal data outright. So re-verify
    // against Keycloak itself. Fail-closed by construction: userExists only reports absence on a
    // clean 404 and otherwise propagates, so an unreachable Keycloak aborts the deletion instead of
    // letting it proceed on a stale flag.
    if (keycloakService.userExists(userId) && !isConfiguredGatewayServiceAccount(userId)) {
      throw new IllegalStateException(
          "Cannot delete user that is still in Keycloak (stored flag was stale)");
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

    // ---- Account-owned data: purged with the account (REQ-DATA-008) ----
    // Every delete below is set-based on purpose. Loading any of these rows as managed entities
    // would leave them pointing at the User removed further down and abort the flush with
    // TransientPropertyValueException — see the projection note on the responsible-holder snapshot.
    // The warehouse delete also takes the job-order and mission allocations with it (both FKs are
    // ON DELETE CASCADE, V217) and any open Materialbörse offer (V210); the handover history
    // survives with its inventory link nulled (V58, ON DELETE SET NULL).
    int inventoryDeleted = inventoryItemRepository.deleteByUserId(userId);
    int shipsDeleted = shipRepository.deleteByOwnerId(userId);

    // The five identity columns that carry no foreign key to app_user, so nothing cascades and no
    // retention job reaches them. Left behind they outlive the account indefinitely (free-text
    // notes included), stay undiscoverable because every lookup is keyed by the departed subject,
    // and are silently re-adopted should the same Keycloak subject return. The rule selectors are
    // the worst of them: left in place they keep minting NEW notifications for a recipient that no
    // longer exists. owner_sub / user_id hold app_user.id rendered as text.
    final String ownerSub = userId.toString();
    int personalInventoryDeleted = personalInventoryItemRepository.deleteByOwnerSub(ownerSub);
    int blueprintsDeleted = personalBlueprintRepository.deleteAllByOwnerSub(ownerSub);
    int notificationsDeleted = notificationRepository.deleteAllForRecipient(userId);
    int ruleSelectorsDeleted = notificationRuleRepository.deleteSelectorsByUserSub(userId);
    int evaluationsDeleted = memberEvaluationRepository.deleteAllByUserId(ownerSub);

    // ---- Shared / historical aggregates: these survive, only their ownership moves ----
    // Refinery orders and missions are not account data: they feed operation finances and the
    // mission history that must stay readable after the member leaves. Reassigning (rather than
    // deleting) them keeps those records whole.
    missionRepository.updateOwner(user, admin);

    // System/cascade audit: summary events only (set-based statements expose no per-row ids); the
    // deleted user is the target, the acting admin is the actor. Recorded only when rows were
    // actually affected and carrying the row count, mirroring InventoryOrgUnitReconciler's >0
    // guard.
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
    // The mission_ownership companion (1:1 with mission, owner_id FK has no ON DELETE clause) must
    // be reassigned in lock-step with mission.owner above; otherwise its dangling owner_id FK-fails
    // (23503) on the final delete, because the parent mission survives so its mission_id cascade
    // never clears the row.
    missionOwnershipRepository.updateOwner(user, admin);

    // Remove ManyToMany and nullable references
    missionRepository.removeManager(userId);
    jobOrderRepository.removeAssignee(userId);
    missionParticipantRepository.unlinkUser(userId);
    // material_claim.claimed_by_user_id (V131) is an audit-only FK with no ON DELETE clause; null
    // it
    // so an ex-logistician who ever filed a claim does not FK-fail (23503) on the delete below.
    materialClaimRepository.unlinkClaimedByUser(userId);

    // Discord-approval audit cleanup (epic #720 / REQ-SEC-017, V173). These three references into
    // app_user declare no ON DELETE clause (Postgres NO ACTION), so without explicit cleanup a
    // decided-on or deciding account cannot be hard-deleted. This is the reported regression: an
    // approved, since-removed Discord registration could not be deleted because of FK
    // user_approval_event_user_id_fkey (409). The subject's own audit rows are deleted (user_id is
    // NOT NULL, so they cannot be orphaned); rows the deleted account decided keep the audit but
    // lose their now-gone decider link; and the denormalised app_user.approved_by_id back-pointer
    // on other users is nulled. The approval audit of OTHER users survives. Must run before the
    // app_user delete below so the FK is satisfied at flush.
    userApprovalEventRepository.deleteByUserId(userId);
    userApprovalEventRepository.clearDecidedBy(userId);
    userRepository.clearApprovedBy(userId);

    // Snapshot the responsible holders of every account tied to the user's org units BEFORE the
    // delete: a leader (Staffelleiter / SK-Lead / Bereichsleiter / OL member) being deleted changes
    // the derived Kontoverantwortliche/r of the affected account(s) (REQ-BANK-034, ADR-0070). The
    // org-unit membership rows go via the DB ON DELETE CASCADE, so the delete is flushed before the
    // re-diff so the recompute observes the post-cascade state.
    final Map<UUID, Set<UUID>> responsibleBefore =
        orgUnitBankResponsibilityServiceProvider
            .getObject()
            .snapshotResponsibleHoldersForUser(userId);

    // The marker event for the whole operation (REQ-AUDIT-001): the deletion mutates several
    // audited areas at once, and without it the per-area purge events above would have no common
    // anchor explaining why an admin suddenly emptied someone's Lager. Recorded BEFORE the delete
    // so the acting admin and the target are still resolvable, and unconditionally — unlike the
    // per-area events there is always exactly one deletion to record. The payload holds ids and
    // counts only; the handle snapshot AuditService takes is the sole place a name may appear.
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

    // Delete the user
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
      // Cannot establish it, so do not claim it. The guard then refuses with its ordinary message
      // instead of this method's failure escaping as an unexpected 500 — which is exactly what the
      // first cut did when the clients endpoint answered 403.
      log.warn("Could not determine whether the row is a gateway service account; refusing");
      return false;
    }
    boolean isMachine =
        username
            .filter(
                name ->
                    ingestGatewayProperties.getClientIds().stream()
                        .anyMatch(clientId -> name.equals(SERVICE_ACCOUNT_PREFIX + clientId)))
            .isPresent();
    if (isMachine) {
      // WARN, not DEBUG: deleting a row the application swears it never creates is worth a line in
      // the log, and this path should fire once per environment and then never again.
      log.warn(
          "Deleting the stray app_user row of a configured ingest gateway's service account; "
              + "the stale in_keycloak flag is expected here because an unfiltered user listing "
              + "omits service accounts");
    }
    return isMachine;
  }
}
