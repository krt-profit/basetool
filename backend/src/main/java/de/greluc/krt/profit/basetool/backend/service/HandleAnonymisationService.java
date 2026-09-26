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
import de.greluc.krt.profit.basetool.backend.repository.BankHolderRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankTransactionRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderHandoverRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderItemHandoverRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import de.greluc.krt.profit.basetool.backend.support.HandleAnonymisation;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Erases a member's surviving handle snapshots on a granted Art. 17 request (REQ-SEC-062); never
 * automatic.
 *
 * <p>Rewrites the columns in {@link #ANONYMISED_COLUMNS} in one act, each by id match or
 * whole-value comparison against every spelling of the member's name, never by substring. No row is
 * removed and no other fact changes. Both audit trails receive a {@code
 * HANDLE_SNAPSHOTS_ANONYMISED} marker with per-table row counts, never the handle.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HandleAnonymisationService {

  private final AuditEventRepository auditEventRepository;
  private final BankAuditEventRepository bankAuditEventRepository;
  private final BankTransactionRepository bankTransactionRepository;
  private final BankBookingRequestRepository bankBookingRequestRepository;
  private final BankHolderRepository bankHolderRepository;
  private final JobOrderHandoverRepository jobOrderHandoverRepository;
  private final JobOrderItemHandoverRepository jobOrderItemHandoverRepository;
  private final JobOrderRepository jobOrderRepository;
  private final AuditService auditService;
  private final BankAuditService bankAuditService;

  /**
   * Exactly the columns this service rewrites, as {@code table.column}; read by {@code
   * HandleErasureCoverageTest}.
   */
  public static final List<String> ANONYMISED_COLUMNS =
      List.of(
          "audit_event.actor_handle",
          "audit_event.subject_label",
          "bank_audit_event.actor_handle",
          "bank_transaction.counterparty_handle",
          "bank_booking_request.requester_handle",
          "bank_booking_request.decider_handle",
          "bank_booking_request.counterparty_handle",
          "bank_booking_request.owner_approval_granted_by_handle",
          "bank_holder.handle",
          "job_order.handle",
          "job_order_handover.recipient_handle",
          "job_order_item_handover.recipient_handle");

  /**
   * The per-table row counts one anonymisation rewrote.
   *
   * @param activityAudit rows rewritten in {@code audit_event}
   * @param bankAudit rows rewritten in {@code bank_audit_event}
   * @param bankTransactions rows rewritten in {@code bank_transaction}
   * @param bookingRequests rows rewritten in {@code bank_booking_request}
   * @param bankHolders rows rewritten in {@code bank_holder}
   * @param jobOrders rows rewritten in {@code job_order}
   * @param materialHandovers rows rewritten in {@code job_order_handover}
   * @param itemHandovers rows rewritten in {@code job_order_item_handover}
   */
  public record AnonymisationResult(
      int activityAudit,
      int bankAudit,
      int bankTransactions,
      int bookingRequests,
      int bankHolders,
      int jobOrders,
      int materialHandovers,
      int itemHandovers) {

    /**
     * The total number of rows rewritten across every column.
     *
     * @return the sum of the per-table counts
     */
    public int total() {
      return activityAudit
          + bankAudit
          + bankTransactions
          + bookingRequests
          + bankHolders
          + jobOrders
          + materialHandovers
          + itemHandovers;
    }
  }

  /**
   * Erases the member's handle snapshots everywhere they survive a deletion and records the two
   * marker events, in one transaction.
   *
   * <p>Must be called before the account is deleted, while the id-matched foreign keys still point
   * at it.
   *
   * @param userId the member whose snapshots are erased
   * @param spellings every name the member is stored under, for the text-matched columns; blanks
   *     are dropped and those columns are skipped when none remain
   * @return the per-table row counts
   */
  @Transactional
  public @NotNull AnonymisationResult anonymise(
      @NotNull UUID userId, @Nullable Collection<String> spellings) {
    String sentinel = HandleAnonymisation.SENTINEL;
    Set<String> names = usableSpellings(spellings);

    int activityAudit = auditEventRepository.anonymiseActorHandle(userId, sentinel);
    int bankAudit = bankAuditEventRepository.anonymiseActorHandle(userId, sentinel);
    int bankTransactions = bankTransactionRepository.anonymiseCounterpartyHandle(userId, sentinel);
    int bookingRequests = bankBookingRequestRepository.anonymiseHandles(userId, sentinel);
    int bankHolders = bankHolderRepository.anonymiseHandle(userId, sentinel);

    int jobOrders = 0;
    int materialHandovers = 0;
    int itemHandovers = 0;
    for (String name : names) {
      activityAudit += auditEventRepository.anonymiseSubjectLabel(name, sentinel);
      jobOrders += jobOrderRepository.anonymiseHandle(name, sentinel);
      materialHandovers += jobOrderHandoverRepository.anonymiseRecipientHandle(name, sentinel);
      itemHandovers += jobOrderItemHandoverRepository.anonymiseRecipientHandle(name, sentinel);
    }

    AnonymisationResult result =
        new AnonymisationResult(
            activityAudit,
            bankAudit,
            bankTransactions,
            bookingRequests,
            bankHolders,
            jobOrders,
            materialHandovers,
            itemHandovers);

    auditService.record(
        AuditEventType.HANDLE_SNAPSHOTS_ANONYMISED,
        userId,
        null,
        userId,
        AuditDetails.of("activityAudit", activityAudit)
            .with("bankAudit", bankAudit)
            .with("bankTransactions", bankTransactions)
            .with("bookingRequests", bookingRequests)
            .with("bankHolders", bankHolders)
            .with("jobOrders", jobOrders)
            .with("materialHandovers", materialHandovers)
            .with("itemHandovers", itemHandovers)
            .with("spellings", names.size()));

    bankAuditService.record(
        BankAuditEventType.HANDLE_SNAPSHOTS_ANONYMISED,
        null,
        null,
        userId,
        AuditDetails.of("bankAudit", bankAudit)
            .with("bankTransactions", bankTransactions)
            .with("bookingRequests", bookingRequests)
            .with("bankHolders", bankHolders));

    log.info(
        "Anonymised handle snapshots for user {}: {} row(s) across {} column(s), {} spelling(s)",
        userId,
        result.total(),
        ANONYMISED_COLUMNS.size(),
        names.size());
    return result;
  }

  /**
   * Returns the distinct, trimmed, non-blank spellings, longest first.
   *
   * @param spellings the raw candidates, possibly {@code null} and possibly containing blanks
   * @return the distinct non-blank spellings, trimmed, longest first
   */
  private static @NotNull Set<String> usableSpellings(@Nullable Collection<String> spellings) {
    if (spellings == null) {
      return Set.of();
    }
    Set<String> out = new LinkedHashSet<>();
    spellings.stream()
        .filter(candidate -> candidate != null && !candidate.isBlank())
        .map(String::trim)
        .distinct()
        .sorted((a, b) -> Integer.compare(b.length(), a.length()))
        .forEach(out::add);
    return out;
  }
}
