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

package de.greluc.krt.profit.basetool.backend.catalogue.api;

/** The unit a blueprint stat's base value is expressed in (REQ-DATA-027). */
public enum BlueprintStatUnit {
  /** A plain number without a unit, e.g. integrity or damage per shot. */
  NONE,
  /** A fraction of one, shown as a percentage. */
  PERCENT,
  /** Degrees Celsius. */
  CELSIUS,
  /** Metres. */
  METER,
  /** Metres per second. */
  METER_PER_SECOND,
  /** Rounds per minute. */
  ROUNDS_PER_MINUTE
}
