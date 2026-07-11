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
import de.greluc.krt.profit.basetool.backend.repository.MissionOwnershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.RefineryOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserApprovalEventRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hard-deletes an {@code app_user} row together with every foreign-key reference that points at it,
 * in the exact order the database constraints demand. Split out of {@link UserService} (audit
 * Thema&nbsp;7, #1252) so the load-bearing FK-cascade ordering — a genuine landmine — lives on its
 * own seam with its own focused collaborators instead of diluting the identity/query/self-service
 * surface of {@code UserService}.
 *
 * <p>The single write, {@link #deleteUser(UUID)}, only ever runs for an ex-member already removed
 * from Keycloak; it reassigns the owned aggregates to a fallback admin, unlinks the nullable
 * back-references, clears the Discord-approval audit trail, and snapshots the bank
 * responsible-holder change around the delete. The identity seam it needs (who is the calling
 * admin) is borrowed from {@link UserService#getCurrentUser()} rather than reimplemented, keeping
 * the JWT-subject resolution in its single canonical place.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserDeletionService {

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
   * The org-unit-aware bank seam, injected as an {@link ObjectProvider} to avoid a constructor
   * cycle. Used only by {@link #deleteUser(UUID)} to audit a change of a bank account's derived
   * responsible holder when the deleted user was a leader whose membership the DB cascade removes
   * (REQ-BANK-034, ADR-0070). All bank access stays inside the seam.
   */
  private final ObjectProvider<OrgUnitBankAccessService> orgUnitBankAccessServiceProvider;

  /**
   * The identity seam. Consulted only for the fallback admin in {@link #deleteUser(UUID)} (the
   * currently authenticated admin when {@code findAllAdmins()} yields no other usable target), so
   * the JWT-subject resolution stays in {@link UserService}'s single canonical accessor rather than
   * being duplicated here.
   */
  private final UserService userService;

  /**
   * Deletes a user account along with all owned data (ships, inventory, refinery orders, mission
   * memberships) and its Discord-approval audit trail (epic #720 / V173). Used by admins to remove
   * ex-members; only a user no longer present in Keycloak may be deleted. The cascade is explicit
   * (per-table delete / reassign calls) so the order matches the FK constraints; auto-cascading
   * would surface confusing FK errors when the table order changes. References whose FK declares
   * {@code ON DELETE SET NULL} / {@code CASCADE} (bank tables, org-unit membership, org-chart, …)
   * are left to the database; the no-{@code ON DELETE} references — the owner columns (reassigned
   * to an admin) and the V173 approval audit (cleaned up here) — must be resolved explicitly first.
   *
   * <p>Every {@code app_user} foreign key that carries no {@code ON DELETE} clause is resolved here
   * before the final {@link UserRepository#delete} — reassigned to the fallback admin (owned
   * aggregates: inventory, ships, refinery orders, missions and the {@code mission_ownership}
   * companion) or unlinked (managers, job-order assignees, mission participants, and the audit-only
   * {@code material_claim.claimed_by_user_id} stamp). The {@code mission_ownership.owner_id}
   * reassignment must stay paired with {@code missionRepository.updateOwner}: the parent mission
   * survives the delete (its owner having been moved to the admin), so the {@code ON DELETE
   * CASCADE} on {@code mission_id} never fires to clear the row, and the FK-less {@code owner_id}
   * would otherwise dangle and FK-fail (SQLSTATE 23503).
   *
   * @param userId user to delete
   * @throws NoSuchElementException when the user id is unknown
   * @throws IllegalStateException when the user is still present in Keycloak, or when no other
   *     admin exists to receive the reassigned owner references
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

    // Reassign mandatory fields
    int inventoryReassigned = inventoryItemRepository.updateOwner(user, admin);
    shipRepository.updateOwner(user, admin);
    int refineryReassigned = refineryOrderRepository.updateOwner(user, admin);
    missionRepository.updateOwner(user, admin);
    // System/cascade audit: a deleted user's warehouse rows and refinery orders are bulk-reassigned
    // to the fallback admin. Summary events only (set-based UPDATEs expose no per-row ids); the
    // deleted user is the target, the acting admin is the actor. Recorded only when rows actually
    // moved and carrying the affected-row count, mirroring InventoryOrgUnitReconciler's >0 guard.
    if (inventoryReassigned > 0) {
      auditService.record(
          AuditEventType.INVENTORY_OWNER_REASSIGNED,
          null,
          null,
          userId,
          AuditDetails.of("reason", "user-deletion")
              .with("rows", inventoryReassigned)
              .with("fromUser", userId)
              .with("toAdmin", admin.getId()));
    }
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
        orgUnitBankAccessServiceProvider.getObject().snapshotResponsibleHoldersForUser(userId);

    // Delete the user
    userRepository.delete(user);
    userRepository.flush();
    orgUnitBankAccessServiceProvider.getObject().recordResponsibleHolderChanges(responsibleBefore);
    log.info("User {} deleted and references reassigned to admin {}", userId, admin.getId());
  }
}
