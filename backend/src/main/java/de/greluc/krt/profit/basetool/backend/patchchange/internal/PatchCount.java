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

package de.greluc.krt.profit.basetool.backend.patchchange.internal;

/**
 * What a patch-change effect counts, in its preview and once it ran (REQ-PATCH-005). Each effect
 * adds the kinds it reports; the admin page shows one tile per kind.
 */
public enum PatchCount {
  /** Stock rows created before the live time that the wipe removes (REQ-PATCH-012). */
  STOCK_ROWS_WIPED,
  /** In-game ships created before the live time that the wipe removes (REQ-PATCH-015). */
  IN_GAME_SHIPS_WIPED,
  /** Members who hold a stock row the wipe removes (REQ-PATCH-016). */
  STOCK_MEMBERS_AFFECTED,
  /** Refinery orders started before the live time that end as cancelled. */
  REFINERY_ORDERS_CANCELED,
  /** Job orders with an earmark on a stock row the wipe removes (REQ-PATCH-013). */
  JOB_ORDERS_AFFECTED,
  /** Materialbörse offers that end because the wipe removes their stock (REQ-PATCH-013). */
  MARKET_OFFERS_ENDED,
  /** Donation pledges withdrawn because the wipe removes their stock (REQ-PATCH-013). */
  DONATIONS_WITHDRAWN,
  /** The sum of the Kartellbank's account balances as of the live time, in whole aUEC. */
  BANK_RESET_TOTAL,
  /** Pending Kartellbank requests submitted before the live time that end as cancelled. */
  BANK_REQUESTS_CANCELED,
  /** Holdings of aUEC orders that existed at the live time and are booked as a real shortage. */
  AUEC_SHORTAGES,
  /** The sum of those shortages, in whole aUEC. */
  AUEC_SHORTAGE_TOTAL,
  /** Bank attempts of aUEC orders waiting since before the live time that end as cancelled. */
  AUEC_BANK_ATTEMPTS_CANCELED,
  /** Unit commitments of aUEC orders opened before the live time that end. */
  AUEC_COMMITMENTS_ENDED
}
