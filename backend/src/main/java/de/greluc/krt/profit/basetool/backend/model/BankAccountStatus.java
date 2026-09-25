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
 * Lifecycle state of a {@link BankAccount} (REQ-BANK-002). Accounts are never hard-deleted; closing
 * requires a zero balance and makes the account read-only while its history stays accessible.
 */
public enum BankAccountStatus {

  /** The account accepts bookings (deposits, withdrawals, transfer legs, holder rebookings). */
  ACTIVE,

  /**
   * The account is read-only: every booking attempt is rejected with the stable problem code {@code
   * BANK_ACCOUNT_CLOSED}. Reopening (bank management) restores full booking capability.
   */
  CLOSED
}
