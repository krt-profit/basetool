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
import de.greluc.krt.profit.basetool.backend.platform.api.NameRetentionParticipant;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Replaces the name snapshots of deleted accounts in the booking history and the holder register
 * once the booking, or the holder's last activity, lies more than the name retention ago
 * (REQ-DATA-047); the bookings and amounts stay, and the bank's own trail records it.
 */
@Component
@RequiredArgsConstructor
public class BankLedgerNameRetention implements NameRetentionParticipant {

  private final BankTransactionRepository transactionRepository;
  private final BankHolderRepository holderRepository;
  private final BankAuditService bankAuditService;

  @Override
  public @NotNull String area() {
    return "bank_ledger";
  }

  @Override
  @Transactional
  public int removeNamesBefore(@NotNull Instant cutoff) {
    String sentinel = HandleAnonymisation.SENTINEL;
    int bookings = transactionRepository.clearDeletedCounterpartyNamesBefore(cutoff, sentinel);
    int holders = holderRepository.clearDeletedHolderNamesBefore(cutoff, sentinel);
    if (bookings + holders > 0) {
      bankAuditService.record(
          BankAuditEventType.NAME_RETENTION_APPLIED,
          null,
          null,
          null,
          AuditDetails.of("bookings", bookings).with("holders", holders).with("cutoff", cutoff));
    }
    return bookings + holders;
  }
}
