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

package de.greluc.krt.profit.basetool.backend.refinery.api;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The money of one refinery order as the finance views of an operation read it, with an absent
 * amount counted as zero.
 *
 * @param missionId the mission the order is linked to, or {@code null} for an unlinked order
 * @param ownerId the owning member, or {@code null} for an ownerless order
 * @param oreSales the ore sales
 * @param expenses the refining expenses
 * @param otherExpenses the other expenses
 */
public record RefineryOrderFigures(
    @Nullable UUID missionId,
    @Nullable UUID ownerId,
    double oreSales,
    double expenses,
    double otherExpenses) {

  /**
   * The order's profit.
   *
   * @return {@code oreSales − expenses − otherExpenses}
   */
  public double profit() {
    return oreSales - expenses - otherExpenses;
  }

  /**
   * The costs the owner advanced.
   *
   * @return {@code expenses + otherExpenses}
   */
  public double advancedCosts() {
    return expenses + otherExpenses;
  }

  /**
   * Builds the figures of an order, counting an absent amount as zero.
   *
   * @param missionId the linked mission, or {@code null}
   * @param ownerId the owning member, or {@code null}
   * @param oreSales the ore sales, or {@code null}
   * @param expenses the refining expenses, or {@code null}
   * @param otherExpenses the other expenses, or {@code null}
   * @return the figures
   */
  public static @NotNull RefineryOrderFigures of(
      @Nullable UUID missionId,
      @Nullable UUID ownerId,
      @Nullable Double oreSales,
      @Nullable Double expenses,
      @Nullable Double otherExpenses) {
    return new RefineryOrderFigures(
        missionId,
        ownerId,
        oreSales != null ? oreSales : 0d,
        expenses != null ? expenses : 0d,
        otherExpenses != null ? otherExpenses : 0d);
  }
}
