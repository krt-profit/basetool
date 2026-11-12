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

import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Identifies a half-open interval of one genuine physical quantity in thousandths of a unit.
 *
 * @param origin the immutable quantity identity
 * @param fromQuanta the inclusive start
 * @param toQuanta the exclusive end
 */
public record StockQuantityFragment(@NotNull UUID origin, long fromQuanta, long toQuanta) {

  /** Rejects empty or negative physical quantity intervals. */
  public StockQuantityFragment {
    Objects.requireNonNull(origin);
    if (fromQuanta < 0 || toQuanta <= fromQuanta) {
      throw new IllegalArgumentException("A physical quantity interval must be positive");
    }
  }

  /**
   * Returns the represented amount in thousandths of a unit.
   *
   * @return the positive interval length
   */
  public long quanta() {
    return toQuanta - fromQuanta;
  }
}
