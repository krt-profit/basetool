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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.NotNull;

/**
 * The tab bar of one page: the entries whose pages are its tabs, the page's own menu link first
 * (REQ-UI-026).
 *
 * <p>A tab may cover further entries: pages of the same area that have no tab of their own, such as
 * a sub-page reached from the tab's page. They show the bar with the covering tab as current.
 *
 * @param entryKeys the entry keys of the tabs in order; at least two
 * @param covered the keys of the entries each tab covers, by the key of the covering tab
 */
public record NavigationPageTabs(
    @NotNull List<String> entryKeys, @NotNull Map<String, List<String>> covered) {

  /**
   * Refuses a tab bar with fewer than two tabs or a cover that names no tab, and copies the keys.
   *
   * @param entryKeys the entry keys of the tabs
   * @param covered the covered entry keys by covering tab
   * @throws IllegalArgumentException if fewer than two keys are given or a covering key is no tab
   */
  public NavigationPageTabs {
    if (entryKeys.size() < 2) {
      throw new IllegalArgumentException("A tab bar needs at least two entry keys");
    }
    entryKeys = List.copyOf(entryKeys);
    if (!entryKeys.containsAll(covered.keySet())) {
      throw new IllegalArgumentException("A covering entry must be a tab of the bar");
    }
    covered = Map.copyOf(covered);
  }

  /**
   * A tab bar over the given entries.
   *
   * @param entryKeys the entry keys of the tabs in order
   * @return the tab bar
   */
  @NotNull
  public static NavigationPageTabs of(@NotNull String... entryKeys) {
    return new NavigationPageTabs(List.of(entryKeys), Map.of());
  }

  /**
   * This tab bar with a tab that also covers further entries.
   *
   * @param tabKey the key of the covering tab
   * @param coveredKeys the keys of the entries the tab covers
   * @return the changed tab bar
   */
  @NotNull
  public NavigationPageTabs covering(@NotNull String tabKey, @NotNull String... coveredKeys) {
    Map<String, List<String>> all = new HashMap<>(covered);
    all.put(tabKey, List.of(coveredKeys));
    return new NavigationPageTabs(entryKeys, all);
  }
}
