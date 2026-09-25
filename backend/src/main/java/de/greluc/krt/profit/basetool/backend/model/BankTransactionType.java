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

package de.greluc.krt.profit.basetool.backend.model;

/**
 * The kind of value movement a {@link BankTransaction} records (REQ-BANK-004, ADR-0010/0039). The
 * type determines the leg shape invariant enforced by {@code BankLedgerService} across the two
 * append-only ledgers (account legs in {@code bank_posting}, holder legs in {@code
 * bank_holder_posting}); the V180 CHECK constraint mirrors this set.
 */
public enum BankTransactionType {

  /** Money entered the bank: one positive account leg and one positive holder leg (receiver). */
  DEPOSIT,

  /** Money left the bank: one negative account leg and one negative holder leg (payer). */
  WITHDRAWAL,

  /**
   * Account↔account transfer (REQ-BANK-011): two account legs summing to zero <strong>and</strong>
   * two holder legs summing to zero — physical custody moves with the booked money. Source and
   * destination accounts always differ (intra-account rebooking is replaced by {@link
   * #HOLDER_TRANSFER} since ADR-0039).
   */
  TRANSFER,

  /**
   * Holder→holder Umbuchung (REQ-BANK-031, ADR-0039): two holder legs summing to zero and
   * <strong>no</strong> account leg — pure custody reconciliation between players. The source
   * holder may go negative (REQ-BANK-006).
   */
  HOLDER_TRANSFER,

  /**
   * Admin-only reset after a Star Citizen wipe (REQ-BANK-013): one negative account leg per
   * non-zero account balance and one negative holder leg per non-zero global holder balance,
   * zeroing both dimensions independently while history survives.
   */
  WIPE_RESET,

  /**
   * Correction booking: its legs negate the reversed transaction's legs on both ledgers, and it
   * references the original via {@link BankTransaction#getReversedTransaction()}. A transaction can
   * be reversed at most once.
   */
  REVERSAL
}
