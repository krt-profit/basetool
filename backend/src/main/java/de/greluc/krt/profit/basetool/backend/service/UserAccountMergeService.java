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

import de.greluc.krt.profit.basetool.backend.exception.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import jakarta.persistence.EntityManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves everything one account owns onto another, so an admin can repair a member who ended up with
 * two (REQ-SEC-045).
 *
 * <p>Ownership follows the member; attribution stays with the act. Every foreign key into {@code
 * app_user} is listed in exactly one of {@link #FOLLOWS_THE_MEMBER} or {@link #STAYS_WITH_THE_ACT},
 * enforced by {@code UserAccountMergeCoverageTest}.
 *
 * <p>Where a unique constraint lets the same row exist on both accounts, the source's duplicate is
 * dropped. A {@code bank_holder} on both accounts means two ledgers, so the merge is refused.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserAccountMergeService {

  private final UserRepository userRepository;
  private final AuditService auditService;
  private final EntityManager entityManager;

  /**
   * One table whose user column is re-pointed from the source account to the target.
   *
   * @param table the table name, from this file's own literals, never from a request
   * @param column the column holding the {@code app_user.id}
   * @param conflictKeys the remaining columns of the unique constraint over {@code column}, or
   *     empty; a source row whose key the target already has is deleted instead of re-pointed
   */
  private record OwnedRows(String table, String column, List<String> conflictKeys) {

    /** A table with no unique constraint over the user column: every row simply moves. */
    @NotNull
    static OwnedRows of(String table, String column) {
      return new OwnedRows(table, column, List.of());
    }

    /** A table whose unique constraint can make the same member hold a row on both accounts. */
    @NotNull
    static OwnedRows deduped(String table, String column, String... conflictKeys) {
      return new OwnedRows(table, column, List.of(conflictKeys));
    }
  }

  /**
   * Everything the member owns, and therefore everything that moves.
   *
   * <p>Ordered the way a person would read it — what they hold, where they belong, what they are
   * signed up for, what the bank lets them do, what they have been told — rather than
   * alphabetically, because this list is meant to be argued with.
   */
  private static final List<OwnedRows> FOLLOWS_THE_MEMBER =
      List.of(
          OwnedRows.of("inventory_item", "user_id"),
          OwnedRows.of("ship", "owner_id"),
          OwnedRows.of("refinery_order", "owner_id"),
          OwnedRows.of("personal_inventory_item", "owner_user_id"),
          OwnedRows.deduped("personal_blueprint", "owner_user_id", "product_key"),
          OwnedRows.deduped("org_unit_membership", "user_id", "org_unit_id"),
          OwnedRows.deduped("org_chart_position", "user_id", "org_unit_id"),
          OwnedRows.of("org_unit", "grand_admiral_user_id"),
          OwnedRows.of("mission", "owner_id"),
          OwnedRows.of("mission_ownership", "owner_id"),
          OwnedRows.of("mission", "party_lead_user_id"),
          OwnedRows.of("mission_unit", "responsible_user_id"),
          OwnedRows.deduped("mission_managers", "user_id", "mission_id"),
          OwnedRows.deduped("mission_participant", "user_id", "mission_id"),
          OwnedRows.deduped("job_order_assignees", "user_id", "job_order_id"),
          OwnedRows.of("material_exchange_offer", "owner_id"),
          OwnedRows.of("material_exchange_request", "owner_id"),
          OwnedRows.deduped("material_exchange_interest", "interested_user_id", "offer_id"),
          OwnedRows.deduped(
              "material_exchange_request_interest", "interested_user_id", "request_id"),
          OwnedRows.deduped("bank_account_grant", "user_id", "account_id"),
          OwnedRows.deduped(
              "bank_account_view_grant", "grantee_user_id", "account_id", "grantee_kind"),
          OwnedRows.deduped(
              "bank_account_approval_limit", "grantee_user_id", "account_id", "grantee_kind"),
          OwnedRows.of("bank_holder", "user_id"),
          OwnedRows.of("notification", "recipient_user_id"),
          OwnedRows.of("notification_rule_selector", "user_id"),
          OwnedRows.deduped("member_evaluation", "user_id", "category_id"));

  /**
   * Every user column that records an act rather than a belonging, and therefore stays with the
   * source account.
   *
   * <p>Includes the audit columns (REQ-AUDIT-001), the requester of a bank booking request, {@code
   * user_roles} (re-derived on every login, REQ-SEC-013) and {@code terms_acceptance} (consent is
   * per account).
   */
  public static final List<String> STAYS_WITH_THE_ACT =
      List.of(
          "audit_event.actor_user_id",
          "audit_event.target_user_id",
          "bank_audit_event.actor_user_id",
          "bank_audit_event.target_user_id",
          "app_user.approved_by_id",
          "bank_account_grant.granted_by",
          "bank_booking_request.requested_by",
          "bank_booking_request.decided_by",
          "bank_booking_request.owner_approval_granted_by",
          "bank_booking_request.counterparty_user_id",
          "bank_transaction.initiated_by",
          "bank_transaction.counterparty_user_id",
          "job_order_handover.executing_user_id",
          "job_order_item_handover.executing_user_id",
          "material_claim.claimed_by_user_id",
          "operation_payout_status.paid_out_by_user_id",
          "user_approval_event.user_id",
          "user_approval_event.decided_by_id",
          "deletion_request.user_id",
          "deletion_request.decided_by_id",
          "user_roles.user_id",
          "terms_acceptance.user_id");

  /**
   * The moved tables as {@code table.column}, the form the schema catalogue reports.
   *
   * <p>Exists so {@code UserAccountMergeCoverageTest} can compare the classification against the
   * live schema without reaching into the private record that describes a move.
   *
   * @return one qualified column name per moved table, in declaration order
   */
  @NotNull
  public static List<String> followedColumns() {
    return FOLLOWS_THE_MEMBER.stream().map(o -> o.table() + "." + o.column()).toList();
  }

  /**
   * Moves everything {@code sourceUserId} owns onto {@code targetUserId}.
   *
   * <p>The emptied source row is left in place; removing it is the separate user-deletion flow.
   *
   * @param sourceUserId the account to empty, which the member can no longer reach
   * @param targetUserId the account to keep, which the member logs into now
   * @param adminId the acting admin, recorded as the audit actor
   * @return the surviving target account
   * @throws NotFoundException when either account is unknown
   * @throws BusinessConflictException when the two ids are the same, or both accounts hold a
   *     bank-holder row
   */
  @Transactional
  @NotNull
  public User merge(@NotNull UUID sourceUserId, @NotNull UUID targetUserId, @NotNull UUID adminId) {
    if (sourceUserId.equals(targetUserId)) {
      throw new BusinessConflictException("An account cannot be merged into itself");
    }
    final User source =
        Entities.require(userRepository.findById(sourceUserId), "Source account not found");
    final User target =
        Entities.require(userRepository.findById(targetUserId), "Target account not found");

    assertLedgersDoNotCollide(sourceUserId, targetUserId);
    carryRsiHandle(source, target);

    entityManager.flush();

    Map<String, Integer> moved = new LinkedHashMap<>();
    int total = 0;
    for (OwnedRows owned : FOLLOWS_THE_MEMBER) {
      int dropped = dropRowsTheTargetAlreadyHas(owned, sourceUserId, targetUserId);
      int repointed = repoint(owned, sourceUserId, targetUserId);
      if (repointed > 0 || dropped > 0) {
        moved.put(owned.table() + "." + owned.column(), repointed);
        total += repointed;
      }
    }
    entityManager.clear();

    AuditDetails details = AuditDetails.of("fromUser", sourceUserId).with("rows", total);
    for (Map.Entry<String, Integer> entry : moved.entrySet()) {
      details = details.with(entry.getKey(), entry.getValue());
    }
    auditService.record(AuditEventType.USER_MERGED, null, null, targetUserId, details);

    log.info(
        "Merged account {} into {}: {} row(s) moved across {} table(s) (acting admin {})",
        sourceUserId,
        targetUserId,
        total,
        moved.size(),
        adminId);
    return target;
  }

  /**
   * Moves the source's RSI handle onto the target when the target has none, and clears it on the
   * source either way, so the survivor's handle wins and the handle stays unique (REQ-SEC-072).
   *
   * @param source the account being emptied
   * @param target the account being kept
   */
  private void carryRsiHandle(@NotNull User source, @NotNull User target) {
    String handle = source.getRsiHandle();
    if (handle == null) {
      return;
    }
    source.setRsiHandle(null);
    userRepository.saveAndFlush(source);
    if (target.getRsiHandle() == null) {
      target.setRsiHandle(handle);
      userRepository.saveAndFlush(target);
    }
  }

  /**
   * Refuses the merge when both accounts carry a bank-holder row, since that is two ledgers rather
   * than a duplicate.
   *
   * @param sourceUserId the account being emptied
   * @param targetUserId the account being kept
   * @throws BusinessConflictException when both hold a bank-holder row
   */
  private void assertLedgersDoNotCollide(@NotNull UUID sourceUserId, @NotNull UUID targetUserId) {
    Number holders =
        (Number)
            entityManager
                .createNativeQuery(
                    "SELECT count(*) FROM bank_holder WHERE user_id IN (:source, :target)")
                .setParameter("source", sourceUserId)
                .setParameter("target", targetUserId)
                .getSingleResult();
    if (holders.intValue() > 1) {
      throw new BusinessConflictException(
          "Both accounts hold a bank ledger. Merging two ledgers is an accounting decision and is"
              + " not done by this operation; settle or close one holder first.");
    }
  }

  /**
   * Deletes the source rows whose unique key the target already carries.
   *
   * <p>Only for a table that declares such a constraint; without it there is nothing to collide and
   * this is a no-op. {@code IS NOT DISTINCT FROM} rather than {@code =} so a nullable key column
   * (an org-chart position with no org unit) compares the way the partial unique index does.
   *
   * @param owned the table being moved
   * @param sourceUserId the account being emptied
   * @param targetUserId the account being kept
   * @return the number of duplicate source rows removed
   */
  private int dropRowsTheTargetAlreadyHas(
      @NotNull OwnedRows owned, @NotNull UUID sourceUserId, @NotNull UUID targetUserId) {
    if (owned.conflictKeys().isEmpty()) {
      return 0;
    }
    StringBuilder keyMatch = new StringBuilder();
    for (String key : owned.conflictKeys()) {
      keyMatch.append(" AND tgt.").append(key).append(" IS NOT DISTINCT FROM src.").append(key);
    }
    String sql =
        "DELETE FROM %1$s src WHERE src.%2$s = :source AND EXISTS (SELECT 1 FROM %1$s tgt WHERE"
                .formatted(owned.table(), owned.column())
            + " tgt.%s = :target%s)".formatted(owned.column(), keyMatch);
    return entityManager
        .createNativeQuery(sql)
        .setParameter("source", sourceUserId)
        .setParameter("target", targetUserId)
        .executeUpdate();
  }

  /**
   * Re-points one table's user column from the source account to the target.
   *
   * @param owned the table being moved
   * @param sourceUserId the account being emptied
   * @param targetUserId the account being kept
   * @return the number of rows moved
   */
  private int repoint(
      @NotNull OwnedRows owned, @NotNull UUID sourceUserId, @NotNull UUID targetUserId) {
    String sql =
        "UPDATE %s SET %s = :target WHERE %s = :source"
            .formatted(owned.table(), owned.column(), owned.column());
    return entityManager
        .createNativeQuery(sql)
        .setParameter("source", sourceUserId)
        .setParameter("target", targetUserId)
        .executeUpdate();
  }
}
