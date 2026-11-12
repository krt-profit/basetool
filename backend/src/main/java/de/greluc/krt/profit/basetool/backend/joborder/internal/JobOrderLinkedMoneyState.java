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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

/** Current outcome of a linked order attempt. */
public enum JobOrderLinkedMoneyState {
  /** The order prepared terms in the transaction admitting the Bank attempt. */
  SUBMITTING,
  /** Bank decision required before any effect. */
  WAITING,
  /** The coupled ledger and order effects committed. */
  POSTED,
  /** Approval and order provision committed without a ledger effect. */
  AUTHORISED,
  /** The Bank declined the request. */
  REJECTED,
  /** The request was withdrawn before confirmation. */
  CANCELLED,
  /** Both effects of a Bank-only transaction were reversed. */
  REVERSED,
  /** The ledger was reversed and the real payment requires clarification. */
  CLARIFICATION
}
