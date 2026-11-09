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

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The parts of a refinery order one exchange write sets; a {@code null} part is left as it is
 * (REQ-XCH-052). Any game-data part raises the game-data counter once, the finances part the
 * finances counter (REQ-REFINERY-029).
 *
 * @param locationId the refinery's location, or {@code null}
 * @param method the refining method, or {@code null}
 * @param timer the run's start and length, or {@code null}
 * @param status the status, or {@code null}
 * @param finances the three money fields, or {@code null}
 * @param goods every good the order holds afterwards, or {@code null}: a good with an id the order
 *     has is changed, one with an id it lacks is inserted under that id, one without an id is
 *     added, and every other good is removed
 */
public record RefineryExchangeEdit(
    @Nullable UUID locationId,
    @Nullable Method method,
    @Nullable Timer timer,
    @Nullable RefineryOrderStatus status,
    @Nullable Finances finances,
    @Nullable @Unmodifiable List<Good> goods) {

  /**
   * Whether the edit writes the game-data section.
   *
   * @return {@code true} when it carries a location, method, timer, status or goods
   */
  public boolean touchesGameData() {
    return locationId != null || method != null || timer != null || status != null || goods != null;
  }

  /**
   * The refining method an edit sets.
   *
   * @param id the method, or {@code null} for none
   */
  public record Method(@Nullable UUID id) {}

  /**
   * The run's start and length.
   *
   * @param startedAt when the run started, or {@code null}
   * @param durationMinutes its length in minutes, or {@code null}
   */
  public record Timer(@Nullable Instant startedAt, @Nullable Long durationMinutes) {}

  /**
   * The money fields, in aUEC; {@code null} and 0 both store none.
   *
   * @param expenses the refining costs
   * @param otherExpenses further costs
   * @param oreSales revenue from selling raw ore
   */
  public record Finances(
      @Nullable Double expenses, @Nullable Double otherExpenses, @Nullable Double oreSales) {}

  /**
   * One good; its output follows from the input ({@link RefineryExchangeRules#output}).
   *
   * @param id the good's row id, or {@code null} for a new row
   * @param inputMaterialId the raw input material
   * @param inputQuantity the input amount in units, 100 to the SCU
   * @param outputQuantity the yield in units, 100 to the SCU
   * @param quality the quality from 0 to 1000
   */
  public record Good(
      @Nullable UUID id,
      @NotNull UUID inputMaterialId,
      int inputQuantity,
      int outputQuantity,
      int quality) {}
}
