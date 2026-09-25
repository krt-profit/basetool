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

package de.greluc.krt.profit.basetool.backend.support;

import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialDto;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

/**
 * Rounds a derived material quantity to the precision its {@link QuantityType} can express: a whole
 * unit for {@link QuantityType#PIECE}, three decimals otherwise. A {@code null} type or {@link
 * Material} counts as SCU.
 *
 * <p>Craftability and job-order snapshots must both round through this class.
 */
public final class QuantityTypeRounding {

  /** Scale for the SCU rounding step ({@code 0.001}); {@code Math.round(q * scale) / scale}. */
  private static final double SCU_ROUNDING_SCALE = 1000.0;

  private QuantityTypeRounding() {}

  /**
   * Rounds a quantity to the precision the given quantity type can express, removing floating-point
   * artefacts such as {@code 1.7999999999999998}.
   *
   * @param quantity the raw, possibly noisy quantity
   * @param quantityType the material's quantity type, or {@code null} (treated as SCU)
   * @return the rounded quantity, in the material's own unit
   */
  public static double roundForQuantityType(double quantity, @Nullable QuantityType quantityType) {
    if (quantityType == QuantityType.PIECE) {
      return Math.round(quantity);
    }
    return Math.round(quantity * SCU_ROUNDING_SCALE) / SCU_ROUNDING_SCALE;
  }

  /**
   * Overload reading the quantity type off a material; a {@code null} material is treated as SCU.
   *
   * @param quantity the raw, possibly noisy quantity
   * @param material the material selecting the rounding granularity, or {@code null} (treated as
   *     SCU)
   * @return the rounded quantity, in the material's own unit
   */
  public static double roundForQuantityType(double quantity, @Nullable Material material) {
    return roundForQuantityType(quantity, material == null ? null : material.getQuantityType());
  }

  /**
   * Overload for a mapped material whose quantity type is text; an absent, blank or unrecognised
   * value is treated as SCU.
   *
   * @param quantity the raw, possibly noisy quantity
   * @param material the mapped material selecting the granularity, or {@code null} (treated as SCU)
   * @return the rounded quantity, in the material's own unit
   */
  public static double roundForQuantityType(double quantity, @Nullable MaterialDto material) {
    return roundForQuantityType(
        quantity, material == null ? null : parseQuantityType(material.quantityType()));
  }

  /**
   * Parses a DTO's textual quantity type, tolerating an absent or unknown value.
   *
   * @param quantityType the textual quantity type, possibly {@code null}
   * @return the parsed type, or {@code null} to mean the SCU default
   */
  @Contract("null -> null")
  @Nullable
  private static QuantityType parseQuantityType(@Nullable String quantityType) {
    if (quantityType == null) {
      return null;
    }
    try {
      return QuantityType.valueOf(quantityType);
    } catch (IllegalArgumentException unknownType) {
      return null;
    }
  }
}
