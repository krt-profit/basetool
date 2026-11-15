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
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
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
   * Checks the current Bank route for proposed Order terms.
   *
   * @param terms the proposed Bank effect
   * @return the expected routing and source debit
   */
  @NotNull
  Quote quote(@NotNull Terms terms);

  /**
   * Checks replacement routing and funding at the previously submitted basis.
   *
   * @param terms the proposed corrected payment terms
   * @param purposeRef the stable opaque lineage
   * @param predecessor the exact failed Bank request
   * @return the expected Bank disposition and source debit
   */
  @NotNull
  Quote quoteReplacement(
      @NotNull Terms terms, @NotNull UUID purposeRef, @NotNull PreviousAttempt predecessor);

  /**
   * Terms of a proposed Bank effect.
   *
   * @param selection the selected Bank coordinates
   * @param amount exact net principal
   * @param rate configured fee basis
   * @param actualPayment whether the operation reports a game payment
   * @param counterparty the actual depositor or payee
   */
  record Terms(
      @NotNull JobOrderBankSelection selection,
      @NotNull BigDecimal amount,
      @NotNull RateSnapshot rate,
      boolean actualPayment,
      @Nullable Counterparty counterparty) {}

  /**
   * Expected Bank result if the terms and admission remain current.
   *
   * @param pending whether the request would await a decision
   * @param sourceDebit exact Bank source-account debit
   */
  record Quote(boolean pending, @NotNull BigDecimal sourceDebit) {}

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
   * Cancels the pending Bank request of an attempt a currency wipe already cancelled under the held
   * order guard (REQ-PATCH-043); the order is not called back.
   *
   * @param requestId the pending request
   * @param attemptId the exact linked attempt
   * @param purposeRef the opaque lineage
   * @param patchChangeId the patch change, kept as the request's cancel reason
   */
  void cancelForCurrencyWipe(
      @NotNull UUID requestId,
      @NotNull UUID attemptId,
      @NotNull UUID purposeRef,
      @NotNull UUID patchChangeId);

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
   * Checks independent account visibility in the current Bank context.
   *
   * @param accountId the selected account
   * @return whether the actor may view the account in the active context
   */
  boolean canSeeAccount(@NotNull UUID accountId);

  /**
   * Checks whether the actor may select this account as a cross-unit credit destination.
   *
   * @param accountId the selected destination
   * @return whether the actor may select the account as a destination
   */
  boolean canSelectCreditAccount(@NotNull UUID accountId);

  /**
   * Returns only selectable Bank references for already authorized Order actions.
   *
   * @param responsibleUnit the collection unit
   * @param requestingUnit the requester unit
   * @param canDeposit the caller may deposit
   * @param canDesignate the caller may designate a recipient
   * @param canDebit the caller may provide from existing funds
   * @return minimal active references
   */
  @NotNull
  AccountOptions accountOptions(
      @NotNull UUID responsibleUnit,
      @NotNull UUID requestingUnit,
      boolean canDeposit,
      boolean canDesignate,
      boolean canDebit);

  /**
   * Authorized reference groups.
   *
   * @param collection accounts for deposits
   * @param designation accounts for requester designation
   * @param withdrawal accounts for withdrawing Bank funds
   * @param transfer accounts for transferring Bank funds
   * @param custodians registered custody actors
   */
  record AccountOptions(
      List<AccountRef> collection,
      List<AccountRef> designation,
      List<AccountRef> withdrawal,
      List<AccountRef> transfer,
      List<CustodianRef> custodians) {}

  /**
   * Reference to an eligible Bank account.
   *
   * @param id the account
   * @param name its label
   */
  record AccountRef(UUID id, String name) {}

  /**
   * Registered custodian reference available for a linked action.
   *
   * @param id the custody identity
   * @param userId the exact linked member
   * @param name their display name
   */
  @Schema(name = "JobOrderMoneyBankCustodian")
  record CustodianRef(UUID id, UUID userId, String name) {}

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
