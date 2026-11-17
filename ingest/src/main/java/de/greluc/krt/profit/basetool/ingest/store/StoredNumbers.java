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

package de.greluc.krt.profit.basetool.ingest.store;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

/** Reads the whole numbers the gateway keeps in Redis strings: counters and running totals. */
final class StoredNumbers {

  /** Not instantiable. */
  private StoredNumbers() {}

  /**
   * Parses a stored value.
   *
   * @param value the value as Redis returned it, or {@code null} for a missing key
   * @return the number, or {@code null} when the key is missing or holds no whole number
   */
  @Contract("null -> null")
  static @Nullable Long parse(@Nullable String value) {
    if (value == null) {
      return null;
    }
    try {
      return Long.valueOf(value);
    } catch (NumberFormatException _) {
      return null;
    }
  }
}
