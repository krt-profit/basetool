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
  /** Refinery orders started before the live time that end as cancelled. */
  REFINERY_ORDERS_CANCELED,
  /** The sum of the Kartellbank's account balances as of the live time, in whole aUEC. */
  BANK_RESET_TOTAL,
  /** Pending Kartellbank requests submitted before the live time that end as cancelled. */
  BANK_REQUESTS_CANCELED,
  /** aUEC order holdings that existed at the live time and are booked as a real shortage. */
  AUEC_SHORTAGES,
  /** The sum of those shortages, in whole aUEC. */
  AUEC_SHORTAGE_TOTAL,
  /** aUEC order Bank attempts waiting since before the live time that end as cancelled. */
  AUEC_BANK_ATTEMPTS_CANCELED,
  /** aUEC unit commitments opened before the live time that end. */
  AUEC_COMMITMENTS_ENDED
}
