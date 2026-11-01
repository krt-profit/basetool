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

package de.greluc.krt.profit.basetool.frontend.dashboard.model;

/** The kinds of option a widget type may offer (REQ-UI-034). */
public enum DashboardOption {

  /** The org unit, empty for "follows the active context". */
  UNIT,

  /** The number of days ahead. */
  DAYS,

  /** The time range in days, sent in the same field as {@link #DAYS}. */
  RANGE,

  /** The number of rows shown. */
  ROWS,

  /** The status filter. */
  STATUS
}
