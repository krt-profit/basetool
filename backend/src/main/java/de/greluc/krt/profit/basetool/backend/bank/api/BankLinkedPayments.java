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

import java.math.BigDecimal;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Submits and cancels linked payments under the current actor in the caller's transaction. */
public interface BankLinkedPayments {

  /**
   * Checks how Bank would admit these terms without recording an attempt.
   *
   * @param payment the proposed linked effect
   * @param pin the trusted active org unit, or {@code null} without a selection
   * @return the expected routing and source debit
   */
  @NotNull
  Quote quote(@NotNull BankLinkedPayment payment, @Nullable UUID pin);

  /**
   * Checks replacement admission using the failed request's retained Bank evidence.
   *
   * @param payment the corrected proposed effect
   * @param requestId the failed request
   * @param attempt the failed request's linked identity
   * @param pin the trusted active org unit, or {@code null} without a selection
   * @return the expected routing and source debit
   */
  @NotNull
  Quote quoteReplacement(
      @NotNull BankLinkedPayment payment,
      @NotNull UUID requestId,
      @NotNull BankLinkedAttempt attempt,
      @Nullable UUID pin);

  /**
   * Expected Bank result from the current admission policy.
   *
   * @param pending whether a request would wait for a decision
   * @param sourceDebit exact reduction in the funding account on execution
   */
  record Quote(boolean pending, @NotNull BigDecimal sourceDebit) {}

  /**
   * Admits a new linked attempt under the current Bank policy.
   *
   * @param attempt the stable attempt identity and opaque purpose
   * @param payment the immutable submitted terms
   * @param pin the trusted active org unit, or {@code null} without a selection
   * @return the synchronous pending, posted or authorised outcome
   */
  @NotNull
  BankLinkedOutcome submit(
      @NotNull BankLinkedAttempt attempt, @NotNull BankLinkedPayment payment, @Nullable UUID pin);

  /**
   * Replaces a rejected or cancelled linked request under current Bank admission.
   *
   * @param previousRequestId the failed Bank request
   * @param previousAttempt the exact failed attempt and purpose
   * @param nextAttempt the fresh attempt identity for the same purpose
   * @param payment the complete revised terms using the failed attempt's rate
   * @param pin the trusted active org unit, or {@code null} without a selection
   * @return the synchronous pending, posted or authorised outcome
   */
  @NotNull
  BankLinkedOutcome replace(
      @NotNull UUID previousRequestId,
      @NotNull BankLinkedAttempt previousAttempt,
      @NotNull BankLinkedAttempt nextAttempt,
      @NotNull BankLinkedPayment payment,
      @Nullable UUID pin);

  /**
   * Requires the selected active custodian to belong to the expected registered member.
   *
   * @param custodianId the selected Bank custodian
   * @param expectedUserId the member identified by the owning purpose
   */
  void requireCustodianUser(@NotNull UUID custodianId, @NotNull UUID expectedUserId);

  /**
   * Cancels a pending request using the required purpose owner's authority.
   *
   * @param requestId the Bank request to cancel
   * @param attempt the exact linked attempt
   */
  void cancel(@NotNull UUID requestId, @NotNull BankLinkedAttempt attempt);

  /**
   * Cancels an unexecuted request under the purpose owner's already-held terminal guard.
   *
   * @param requestId the pending request
   * @param attempt the exact linked attempt
   */
  void cancelUnexecuted(@NotNull UUID requestId, @NotNull BankLinkedAttempt attempt);

  /**
   * Cancels a pending linked request for a currency wipe whose owner already cancelled the attempt
   * under its held guard (REQ-PATCH-043); the owner is not called back.
   *
   * @param requestId the pending request
   * @param attempt the exact linked attempt
   * @param patchChangeId the patch change, kept as the request's cancel reason
   */
  void cancelForCurrencyWipe(
      @NotNull UUID requestId, @NotNull BankLinkedAttempt attempt, @NotNull UUID patchChangeId);
}
