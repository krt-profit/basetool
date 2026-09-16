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
import de.greluc.krt.profit.basetool.backend.model.BankAuditEventType;
import de.greluc.krt.profit.basetool.backend.repository.AuditEventRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankAuditEventRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankBookingRequestRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankTransactionRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderHandoverRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderItemHandoverRepository;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import de.greluc.krt.profit.basetool.backend.support.HandleAnonymisation;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Erases a member's surviving handle snapshots on a granted Art. 17 request (REQ-SEC-062).
 *
 * <p>Deleting an account removes or reassigns everything it owned (REQ-DATA-008) but deliberately
 * leaves the <b>handle snapshots</b> behind, because they are what keeps a trail readable after the
 * person is gone: the user foreign keys are {@code ON DELETE SET NULL}, so without the snapshot a
 * row would record an action by nobody. The privacy policy says so and rests it on Art. 6(1)(f).
 *
 * <p>That interest is real but not automatically overriding, so the policy also offers the member
 * an Art. 17 request that reaches these snapshots too. This service is that request's execution. It
 * is <b>never automatic</b>: an admin weighs the wish and grants it deliberately, and the procedure
 * for weighing it is in {@code docs/privacy/data-subject-requests.md}.
 *
 * <p><b>Six places, one act.</b> Erasing some and leaving others would be worse than not erasing at
 * all, because the result reads as an erasure that has been performed:
 *
 * <ol>
 *   <li>{@code audit_event.actor_handle} — the activity trail
 *   <li>{@code bank_audit_event.actor_handle} — the bank trail
 *   <li>{@code bank_transaction.counterparty_handle} — the booking history
 *   <li>{@code bank_booking_request} — four columns (requester, decider, counterparty, owner
 *       approver), any of which can name the same member on one row
 *   <li>{@code job_order_handover.recipient_handle} — matched by text, case-insensitively
 *   <li>{@code job_order_item_handover.recipient_handle} — likewise
 * </ol>
 *
 * <p><b>What it does not change.</b> Not one row is removed and not one fact about what happened is
 * altered: timestamps, event types, amounts, accounts, subjects and counts all stand. Only the name
 * goes. That is the difference between this and a deletion, and it is why it can coexist with an
 * append-only guarantee (ADR-0183) — whereas REQ-AUDIT-006's retention sweep deliberately deletes
 * rather than anonymises, because a blanket scrub would turn every old row into the
 * action-by-nobody the snapshot exists to prevent (ADR-0179).
 *
 * <p><b>It leaves a receipt.</b> Both trails get a {@code HANDLE_SNAPSHOTS_ANONYMISED} marker,
 * written <em>after</em> the updates so the marker itself is not scrubbed, carrying the per-table
 * row counts and never the handle that was removed — writing the value back would undo the erasure
 * in the very row that records it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HandleAnonymisationService {

  private final AuditEventRepository auditEventRepository;
  private final BankAuditEventRepository bankAuditEventRepository;
  private final BankTransactionRepository bankTransactionRepository;
  private final BankBookingRequestRepository bankBookingRequestRepository;
  private final JobOrderHandoverRepository jobOrderHandoverRepository;
  private final JobOrderItemHandoverRepository jobOrderItemHandoverRepository;
  private final AuditService auditService;
  private final BankAuditService bankAuditService;

  /**
   * The per-table row counts one anonymisation rewrote.
   *
   * @param activityAudit rows rewritten in {@code audit_event}
   * @param bankAudit rows rewritten in {@code bank_audit_event}
   * @param bankTransactions rows rewritten in {@code bank_transaction}
   * @param bookingRequests rows rewritten in {@code bank_booking_request}
   * @param materialHandovers rows rewritten in {@code job_order_handover}
   * @param itemHandovers rows rewritten in {@code job_order_item_handover}
   */
  public record AnonymisationResult(
      int activityAudit,
      int bankAudit,
      int bankTransactions,
      int bookingRequests,
      int materialHandovers,
      int itemHandovers) {

    /**
     * The total number of rows rewritten across all six places.
     *
     * @return the sum of the per-table counts
     */
    public int total() {
      return activityAudit
          + bankAudit
          + bankTransactions
          + bookingRequests
          + materialHandovers
          + itemHandovers;
    }
  }

  /**
   * Erases the member's handle snapshots everywhere they survive a deletion, and records the two
   * marker events.
   *
   * <p>Runs in one transaction: a partial anonymisation is the one outcome that must not be
   * possible, because the rows left behind would still name a member the record claims was erased.
   *
   * <p>Must be called <b>before</b> the account is deleted. The id-matched updates only reach rows
   * while the foreign key still points at the account; afterwards the handle text is the only link
   * and the admin Personensuche (REQ-SEC-060) is the route to it.
   *
   * @param userId the member whose snapshots are erased
   * @param handle the member's current effective name, used for the two text-matched handover
   *     columns; when {@code null} or blank those two are skipped, because matching an empty string
   *     would rewrite every row that has no recipient
   * @return the per-table row counts
   */
  @Transactional
  public @NotNull AnonymisationResult anonymise(@NotNull UUID userId, @Nullable String handle) {
    String sentinel = HandleAnonymisation.SENTINEL;

    int activityAudit = auditEventRepository.anonymiseActorHandle(userId, sentinel);
    int bankAudit = bankAuditEventRepository.anonymiseActorHandle(userId, sentinel);
    int bankTransactions = bankTransactionRepository.anonymiseCounterpartyHandle(userId, sentinel);
    int bookingRequests = bankBookingRequestRepository.anonymiseHandles(userId, sentinel);

    int materialHandovers = 0;
    int itemHandovers = 0;
    if (handle != null && !handle.isBlank()) {
      materialHandovers = jobOrderHandoverRepository.anonymiseRecipientHandle(handle, sentinel);
      itemHandovers = jobOrderItemHandoverRepository.anonymiseRecipientHandle(handle, sentinel);
    }

    AnonymisationResult result =
        new AnonymisationResult(
            activityAudit,
            bankAudit,
            bankTransactions,
            bookingRequests,
            materialHandovers,
            itemHandovers);

    // The markers go in AFTER the updates, so they are not scrubbed by them. The payload carries
    // counts and the target reference only -- never the handle that was just removed.
    auditService.record(
        AuditEventType.HANDLE_SNAPSHOTS_ANONYMISED,
        userId,
        null,
        userId,
        AuditDetails.of("activityAudit", activityAudit)
            .with("bankAudit", bankAudit)
            .with("bankTransactions", bankTransactions)
            .with("bookingRequests", bookingRequests)
            .with("materialHandovers", materialHandovers)
            .with("itemHandovers", itemHandovers));

    bankAuditService.record(
        BankAuditEventType.HANDLE_SNAPSHOTS_ANONYMISED,
        null,
        null,
        userId,
        AuditDetails.of("bankAudit", bankAudit)
            .with("bankTransactions", bankTransactions)
            .with("bookingRequests", bookingRequests));

    log.info(
        "Anonymised handle snapshots for user {}: {} row(s) across six places",
        userId,
        result.total());
    return result;
  }
}
