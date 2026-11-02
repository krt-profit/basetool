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

package de.greluc.krt.profit.basetool.backend.navigation.internal;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.NotNull;

/** The hidden-pages rule of an account merge (REQ-UI-032, REQ-SEC-046). */
final class HiddenMerge {

  private HiddenMerge() {}

  /**
   * Unions two accounts' hidden pages and removes every page that is a favourite after the merge.
   *
   * @param target the surviving account's hidden keys
   * @param source the emptied account's hidden keys
   * @param mergedFavourites the favourites of the merged account
   * @return the merged hidden keys
   */
  static @NotNull Set<String> union(
      @NotNull Collection<String> target,
      @NotNull Collection<String> source,
      @NotNull List<String> mergedFavourites) {
    Set<String> merged = new LinkedHashSet<>(target);
    merged.addAll(source);
    merged.removeAll(Set.copyOf(mergedFavourites));
    return merged;
  }
}
