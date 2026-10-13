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

/** Why a raising unit allows a need to be bought (REQ-SUP-004). */
public enum PurchaseReason {

  /** Farming the material takes too long. */
  HIGH_FARM_EFFORT,

  /** The material is hard to come by. */
  RARE_SOURCE,

  /** The unit cannot wait. */
  TIME_CRITICAL,

  /** Any other reason. */
  OTHER
}
