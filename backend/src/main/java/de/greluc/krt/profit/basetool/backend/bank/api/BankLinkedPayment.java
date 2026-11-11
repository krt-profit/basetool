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

import de.greluc.krt.profit.basetool.backend.admin.api.TransferFeePolicy.RateSnapshot;
import java.math.BigDecimal;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** The actual parties and frozen terms of one complete linked Bank payment. */
public sealed interface BankLinkedPayment {

  /**
   * Reads the submitted terms.
   *
   * @return the immutable amount and fee basis
   */
  @NotNull
  Terms terms();

  /**
   * Monetary terms retained from submission through confirmation.
   *
   * @param amount principal in whole aUEC
   * @param rate submitted shared fee basis
   * @param note the booking note
   * @param justification the debit justification where required by Bank
   */
  record Terms(
      @NotNull BigDecimal amount,
      @NotNull RateSnapshot rate,
      @Nullable String note,
      @Nullable String justification) {}

  /** The actual depositor or payee, distinct from the authenticated applicant. */
  sealed interface Counterparty {

    /**
     * A registered counterparty.
     *
     * @param userId the member's registered identity
     * @param orgUnitId an optional unit of the member
     */
    record Member(@NotNull UUID userId, @Nullable UUID orgUnitId) implements Counterparty {}

    /**
     * An accountless counterparty.
     *
     * @param name the explicit name
     * @param orgUnitId an optional active unit
     */
    record Accountless(@NotNull String name, @Nullable UUID orgUnitId) implements Counterparty {}

    /** Retains the verified counterparty of a failed linked deposit. */
    record Retained() implements Counterparty {}
  }

  /** The actual movement between players. */
  enum GamePayment {
    /** The complete game payment has been reported. */
    REPORTED,
    /** A proven same-player change of purpose without a game transfer. */
    SAME_PLAYER
  }

  /**
   * A reported payment into Bank custody.
   *
   * @param accountId the account receiving the principal
   * @param holderId the receiving registered custodian
   * @param depositor the actual depositor
   * @param terms the submitted amount and fee basis
   */
  record Deposit(
      @NotNull UUID accountId,
      @NotNull UUID holderId,
      @NotNull Counterparty depositor,
      @NotNull Terms terms)
      implements BankLinkedPayment {}

  /**
   * A reported deposit whose receiving Bank custodian is selected by the confirmer.
   *
   * @param accountId the account receiving the principal
   * @param depositor the actual depositor
   * @param terms the submitted amount and fee basis
   */
  record AccountDeposit(
      @NotNull UUID accountId, @NotNull Counterparty depositor, @NotNull Terms terms)
      implements BankLinkedPayment {}

  /**
   * An account ownership change with unchanged Bank custody.
   *
   * @param sourceAccountId the funding account
   * @param destinationAccountId the receiving account
   * @param terms the submitted principal and fee basis
   */
  record AccountTransfer(
      @NotNull UUID sourceAccountId, @NotNull UUID destinationAccountId, @NotNull Terms terms)
      implements BankLinkedPayment {}

  /**
   * An authorisation to use an account without a custody attribution or ledger movement.
   *
   * @param accountId the funding account
   * @param terms the submitted principal and fee basis
   */
  record AccountAuthorisation(@NotNull UUID accountId, @NotNull Terms terms)
      implements BankLinkedPayment {}

  /**
   * A payout from Bank custody.
   *
   * @param accountId the funding account
   * @param holderId the paying registered custodian
   * @param payee the actual payee
   * @param gamePayment whether a game transfer took place
   * @param terms the submitted amount and fee basis
   */
  record Withdrawal(
      @NotNull UUID accountId,
      @NotNull UUID holderId,
      @NotNull Counterparty payee,
      @NotNull GamePayment gamePayment,
      @NotNull Terms terms)
      implements BankLinkedPayment {}

  /**
   * A change of Bank account ownership.
   *
   * @param sourceAccountId the funding account
   * @param sourceHolderId the registered source custodian
   * @param destinationAccountId the receiving account
   * @param destinationHolderId the registered destination custodian
   * @param gamePayment whether a game transfer took place
   * @param terms the submitted amount and fee basis
   */
  record Transfer(
      @NotNull UUID sourceAccountId,
      @NotNull UUID sourceHolderId,
      @NotNull UUID destinationAccountId,
      @NotNull UUID destinationHolderId,
      @NotNull GamePayment gamePayment,
      @NotNull Terms terms)
      implements BankLinkedPayment {}

  /**
   * A reported game payment between Bank custodians with unchanged principal account ownership.
   *
   * @param sourceHolderId the paying registered custodian
   * @param destinationHolderId the receiving registered custodian
   * @param terms the submitted amount and fee basis
   */
  record HolderTransfer(
      @NotNull UUID sourceHolderId, @NotNull UUID destinationHolderId, @NotNull Terms terms)
      implements BankLinkedPayment {}

  /**
   * An authorisation to use existing funds that leaves both dimensions of the Bank ledger intact.
   *
   * @param accountId the funding account
   * @param holderId the actual registered custodian
   * @param terms the submitted amount and fee basis
   */
  record Authorisation(@NotNull UUID accountId, @NotNull UUID holderId, @NotNull Terms terms)
      implements BankLinkedPayment {}
}
