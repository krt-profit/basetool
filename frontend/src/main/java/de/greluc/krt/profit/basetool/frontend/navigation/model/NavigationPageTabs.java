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

import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * The tab bar of one page: the entries whose pages are its tabs, the page's own menu link first
 * (REQ-UI-026).
 *
 * @param entryKeys the entry keys of the tabs in order; at least two
 */
public record NavigationPageTabs(@NotNull List<String> entryKeys) {

  /**
   * Refuses a tab bar with fewer than two tabs and copies the keys.
   *
   * @param entryKeys the entry keys of the tabs
   * @throws IllegalArgumentException if fewer than two keys are given
   */
  public NavigationPageTabs {
    if (entryKeys.size() < 2) {
      throw new IllegalArgumentException("A tab bar needs at least two entry keys");
    }
    entryKeys = List.copyOf(entryKeys);
  }

  /**
   * A tab bar over the given entries.
   *
   * @param entryKeys the entry keys of the tabs in order
   * @return the tab bar
   */
  @NotNull
  public static NavigationPageTabs of(@NotNull String... entryKeys) {
    return new NavigationPageTabs(List.of(entryKeys));
  }
}
