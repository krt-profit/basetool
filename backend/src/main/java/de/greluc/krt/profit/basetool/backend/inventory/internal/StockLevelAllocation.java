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

import de.greluc.krt.profit.basetool.backend.kernel.ScuAmounts;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Allocates a unit's free stock of one material to that material's minimum stock levels,
 * exclusively and from the highest tier floor down, on plain rows (REQ-INV-067, ADR-0302).
 */
public final class StockLevelAllocation {

  /** The floor an untiered level counts from; it ranks below every tier. */
  private static final int UNTIERED_FLOOR = 0;

  /** Serves the highest floor first, a tiered level before the untiered one, then by id. */
  private static final Comparator<Level> SERVING_ORDER =
      Comparator.comparingInt(Level::rank).reversed().thenComparing(Level::id);

  /** Non-instantiable function holder. */
  private StockLevelAllocation() {}

  /**
   * One minimum stock level of the material.
   *
   * @param id the level
   * @param floor the minimum quality of its tier, {@code null} for an untiered level
   * @param minimum the amount below which the level is low
   * @param target the amount the level is filled up to before the next level is served
   */
  public record Level(@NotNull UUID id, @Nullable Integer floor, double minimum, double target) {

    private int rank() {
      return floor == null ? UNTIERED_FLOOR - 1 : floor;
    }

    private int countsFrom() {
      return floor == null ? UNTIERED_FLOOR : floor;
    }
  }

  /**
   * The summed stock of one quality in the unit's pool, with its earmarks and markers.
   *
   * @param quality the stock quality
   * @param amount the summed amount
   * @param earmarked the summed job-order, mission and donation earmarks on it
   * @param personal whether the rows are personal
   * @param stolen whether the rows are marked „gestohlen"
   */
  public record Lot(
      int quality, double amount, double earmarked, boolean personal, boolean stolen) {

    private double free() {
      return personal || stolen ? 0.0 : Math.max(0.0, amount - earmarked);
    }
  }

  /**
   * The figures of one level.
   *
   * @param allocated the free stock allocated to the level
   * @param shortfall the target minus the allocated stock, never below zero
   * @param fillRatio the allocated stock divided by the minimum
   * @param low whether the allocated stock is below the minimum
   */
  public record Fill(double allocated, double shortfall, double fillRatio, boolean low) {}

  /**
   * Allocates the lots to the levels: each level takes the not yet allocated free stock at or above
   * its floor up to its target, and the last level served takes the whole remainder.
   *
   * @param levels the levels of one material in one unit
   * @param lots the unit's stock of that material
   * @return the figures by level id
   */
  public static @NotNull Map<UUID, Fill> allocate(
      @NotNull List<Level> levels, @NotNull List<Lot> lots) {
    List<Level> ordered = levels.stream().sorted(SERVING_ORDER).toList();
    Map<UUID, Fill> fills = new HashMap<>();
    double taken = 0.0;
    for (int i = 0; i < ordered.size(); i++) {
      Level level = ordered.get(i);
      double available = Math.max(0.0, freeFrom(lots, level.countsFrom()) - taken);
      boolean last = i == ordered.size() - 1;
      double allocated =
          ScuAmounts.roundToScuScale(last ? available : Math.min(level.target(), available));
      taken += allocated;
      fills.put(level.id(), fill(level, allocated));
    }
    return fills;
  }

  private static double freeFrom(@NotNull List<Lot> lots, int floor) {
    return lots.stream().filter(lot -> lot.quality() >= floor).mapToDouble(Lot::free).sum();
  }

  private static @NotNull Fill fill(@NotNull Level level, double allocated) {
    return new Fill(
        allocated,
        ScuAmounts.roundToScuScale(Math.max(0.0, level.target() - allocated)),
        allocated / level.minimum(),
        allocated < level.minimum());
  }
}
