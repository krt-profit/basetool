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

package de.greluc.krt.profit.basetool.backend.inventory.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/** Exact operations on known physical quantity intervals, independent of storage placement. */
public final class StockQuantityFragments {
  private StockQuantityFragments() {}

  /**
   * Sums interval quantities without arithmetic wraparound.
   *
   * @param fragments disjoint known physical intervals
   * @return total thousandths of a unit
   */
  public static long total(Collection<StockQuantityFragment> fragments) {
    return fragments.stream().mapToLong(StockQuantityFragment::quanta).reduce(0L, Math::addExact);
  }

  /**
   * Takes the deterministic leading quantity of an interval set.
   *
   * @param fragments disjoint known intervals
   * @param quantity the exact quantity to take
   * @return prefix in the supplied immutable fact order
   */
  public static List<StockQuantityFragment> prefix(
      Collection<StockQuantityFragment> fragments, long quantity) {
    if (quantity < 0L || quantity > total(fragments)) {
      throw new IllegalArgumentException("A physical quantity prefix must fit its known intervals");
    }
    long remaining = quantity;
    List<StockQuantityFragment> selected = new ArrayList<>();
    for (StockQuantityFragment fragment : fragments) {
      long take = Math.min(remaining, fragment.quanta());
      if (take > 0L) {
        selected.add(
            new StockQuantityFragment(
                fragment.origin(),
                fragment.fromQuanta(),
                Math.addExact(fragment.fromQuanta(), take)));
        remaining -= take;
      }
    }
    return List.copyOf(selected);
  }

  /**
   * Removes overlapping known intervals while retaining every other physical quantity.
   *
   * @param quantity the disjoint original intervals
   * @param removed the intervals to subtract
   * @return surviving intervals in the supplied immutable fact order
   */
  public static List<StockQuantityFragment> subtract(
      Collection<StockQuantityFragment> quantity, Collection<StockQuantityFragment> removed) {
    List<StockQuantityFragment> result = new ArrayList<>();
    List<StockQuantityFragment> blocked = ordered(removed);
    for (StockQuantityFragment candidate : quantity) {
      long cursor = candidate.fromQuanta();
      for (StockQuantityFragment used : blocked) {
        if (!used.origin().equals(candidate.origin()) || used.toQuanta() <= cursor) {
          continue;
        }
        if (used.fromQuanta() >= candidate.toQuanta()) {
          break;
        }
        if (used.fromQuanta() > cursor) {
          result.add(new StockQuantityFragment(candidate.origin(), cursor, used.fromQuanta()));
        }
        cursor = Math.max(cursor, used.toQuanta());
        if (cursor >= candidate.toQuanta()) {
          break;
        }
      }
      if (cursor < candidate.toQuanta()) {
        result.add(new StockQuantityFragment(candidate.origin(), cursor, candidate.toQuanta()));
      }
    }
    return List.copyOf(result);
  }

  private static List<StockQuantityFragment> ordered(Collection<StockQuantityFragment> fragments) {
    return fragments.stream()
        .sorted(
            Comparator.comparing(StockQuantityFragment::origin)
                .thenComparingLong(StockQuantityFragment::fromQuanta))
        .toList();
  }
}
