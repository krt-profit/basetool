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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import de.greluc.krt.profit.basetool.backend.kernel.ScuAmounts;

/** Rejects unrepresentable material quantities instead of allowing arithmetic wraparound. */
public final class MaterialQuantityArithmetic {
  private MaterialQuantityArithmetic() {}

  /**
   * Converts a nonnegative material amount to exact thousandths of a unit.
   *
   * @param amount the finite quantity
   * @return the nonnegative exact quantum count
   * @throws IllegalArgumentException when the amount cannot be represented in exact quanta
   */
  public static long quanta(double amount) {
    try {
      long result = ScuAmounts.toQuanta(amount);
      if (result < 0L) {
        throw new IllegalArgumentException("Material quantity cannot be represented exactly");
      }
      return result;
    } catch (ArithmeticException | NumberFormatException _) {
      throw new IllegalArgumentException("Material quantity cannot be represented exactly");
    }
  }

  /**
   * Adds exact quantum counts without overflow.
   *
   * @param left the first quantity
   * @param right the second quantity
   * @return their representable sum
   * @throws IllegalArgumentException when the exact sum exceeds the numeric range
   */
  public static long add(long left, long right) {
    try {
      return Math.addExact(left, right);
    } catch (ArithmeticException _) {
      throw new IllegalArgumentException("Material quantity cannot be represented exactly");
    }
  }

  /**
   * Scales whole valid quanta without overflow.
   *
   * @param units the whole material units
   * @param quantum the material's quantum size
   * @return the representable exact amount
   * @throws IllegalArgumentException when the exact product exceeds the numeric range
   */
  public static long multiply(long units, long quantum) {
    try {
      return Math.multiplyExact(units, quantum);
    } catch (ArithmeticException _) {
      throw new IllegalArgumentException("Material quantity cannot be represented exactly");
    }
  }
}
