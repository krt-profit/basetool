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

/** One thing an actor may do with one bank account, as decided by {@link BankAccessPolicy}. */
public enum BankAccountRight {

  /** Read the account on the org-unit side, under the active-org-unit pin (REQ-BANK-021/037). */
  VIEW,

  /** Raise a withdrawal or transfer request: {@link #VIEW} on a request-capable account. */
  REQUEST,

  /**
   * Be the account's derived responsible holder (REQ-BANK-034): exempt from every approval gate,
   * the approver of its holder-routed requests and the reader of its foreign requests.
   */
  RESPONSIBLE_HOLDER,

  /** Set or clear the balance target from the org-unit side (REQ-BANK-036). */
  SET_TARGET,

  /** Add or remove view grants (REQ-BANK-035). */
  CONFIGURE_VISIBILITY,

  /** Set or clear approval limits and tiers from the org-unit side (REQ-BANK-041). */
  CONFIGURE_APPROVAL_LIMITS,

  /** Read the account on the bank-staff side (REQ-BANK-009). */
  STAFF_VIEW,

  /** Book or confirm deposits onto the account as bank staff (REQ-BANK-009). */
  STAFF_DEPOSIT,

  /** Book or confirm withdrawals from the account as bank staff (REQ-BANK-009). */
  STAFF_WITHDRAW,

  /** Book or confirm transfers out of the account as bank staff (REQ-BANK-011). */
  STAFF_TRANSFER
}
