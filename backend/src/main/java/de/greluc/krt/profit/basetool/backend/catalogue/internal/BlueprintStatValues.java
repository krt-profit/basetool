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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

/**
 * The rules both stat sources share when they turn a raw value into a stored blueprint stat base
 * value (REQ-P4K-030).
 */
final class BlueprintStatValues {

  /** Non-instantiable holder of static helpers. */
  private BlueprintStatValues() {}

  /**
   * Converts the damage multiplier an armour lets through into the stored damage mitigation.
   *
   * @param multiplier the raw multiplier, may be {@code null}
   * @return {@code 1 − multiplier}, or {@code null} without a multiplier
   */
  @Nullable
  @Contract("null -> null; !null -> !null")
  static Double mitigation(@Nullable Double multiplier) {
    return multiplier == null ? null : 1.0 - multiplier;
  }

  /**
   * Whether a value is stored: quality cannot change a zero base, and a non-finite value is no
   * fact.
   *
   * @param value the value, may be {@code null}
   * @return {@code true} for a finite value other than zero
   */
  @Contract("null -> false")
  static boolean isStored(@Nullable Double value) {
    return value != null && value != 0.0 && Double.isFinite(value);
  }
}
