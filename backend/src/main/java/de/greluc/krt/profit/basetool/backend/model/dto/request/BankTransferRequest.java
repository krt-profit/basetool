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
 * Write payload for booking an account-to-account transfer (REQ-BANK-011, ADR-0039): two account
 * legs summing to zero and two holder legs summing to zero — value moves between two
 * <strong>different</strong> accounts and physical custody moves with it. Identical source and
 * destination account is a rejected self-transfer ({@code BANK_SELF_TRANSFER}); moving custody
 * between holders without touching an account is a holder Umbuchung ({@code
 * BankHolderTransferRequest}), not a transfer.
 *
 * @param sourceAccountId the account the value leaves
 * @param sourceHolderId the player whose stash shrinks
 * @param destinationAccountId the account the value enters (must differ from the source)
 * @param destinationHolderId the player whose stash grows
 * @param amount whole-aUEC amount, at least 1
 * @param note optional free-text note for the booking history and statements
 * @param justification optional free-text justification (Begr&uuml;ndung) for the booking history
 *     and statements (REQ-BANK-045); required by the service when the source account type
 *     {@linkplain
 *     de.greluc.krt.profit.basetool.backend.model.BankAccountType#requiresDebitJustification()
 *     mandates a reason}, optional otherwise
 * @param feeInclusive fee-mode toggle (REQ-BANK-033, #999), effective only on a holder-changing
 *     (fee-bearing) transfer: {@code false} (default, unchanged) means the entered {@code amount}
 *     arrives at the destination and the fee is added on top — the source is debited {@code amount
 *     + fee}; {@code true} means the entered {@code amount} is the gross debited and the
 *     destination receives {@code amount - fee}. A bank-staff choice at booking time only — a
 *     transfer <em>request</em> never carries it (confirmation always books on-top)
 */
public record BankTransferRequest(
    @NotNull UUID sourceAccountId,
    @NotNull UUID sourceHolderId,
    @NotNull UUID destinationAccountId,
    @NotNull UUID destinationHolderId,
    @NotNull @DecimalMin("1") @DecimalMax("1000000000000.0") @WholeNumber BigDecimal amount,
    @Nullable @Size(max = 500) String note,
    @Nullable @Size(max = 500) String justification,
    boolean feeInclusive) {

  /**
   * Convenience constructor for a transfer without a recorded justification (the pre-REQ-BANK-045
   * shape) and the default on-top fee mode (REQ-BANK-033), delegating to the canonical constructor
   * with {@code justification} {@code null} and {@code feeInclusive} {@code false}. Inbound JSON is
   * always deserialized via the canonical (all-component) constructor, so this overload only serves
   * programmatic callers.
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
        false);
  }
}
