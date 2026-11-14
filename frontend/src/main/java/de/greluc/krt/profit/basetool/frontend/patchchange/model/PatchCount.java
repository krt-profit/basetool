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

package de.greluc.krt.profit.basetool.frontend.patchchange.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;

/**
 * Mirror of the backend's kinds of what a patch-change effect counts (REQ-PATCH-005); each kind is
 * one tile of the preview, labelled by {@code admin.patchChange.count.<KIND>}.
 *
 * <p>Must carry every backend constant under the same name, or deserialising a response that
 * contains it fails.
 */
@DtoMirror
public enum PatchCount {
  /** The running refinery orders the refinery effect cancels (REQ-PATCH-006). */
  REFINERY_ORDERS_CANCELED,
  /** The Bank balances as of the live time the currency wipe resets (REQ-PATCH-041). */
  BANK_RESET_TOTAL,
  /** The pending Kartellbank requests the currency wipe cancels (REQ-PATCH-042). */
  BANK_REQUESTS_CANCELED,
  /** The aUEC order holdings the currency wipe books as a real shortage (REQ-PATCH-044). */
  AUEC_SHORTAGES,
  /** The sum of those shortages in whole aUEC (REQ-PATCH-044). */
  AUEC_SHORTAGE_TOTAL,
  /** The waiting aUEC order Bank attempts the currency wipe cancels (REQ-PATCH-043). */
  AUEC_BANK_ATTEMPTS_CANCELED,
  /** The aUEC unit commitments the currency wipe ends (REQ-PATCH-045). */
  AUEC_COMMITMENTS_ENDED,
  /** The members older than the live time the home-location prompt asks (REQ-PATCH-021). */
  MEMBERS_ASKED
}
