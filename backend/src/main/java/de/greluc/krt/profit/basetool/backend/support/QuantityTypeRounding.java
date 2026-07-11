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
import org.jetbrains.annotations.Nullable;

/**
 * Rounds a derived material quantity to the precision its {@link QuantityType} can express: a whole
 * unit for a {@link QuantityType#PIECE} material, three decimals (the {@code 0.001} SCU input step
 * used throughout the UI) otherwise. A {@code null} type — or a {@code null} {@link Material} — is
 * treated as SCU, the {@link Material#getQuantityType()} default.
 *
 * <p>Extracted from the two byte-identical, comment-synchronized private copies that {@code
 * JobOrderItemService} and {@code BlueprintCraftabilityService} each carried (audit "Ingredient
 * MaterialBridge" dedup): the craftability calc and the job-order requirement snapshot must round a
 * shared PIECE material identically or a blueprint's craftable count silently diverges from the
 * order it fulfils, so this is now the single source of that rounding. Lives in the dependency-leaf
 * {@code support} package — it depends only on {@code model} ({@link Material} / {@link
 * QuantityType}).
 */
public final class QuantityTypeRounding {

  /** Scale for the SCU rounding step ({@code 0.001}); {@code Math.round(q * scale) / scale}. */
  private static final double SCU_ROUNDING_SCALE = 1000.0;

  private QuantityTypeRounding() {}

  /**
   * Rounds a quantity to the precision the given quantity type can express, eliminating the binary
   * floating-point artefacts that a {@code perUnit * amount} product introduces (e.g. {@code 0.36 *
   * 5} yielding {@code 1.7999999999999998} instead of {@code 1.8}).
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
   * Convenience overload reading the quantity type off a material; a {@code null} material is
   * treated as SCU, preserving the job-order snapshot's original null-handling.
   *
   * @param quantity the raw, possibly noisy quantity
   * @param material the material whose quantity type selects the rounding granularity, or {@code
   *     null} (treated as SCU)
   * @return the rounded quantity, in the material's own unit
   */
  public static double roundForQuantityType(double quantity, @Nullable Material material) {
    return roundForQuantityType(quantity, material == null ? null : material.getQuantityType());
  }
}
