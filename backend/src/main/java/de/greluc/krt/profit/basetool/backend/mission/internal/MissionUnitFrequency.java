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

package de.greluc.krt.profit.basetool.backend.mission.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

/** Validation shared by mission units and template unit plans. */
final class MissionUnitFrequency {

  private static final double MIN_FREQUENCY = 100.00;
  private static final double MAX_FREQUENCY = 999.99;

  private MissionUnitFrequency() {}

  @Contract("null -> null; !null -> !null")
  static @Nullable Double normalize(@Nullable Double frequency) {
    if (frequency == null) {
      return null;
    }
    double rounded = BigDecimal.valueOf(frequency).setScale(2, RoundingMode.HALF_UP).doubleValue();
    if (rounded < MIN_FREQUENCY || rounded > MAX_FREQUENCY) {
      throw new IllegalArgumentException("Frequency must be between 100.00 and 999.99");
    }
    return rounded;
  }
}
