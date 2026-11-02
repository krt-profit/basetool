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

package de.greluc.krt.profit.basetool.frontend.navigation.model;

import java.util.Locale;
import org.jetbrains.annotations.NotNull;

/** How the navigation sits on a desktop (REQ-UI-026); the stored choice of the appearance. */
public enum DesktopMode {

  /** The 320 px overlay drawer, the default. */
  DRAWER,

  /** The icon rail with one entry per group, from 1025 px. */
  RAIL,

  /** The pinned labelled sidebar from 1280 px; the rail between 1025 and 1279 px. */
  PINNED;

  /**
   * The message key of the switch option.
   *
   * @return the key under {@code nav.mode}
   */
  @NotNull
  public String labelKey() {
    return "nav.mode." + attribute();
  }

  /**
   * The value of the {@code data-nav-mode} attribute the style sheet and the scripts key on.
   *
   * @return the lower-case name
   */
  @NotNull
  public String attribute() {
    return name().toLowerCase(Locale.ROOT);
  }
}
