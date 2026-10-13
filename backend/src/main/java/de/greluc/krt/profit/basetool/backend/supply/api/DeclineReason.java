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

package de.greluc.krt.profit.basetool.backend.supply.api;

/** Why a receiving unit declines a pledge (REQ-SUP-010). */
public enum DeclineReason {

  /** The pledged item is not what was agreed. */
  WRONG_ITEM,

  /** The unit needs nothing of it. */
  NOT_NEEDED,

  /** The quality is below the need's tier. */
  QUALITY_TOO_LOW,

  /** The donor was not there for the handover. */
  DONOR_ABSENT,

  /** Any other reason. */
  OTHER
}
