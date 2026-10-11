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

package de.greluc.krt.profit.basetool.backend.bank.internal;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.kernel.HandleAnonymisation;
import de.greluc.krt.profit.basetool.backend.platform.api.HandleAnonymisationParticipant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The bank's part of the handle anonymisation: the handle snapshots in the ledger, the booking
 * requests, the custodian registry and the bank audit trail, plus the bank's own marker row.
 */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class BankHandleAnonymisation implements HandleAnonymisationParticipant {

  static final int POSITION = 20;

  private final BankAuditEventRepository bankAuditEventRepository;
  private final BankTransactionRepository bankTransactionRepository;
  private final BankBookingRequestRepository bankBookingRequestRepository;
  private final BankHolderRepository bankHolderRepository;
  private final BankAuditService bankAuditService;

  @Override
  public int position() {
    return POSITION;
  }

  @Override
  public @NotNull List<String> columns() {
    return List.of(
        "bank_audit_event.actor_handle",
        "bank_transaction.counterparty_handle",
        "bank_booking_request.requester_handle",
        "bank_booking_request.decider_handle",
        "bank_booking_request.counterparty_handle",
        "bank_booking_request.owner_approval_granted_by_handle",
        "bank_holder.handle");
  }

  /**
   * Rewrites the audit actor, transaction counterparty, booking-request and holder handles of the
   * member, in this order; every column is matched by id.
   *
   * @param userId the member whose snapshots are erased
   * @param spellings unused: no bank column is matched by text
   * @return the rows rewritten per table
   */
  @Override
  public @NotNull Map<Rewritten, Integer> anonymise(
      @NotNull UUID userId, @NotNull List<String> spellings) {
    String sentinel = HandleAnonymisation.SENTINEL;
    Map<Rewritten, Integer> rewritten = new EnumMap<>(Rewritten.class);
    rewritten.put(
        Rewritten.BANK_AUDIT, bankAuditEventRepository.anonymiseActorHandle(userId, sentinel));
    rewritten.put(
        Rewritten.BANK_TRANSACTIONS,
        bankTransactionRepository.anonymiseCounterpartyHandle(userId, sentinel));
    rewritten.put(
        Rewritten.BOOKING_REQUESTS,
        bankBookingRequestRepository.anonymiseHandles(userId, sentinel));
    rewritten.put(Rewritten.BANK_HOLDERS, bankHolderRepository.anonymiseHandle(userId, sentinel));
    return rewritten;
  }

  /**
   * Records {@code HANDLE_SNAPSHOTS_ANONYMISED} in the bank audit trail with the four bank row
   * counts.
   *
   * @param userId the member whose snapshots were erased
   * @param rewritten the summed row counts of every participant
   */
  @Override
  public void recordAnonymised(@NotNull UUID userId, @NotNull Map<Rewritten, Integer> rewritten) {
    bankAuditService.record(
        BankAuditEventType.HANDLE_SNAPSHOTS_ANONYMISED,
        null,
        null,
        userId,
        AuditDetails.of("bankAudit", rewritten.getOrDefault(Rewritten.BANK_AUDIT, 0))
            .with("bankTransactions", rewritten.getOrDefault(Rewritten.BANK_TRANSACTIONS, 0))
            .with("bookingRequests", rewritten.getOrDefault(Rewritten.BOOKING_REQUESTS, 0))
            .with("bankHolders", rewritten.getOrDefault(Rewritten.BANK_HOLDERS, 0)));
  }
}
