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
import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Follows a currency wipe of the game in the Kartellbank (REQ-PATCH-041, REQ-PATCH-042, ADR-0384):
 * the pending requests submitted before the live time are cancelled, and one wipe reset offsets
 * exactly the balances as of the live time, so the bookings made after it stay.
 *
 * <p>A request linked to an order payment attempt is left to its owner, which cancels it through
 * {@link BankLinkedPayments#cancelForCurrencyWipe}.
 */
public interface BankCurrencyWipe {

  /**
   * Counts what {@link #apply} would do now, without writing.
   *
   * @param liveAt the live time of the patch
   * @return the requests it would cancel and the total it would reset
   * @throws BankConflictException with {@code BANK_OVERDRAFT} when the reset would overdraw an
   *     account whose later debits exceed its later credits
   */
  @NotNull
  Effect preview(@NotNull Instant liveAt);

  /**
   * Cancels the pending requests submitted before the live time and books the reset inside the
   * caller's transaction, recording one {@code WIPE_RESET_EXECUTED} event in the Bank's trail that
   * names the patch change.
   *
   * @param liveAt the live time of the patch
   * @param patchChangeId the patch change, kept as each cancelled request's reason
   * @return the requests cancelled and the total reset
   * @throws BankConflictException with {@code BANK_OVERDRAFT} when the reset would overdraw an
   *     account whose later debits exceed its later credits
   * @throws org.springframework.transaction.IllegalTransactionStateException when no transaction is
   *     active
   */
  @NotNull
  Effect apply(@NotNull Instant liveAt, @NotNull UUID patchChangeId);

  /**
   * What the currency wipe does in the Kartellbank.
   *
   * @param requestsCancelled the pending requests submitted before the live time
   * @param resetTotal the sum of the account balances as of the live time, in whole aUEC
   */
  record Effect(long requestsCancelled, @NotNull BigDecimal resetTotal) {}
}
