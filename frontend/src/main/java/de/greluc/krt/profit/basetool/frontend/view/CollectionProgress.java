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

package de.greluc.krt.profit.basetool.frontend.view;

import java.util.Collection;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

/**
 * The delivered share of the stock earmarked to a job order, drawn as the collection progress bar
 * of the item and material collection pages (REQ-UI-027).
 *
 * @param delivered the earmarked amount whose slice is marked delivered
 * @param total the earmarked amount in total
 */
public record CollectionProgress(double delivered, double total) {

  /**
   * Sums the earmarked and the delivered amount over the given entries.
   *
   * @param <T> the entry type
   * @param entries the earmarked entries
   * @param amount the earmarked amount of one entry
   * @param delivered whether one entry's slice is marked delivered
   * @return the progress over all entries; zero of zero for no entries
   */
  @NotNull
  @Contract(pure = true)
  public static <T> CollectionProgress of(
      @NotNull Collection<? extends T> entries,
      @NotNull ToDoubleFunction<? super T> amount,
      @NotNull Predicate<? super T> delivered) {
    double deliveredSum = 0;
    double totalSum = 0;
    for (T entry : entries) {
      double value = amount.applyAsDouble(entry);
      totalSum += value;
      if (delivered.test(entry)) {
        deliveredSum += value;
      }
    }
    return new CollectionProgress(deliveredSum, totalSum);
  }

  /**
   * Adds another progress to this one.
   *
   * @param other the progress to add
   * @return the sum of both delivered and both total amounts
   */
  @NotNull
  @Contract(pure = true)
  public CollectionProgress plus(@NotNull CollectionProgress other) {
    return new CollectionProgress(delivered + other.delivered, total + other.total);
  }

  /**
   * The delivered share in whole percent, rounded half up and capped at 100.
   *
   * @return the share from 0 to 100; 0 when nothing is earmarked
   */
  @Contract(pure = true)
  public int percent() {
    if (total <= 0) {
      return 0;
    }
    return (int) Math.round(Math.min(delivered, total) * 100 / total);
  }
}
