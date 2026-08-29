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
 * Moves everything one account <em>owns</em> onto another, so an admin can repair a member who
 * ended up with two (REQ-SEC-045, ADR-0142 point 5).
 *
 * <p>The need arises because a login whose subject matches no row no longer adopts an account found
 * by callsign (#1639). That silent inheritance was the defect — but the outcome it produced was not
 * always wrong, and removing it without a remedy would leave a member's data stranded on an account
 * nobody can log into and the admin with nothing to do about it. This is that remedy, and the
 * difference that matters is that it is a <b>decision</b>: an admin names both accounts, and one
 * audit event records it.
 *
 * <h2>The rule that decides each table</h2>
 *
 * <p><b>Ownership follows the member; attribution stays with the act.</b> A row that says "this
 * belongs to X" moves. A row that says "X did this, then" does not — re-pointing it would not
 * repair an identity, it would falsify history. That is why the member's warehouse stock moves and
 * the audit event recording who booked it does not, and why an open bank grant moves while the
 * record of who granted it stays.
 *
 * <p>The classification is exhaustive by construction: {@code UserAccountMergeCoverageTest} reads
 * every foreign key into {@code app_user} out of the live schema and fails the build unless each
 * one appears in exactly one of {@link #FOLLOWS_THE_MEMBER} or {@link #STAYS_WITH_THE_ACT}. A new
 * user-referencing column cannot be forgotten here; it can only be classified.
 *
 * <h2>Conflicts are refused, not guessed</h2>
 *
 * <p>Several of the moved tables carry a unique constraint over the user column, so the same member
 * may legitimately hold a row on both accounts — two sign-ups for one Einsatz, the same blueprint
 * owned twice. Those are deduplicated: the source's row is dropped where the target already has an
 * equivalent, and the rest are re-pointed. That is safe precisely because the two accounts are one
 * person, so the duplicate carries no information the survivor lacks.
 *
 * <p>{@code bank_holder} is the exception, and it is refused rather than deduplicated: its unique
 * key is the user alone, so a holder on both accounts means <b>two ledgers</b>, and merging ledgers
 * is an accounting decision with money in it, not a duplicate to drop. The merge aborts and says
 * so.
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
   * @param table the table name, from this file's own literals — never from a request
   * @param column the column holding the {@code app_user.id}
   * @param conflictKeys the remaining columns of the unique constraint over {@code column}, or
   *     empty when the table carries none. When present, a source row whose key already exists on
   *     the target is deleted rather than re-pointed, because the update would otherwise violate
   *     the constraint and the duplicate carries nothing the survivor lacks
   */
  private record OwnedRows(String table, String column, List<String> conflictKeys) {

    /** A table with no unique constraint over the user column: every row simply moves. */
    static OwnedRows of(String table, String column) {
      return new OwnedRows(table, column, List.of());
    }

    /** A table whose unique constraint can make the same member hold a row on both accounts. */
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
          // --- What they hold -------------------------------------------------------------
          OwnedRows.of("inventory_item", "user_id"),
          OwnedRows.of("ship", "owner_id"),
          OwnedRows.of("refinery_order", "owner_id"),
          OwnedRows.of("personal_inventory_item", "owner_user_id"),
          OwnedRows.deduped("personal_blueprint", "owner_user_id", "product_key"),

          // --- Where they belong ----------------------------------------------------------
          OwnedRows.deduped("org_unit_membership", "user_id", "org_unit_id"),
          OwnedRows.deduped("org_chart_position", "user_id", "org_unit_id"),
          OwnedRows.of("org_unit", "grand_admiral_user_id"),

          // --- What they run and are signed up for ----------------------------------------
          OwnedRows.of("mission", "owner_id"),
          OwnedRows.of("mission_ownership", "owner_id"),
          OwnedRows.of("mission", "party_lead_user_id"),
          OwnedRows.of("mission_unit", "responsible_user_id"),
          OwnedRows.deduped("mission_managers", "user_id", "mission_id"),
          OwnedRows.deduped("mission_participant", "user_id", "mission_id"),
          OwnedRows.deduped("job_order_assignees", "user_id", "job_order_id"),

          // --- The exchange ---------------------------------------------------------------
          OwnedRows.of("material_exchange_offer", "owner_id"),
          OwnedRows.of("material_exchange_request", "owner_id"),
          OwnedRows.deduped("material_exchange_interest", "interested_user_id", "offer_id"),
          OwnedRows.deduped(
              "material_exchange_request_interest", "interested_user_id", "request_id"),

          // --- What the bank lets them do -------------------------------------------------
          // The grants are current permissions, so they follow. Who granted them does not.
          OwnedRows.deduped("bank_account_grant", "user_id", "account_id"),
          OwnedRows.deduped(
              "bank_account_view_grant", "grantee_user_id", "account_id", "grantee_kind"),
          OwnedRows.deduped(
              "bank_account_approval_limit", "grantee_user_id", "account_id", "grantee_kind"),
          OwnedRows.of("bank_holder", "user_id"),

          // --- What has been said about them ----------------------------------------------
          OwnedRows.of("notification", "recipient_user_id"),
          OwnedRows.of("notification_rule_selector", "user_id"),
          OwnedRows.deduped("member_evaluation", "user_id", "category_id"));

  /**
   * Everything that records an act rather than a belonging, and therefore stays.
   *
   * <p>Each of these answers "who did this, and when", and the answer does not change because the
   * person later ended up with a second account. The audit columns are the clearest case and the
   * reason the whole distinction exists (REQ-AUDIT-001: the trail must outlive even a deleted
   * account); the rest follow the same logic one step further out.
   *
   * <p>Two consequences are accepted deliberately rather than worked around:
   *
   * <ul>
   *   <li>A bank booking request the <em>source</em> account raised keeps naming it as the
   *       requester, so the target cannot act on it as its owner. Re-pointing it would rewrite who
   *       asked, which is exactly what a booking record must not do. An open request is finished
   *       under the account that raised it, or withdrawn.
   *   <li>{@code user_roles} stays because roles are not owned: they are re-derived from the
   *       member's token on every login and from the roster sync (REQ-SEC-013, REQ-SEC-036). Moving
   *       them would grant the target, for one request, a set its own token does not carry.
   *   <li>{@code terms_acceptance} stays because consent is recorded per account. A member who
   *       accepted on the old one is asked once more on the new one — an annoyance, and the honest
   *       alternative to back-dating a consent the surviving account never gave.
   * </ul>
   */
  public static final List<String> STAYS_WITH_THE_ACT =
      List.of(
          // The audit trail, in both its forms. Never moves; must outlive even a deletion.
          "audit_event.actor_user_id",
          "audit_event.target_user_id",
          "bank_audit_event.actor_user_id",
          "bank_audit_event.target_user_id",
          // Who decided, granted, requested, initiated, executed, paid out.
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
          // The approval history OF an account, and who decided it.
          "user_approval_event.user_id",
          "user_approval_event.decided_by_id",
          // Not owned: re-derived from Keycloak, and recorded per account.
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
   * <p>The source row itself is left in place, emptied. Deleting it is the existing user-deletion
   * flow's job and carries its own fail-closed Keycloak probe (REQ-DATA-008) — an account whose
   * Keycloak user still exists must not be removed here as a side effect of a data move, and an
   * admin who wants it gone runs that operation deliberately.
   *
   * @param sourceUserId the account to empty — the one the member can no longer reach
   * @param targetUserId the account to keep — the one the member logs into now
   * @param adminId the acting admin, recorded as the audit actor
   * @return the surviving target account
   * @throws NotFoundException when either account is unknown
   * @throws BusinessConflictException when the two ids are the same, or when both accounts hold a
   *     bank-holder row (two ledgers is an accounting decision, not a duplicate)
   */
  @Transactional
  @NotNull
  public User merge(@NotNull UUID sourceUserId, @NotNull UUID targetUserId, @NotNull UUID adminId) {
    if (sourceUserId.equals(targetUserId)) {
      throw new BusinessConflictException("An account cannot be merged into itself");
    }
    userRepository
        .findById(sourceUserId)
        .orElseThrow(() -> new NotFoundException("Source account not found"));
    final User target =
        userRepository
            .findById(targetUserId)
            .orElseThrow(() -> new NotFoundException("Target account not found"));

    assertLedgersDoNotCollide(sourceUserId, targetUserId);

    // Flush first: the merge is set-based native SQL, so any pending change to these rows must
    // already be in the database or it would be written back over the move afterwards.
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
    // The moved rows are in the database, not in the persistence context; anything already loaded
    // still carries the old owner. Clearing is what stops a later read in this transaction from
    // serving a stale row (the bulk-update landmine of REQ-DATA-008).
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
   * Refuses the merge when both accounts carry a bank-holder row.
   *
   * <p>{@code bank_holder} is unique on the user alone, so this is not a duplicate to drop — it is
   * two ledgers, and which postings belong to which holder is an accounting question with money in
   * it. Failing loudly is the only honest answer a data move can give.
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
    // Table and column names come from FOLLOWS_THE_MEMBER's own literals, never from a request.
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
    // Table and column names come from FOLLOWS_THE_MEMBER's own literals, never from a request.
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
