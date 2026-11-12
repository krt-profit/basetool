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

package de.greluc.krt.profit.basetool.backend.bank.api;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/** The effect of an admitted linked Bank action. */
public sealed interface BankLinkedOutcome {

  /**
   * A request awaits a decision.
   *
   * @param requestId the existing Bank request
   */
  record Pending(@NotNull UUID requestId) implements BankLinkedOutcome {}

  /**
   * The Bank posted a transaction.
   *
   * @param transactionId the resulting ledger transaction
   */
  record Posted(@NotNull UUID transactionId) implements BankLinkedOutcome {}

  /**
   * Final debit-equivalent approval without a ledger movement.
   *
   * @param authorisationId the stored Bank authorisation identity
   */
  record Authorised(@NotNull UUID authorisationId) implements BankLinkedOutcome {}

  /**
   * Staff rejected a pending request.
   *
   * @param requestId the rejected Bank request
   */
  record Rejected(@NotNull UUID requestId) implements BankLinkedOutcome {}

  /**
   * A pending request was cancelled.
   *
   * @param requestId the cancelled Bank request
   */
  record Cancelled(@NotNull UUID requestId) implements BankLinkedOutcome {}

  /**
   * The Bank reversed a linked ledger transaction.
   *
   * @param originalTransactionId the original ledger transaction
   * @param reversalTransactionId the resulting reversal transaction
   */
  record Reversed(@NotNull UUID originalTransactionId, @NotNull UUID reversalTransactionId)
      implements BankLinkedOutcome {}
}
