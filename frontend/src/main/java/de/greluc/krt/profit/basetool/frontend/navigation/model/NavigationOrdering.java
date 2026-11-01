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

import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.NotNull;

/** Applies a stored order to the permitted keys of one scope (REQ-UI-032). */
public final class NavigationOrdering {

  private NavigationOrdering() {}

  /**
   * Orders the default keys by the stored ones. A stored key that is not a default key is ignored;
   * a default key the store does not know, such as one added by a later release, is placed after
   * its nearest default predecessor that is placed, or first when none is.
   *
   * @param defaults the permitted keys in their default order
   * @param stored the keys in the order the member stored
   * @return the keys in the order to render
   */
  @NotNull
  public static List<String> merge(@NotNull List<String> defaults, @NotNull List<String> stored) {
    List<String> merged =
        new ArrayList<>(stored.stream().distinct().filter(defaults::contains).toList());
    for (int i = 0; i < defaults.size(); i++) {
      String key = defaults.get(i);
      if (merged.contains(key)) {
        continue;
      }
      int at = 0;
      for (int p = i - 1; p >= 0; p--) {
        int predecessor = merged.indexOf(defaults.get(p));
        if (predecessor >= 0) {
          at = predecessor + 1;
          break;
        }
      }
      merged.add(at, key);
    }
    return merged;
  }
}
