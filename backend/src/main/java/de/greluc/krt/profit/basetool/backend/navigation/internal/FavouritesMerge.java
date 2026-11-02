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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.NotNull;

/** The favourites rule of an account merge (REQ-UI-032, REQ-SEC-046). */
final class FavouritesMerge {

  private FavouritesMerge() {}

  /**
   * Unions two accounts' favourites: the target's first, then the source's, without duplicates and
   * cut at {@link NavigationLimits#MAX_FAVOURITES}.
   *
   * @param target the surviving account's favourites in order
   * @param source the emptied account's favourites in order
   * @return the merged favourites
   */
  static @NotNull List<String> union(@NotNull List<String> target, @NotNull List<String> source) {
    Set<String> merged = new LinkedHashSet<>(target);
    merged.addAll(source);
    return merged.stream().limit(NavigationLimits.MAX_FAVOURITES).toList();
  }
}
