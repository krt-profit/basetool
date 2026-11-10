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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import de.greluc.krt.profit.basetool.backend.inventory.api.StockQuantityFragment;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/** Maintains disjoint durable quantity intervals through stock transformations. */
public final class InventoryQuantityComposition {

  private InventoryQuantityComposition() {}

  /**
   * Sums a physical composition without losing precision.
   *
   * @param fragments the composition
   * @return its total integral quantity
   */
  static long total(@NotNull Collection<StockQuantityFragment> fragments) {
    long total = 0;
    for (StockQuantityFragment fragment : fragments) {
      total = Math.addExact(total, fragment.quanta());
    }
    return total;
  }

  /**
   * Sorts intervals, coalesces adjacent parts and rejects overlapping identity.
   *
   * @param fragments the intervals to combine
   * @return the immutable canonical composition
   */
  static @NotNull List<StockQuantityFragment> canonical(
      @NotNull Collection<StockQuantityFragment> fragments) {
    List<StockQuantityFragment> sorted =
        fragments.stream()
            .sorted(
                Comparator.comparing(StockQuantityFragment::origin)
                    .thenComparingLong(StockQuantityFragment::fromQuanta))
            .toList();
    List<StockQuantityFragment> result = new ArrayList<>();
    for (StockQuantityFragment fragment : sorted) {
      if (!result.isEmpty()) {
        StockQuantityFragment previous = result.getLast();
        if (previous.origin().equals(fragment.origin())) {
          if (fragment.fromQuanta() < previous.toQuanta()) {
            throw new IllegalArgumentException("Physical quantity intervals must not overlap");
          }
          if (fragment.fromQuanta() == previous.toQuanta()) {
            result.set(
                result.size() - 1,
                new StockQuantityFragment(
                    previous.origin(), previous.fromQuanta(), fragment.toQuanta()));
            continue;
          }
        }
      }
      result.add(fragment);
    }
    return List.copyOf(result);
  }

  /**
   * Divides a composition by its deterministic leading quantity.
   *
   * @param fragments the composition
   * @param amount the leading integral quantity
   * @return disjoint leading and remaining intervals
   */
  static @NotNull Partition partition(
      @NotNull Collection<StockQuantityFragment> fragments, long amount) {
    if (amount < 0 || amount > total(fragments)) {
      throw new IllegalArgumentException("A quantity part must fit its physical stock");
    }
    List<StockQuantityFragment> taken = new ArrayList<>();
    List<StockQuantityFragment> remaining = new ArrayList<>();
    long needed = amount;
    for (StockQuantityFragment fragment : canonical(fragments)) {
      long take = Math.min(needed, fragment.quanta());
      if (take > 0) {
        taken.add(
            new StockQuantityFragment(
                fragment.origin(), fragment.fromQuanta(), fragment.fromQuanta() + take));
      }
      if (take < fragment.quanta()) {
        remaining.add(
            new StockQuantityFragment(
                fragment.origin(), fragment.fromQuanta() + take, fragment.toQuanta()));
      }
      needed -= take;
    }
    return new Partition(List.copyOf(taken), List.copyOf(remaining));
  }

  /**
   * Retains truthful identity after a loss or adds an independent genuine new quantity.
   *
   * @param fragments the existing composition
   * @param amount the resulting integral amount
   * @return the resulting immutable composition
   */
  static @NotNull List<StockQuantityFragment> resized(
      @NotNull Collection<StockQuantityFragment> fragments, long amount) {
    if (amount < 0) {
      throw new IllegalArgumentException("Physical stock cannot be negative");
    }
    long previous = total(fragments);
    if (amount <= previous) {
      return partition(fragments, previous - amount).remaining();
    }
    List<StockQuantityFragment> expanded = new ArrayList<>(fragments);
    expanded.add(new StockQuantityFragment(UUID.randomUUID(), 0, amount - previous));
    return canonical(expanded);
  }

  /**
   * One exact division of physical identity.
   *
   * @param taken the leading quantity
   * @param remaining the rest
   */
  record Partition(List<StockQuantityFragment> taken, List<StockQuantityFragment> remaining) {}
}
