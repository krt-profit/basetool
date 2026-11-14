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
 * the pending requests submitted before the live time are cancelled, and one wipe reset offsets the
 * balances as of the live time, so the bookings made after it stay. An offset that would take an
 * account or a holder below zero stops at zero.
 *
 * <p>A request linked to an order payment attempt is left to its owner, which cancels it through
 * {@link BankLinkedPayments#cancelForCurrencyWipe}.
 */
public interface BankCurrencyWipe {

  /**
   * Counts what {@link #apply} would do now, without writing.
   *
   * @param liveAt the live time of the patch
   * @return the requests it would cancel, the total it would reset and the accounts it would stop
   *     at zero
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
   * @return the requests cancelled, the total reset and the accounts stopped at zero
   * @throws org.springframework.transaction.IllegalTransactionStateException when no transaction is
   *     active
   */
  @NotNull
  Effect apply(@NotNull Instant liveAt, @NotNull UUID patchChangeId);

  /**
   * What the currency wipe does in the Kartellbank.
   *
   * @param requestsCancelled the pending requests submitted before the live time
   * @param resetTotal the sum offset from the accounts, in whole aUEC
   * @param accountsClamped the accounts whose offset stopped at zero because more was booked out of
   *     them than into them since the live time
   */
  record Effect(long requestsCancelled, @NotNull BigDecimal resetTotal, long accountsClamped) {}
}
