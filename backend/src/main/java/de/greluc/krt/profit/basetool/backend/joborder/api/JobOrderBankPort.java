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

package de.greluc.krt.profit.basetool.backend.joborder.api;

import de.greluc.krt.profit.basetool.backend.admin.api.TransferFeePolicy.RateSnapshot;
import java.math.BigDecimal;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Coordinates a selected Bank effect in the caller's order transaction. */
public interface JobOrderBankPort {

  /**
   * Submits frozen terms under the current actor's Bank authority.
   *
   * @param payment the prepared order attempt and selected Bank terms
   */
  void submit(@NotNull Payment payment);

  /**
   * Replaces a failed attempt through Bank admission at its retained rate.
   *
   * @param payment the new full payment request
   * @param predecessor the exact failed Bank request
   */
  void replace(@NotNull Payment payment, @NotNull PreviousAttempt predecessor);

  /**
   * Withdraws an unexecuted intent when the owning order closes.
   *
   * @param requestId the pending request
   * @param attemptId the exact linked attempt
   * @param purposeRef the opaque lineage
   */
  void cancelUnexecuted(@NotNull UUID requestId, @NotNull UUID attemptId, @NotNull UUID purposeRef);

  /**
   * Predecessor with an admitted rate and final failed request status.
   *
   * @param attemptId the prior attempt
   * @param requestId the prior request
   */
  record PreviousAttempt(@NotNull UUID attemptId, @NotNull UUID requestId) {}

  /**
   * Checks that a Bank custodian represents the explicitly selected order participant.
   *
   * @param custodianId the selected Bank custodian
   * @param userId the linked order participant
   */
  void requireCustodian(@NotNull UUID custodianId, @NotNull UUID userId);

  /**
   * Checks current permission to select the account as the requester destination.
   *
   * @param accountId the selected account
   * @return whether the actor may view this active account
   */
  boolean canSeeAccount(@NotNull UUID accountId);

  /**
   * Payment data required by the Bank.
   *
   * @param attemptId the new attempt identity
   * @param purposeRef the stable opaque lineage
   * @param selection the Bank coordinates and debit justification
   * @param amount the exact principal
   * @param rate the retained configured fee rate
   * @param actualPayment whether a payment was reported between players
   * @param counterparty the depositor or payee, when applicable
   */
  record Payment(
      @NotNull UUID attemptId,
      @NotNull UUID purposeRef,
      @NotNull JobOrderBankSelection selection,
      @NotNull BigDecimal amount,
      @NotNull RateSnapshot rate,
      boolean actualPayment,
      @Nullable Counterparty counterparty) {}

  /**
   * The actual non-custodian party to a deposit or withdrawal.
   *
   * @param userId the registered party, when linked
   * @param name the explicit accountless name, when unlinked
   */
  record Counterparty(@Nullable UUID userId, @Nullable String name) {}
}
