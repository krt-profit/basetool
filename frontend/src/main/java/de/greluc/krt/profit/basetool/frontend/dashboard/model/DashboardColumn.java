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

import org.jetbrains.annotations.NotNull;

/** The column of the two-column dashboard a widget instance sits in (REQ-UI-034). */
public enum DashboardColumn {

  /** The wide column, two thirds of the width. */
  WIDE,

  /** The narrow column, one third of the width. */
  NARROW;

  /**
   * The column an instance moves to when its column is switched.
   *
   * @return the other column
   */
  public @NotNull DashboardColumn other() {
    return this == WIDE ? NARROW : WIDE;
  }
}
