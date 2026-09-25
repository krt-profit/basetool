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

package de.greluc.krt.profit.basetool.backend.model.dto.request;

import de.greluc.krt.profit.basetool.backend.validation.WholeNumber;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Write payload for an account-to-account transfer (REQ-BANK-011): value moves between two
 * different accounts and custody moves between the two holders.
 *
 * @param sourceAccountId the account the value leaves
 * @param sourceHolderId the player whose stash shrinks
 * @param destinationAccountId the account the value enters (must differ from the source)
 * @param destinationHolderId the player whose stash grows
 * @param amount whole-aUEC amount, at least 1
 * @param note optional free-text note for the booking history and statements
 * @param justification optional Begr&uuml;ndung (REQ-BANK-045); required when the source account
 *     type {@linkplain
 *     de.greluc.krt.profit.basetool.backend.model.BankAccountType#requiresDebitJustification()
 *     mandates a reason}
 * @param staffNote optional internal note by the booking bank employee (REQ-BANK-054), hidden from
 *     the member-facing views
 * @param feeInclusive fee mode for a holder-changing transfer (REQ-BANK-033): {@code false} debits
 *     {@code amount + fee}, {@code true} debits {@code amount} and delivers {@code amount - fee}
 */
public record BankTransferRequest(
    @NotNull UUID sourceAccountId,
    @NotNull UUID sourceHolderId,
    @NotNull UUID destinationAccountId,
    @NotNull UUID destinationHolderId,
    @NotNull @DecimalMin("1") @DecimalMax("1000000000000.0") @WholeNumber BigDecimal amount,
    @Nullable @Size(max = 500) String note,
    @Nullable @Size(max = 500) String justification,
    @Nullable @Size(max = 500) String staffNote,
    boolean feeInclusive) {

  /**
   * Creates a transfer without justification in the default on-top fee mode; for programmatic
   * callers only.
   *
   * @param sourceAccountId the account the value leaves
   * @param sourceHolderId the player whose stash shrinks
   * @param destinationAccountId the account the value enters (must differ from the source)
   * @param destinationHolderId the player whose stash grows
   * @param amount whole-aUEC amount, at least 1
   * @param note optional free-text note for the booking history and statements
   */
  public BankTransferRequest(
      UUID sourceAccountId,
      UUID sourceHolderId,
      UUID destinationAccountId,
      UUID destinationHolderId,
      BigDecimal amount,
      @Nullable String note) {
    this(
        sourceAccountId,
        sourceHolderId,
        destinationAccountId,
        destinationHolderId,
        amount,
        note,
        null,
        null,
        false);
  }
}
