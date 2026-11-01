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

import java.util.Locale;
import org.jetbrains.annotations.NotNull;

/** The status filters a widget instance offers (REQ-UI-034). */
public enum DashboardStatus {

  /** Only unread entries. */
  UNREAD,

  /** Every entry. */
  ALL;

  /**
   * The key suffix of the status label in the message bundles.
   *
   * @return the lower-case name
   */
  public @NotNull String key() {
    return name().toLowerCase(Locale.ROOT);
  }
}
