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

package de.greluc.krt.profit.basetool.frontend.kernel.layout;

import java.util.Set;
import org.jetbrains.annotations.NotNull;

/**
 * Names the menu pages the caller hid, so the quick access can leave them and their tabs out
 * (REQ-UI-032). Implemented by the navigation domain, which owns the preferences.
 */
public interface QuickAccessHiddenEntries {

  /**
   * The entry keys that leave the quick access: every page the caller hid and each tab of it.
   *
   * @return the keys; empty when nothing is hidden or the preferences cannot be read
   */
  @NotNull
  Set<String> keys();
}
