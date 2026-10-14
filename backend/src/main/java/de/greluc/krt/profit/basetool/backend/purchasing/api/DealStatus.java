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

package de.greluc.krt.profit.basetool.backend.purchasing.api;

/** The life of a trade deal (REQ-PUR-007 … REQ-PUR-017); a deal is never deleted (REQ-PUR-022). */
public enum DealStatus {

  /** Created, no money requested yet. */
  DRAFT,

  /** The first tranche is requested from the bank. */
  SUBMITTED,

  /** A tranche is confirmed by the bank. */
  FUNDED,

  /** At least one delivery was recorded. */
  IN_DELIVERY,

  /** Delivery is closed with goods accepted; settlement is due. */
  DELIVERED,

  /** The settlement is recorded and its remainder is back in the funding account. */
  SETTLED,

  /** Cancelled before any money was confirmed. */
  CANCELLED,

  /** Delivery is closed with no goods accepted although money is out; settlement is due. */
  FAILED
}
