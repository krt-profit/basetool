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

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.model.BankAuditEventType;
import de.greluc.krt.profit.basetool.backend.repository.BankAuditEventRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankBookingRequestRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankHolderRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankTransactionRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Erases a member's handle snapshots in the bank tables for the GDPR handle anonymisation. */
@Component
@RequiredArgsConstructor
public class BankHandleSnapshotService implements BankHandleSnapshots {

  private final BankAuditEventRepository bankAuditEventRepository;
  private final BankTransactionRepository bankTransactionRepository;
  private final BankBookingRequestRepository bankBookingRequestRepository;
  private final BankHolderRepository bankHolderRepository;
  private final BankAuditService bankAuditService;

  /**
   * Rewrites the audit actor, transaction counterparty, booking-request and holder handles, in this
   * order; requires the caller's transaction.
   *
   * @param userId the member whose snapshots are erased
   * @param sentinel the replacement handle
   * @return the per-table row counts
   */
  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull Counts anonymiseHandles(@NotNull UUID userId, @NotNull String sentinel) {
    int bankAudit = bankAuditEventRepository.anonymiseActorHandle(userId, sentinel);
    int bankTransactions = bankTransactionRepository.anonymiseCounterpartyHandle(userId, sentinel);
    int bookingRequests = bankBookingRequestRepository.anonymiseHandles(userId, sentinel);
    int bankHolders = bankHolderRepository.anonymiseHandle(userId, sentinel);
    return new Counts(bankAudit, bankTransactions, bookingRequests, bankHolders);
  }

  /**
   * Records {@code HANDLE_SNAPSHOTS_ANONYMISED} in the bank audit trail with the four row counts;
   * requires the caller's transaction.
   *
   * @param userId the member whose snapshots were erased
   * @param counts the per-table row counts of that anonymisation
   */
  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void recordAnonymised(@NotNull UUID userId, @NotNull Counts counts) {
    bankAuditService.record(
        BankAuditEventType.HANDLE_SNAPSHOTS_ANONYMISED,
        null,
        null,
        userId,
        AuditDetails.of("bankAudit", counts.bankAudit())
            .with("bankTransactions", counts.bankTransactions())
            .with("bookingRequests", counts.bookingRequests())
            .with("bankHolders", counts.bankHolders()));
  }
}
