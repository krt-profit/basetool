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

package de.greluc.krt.profit.basetool.backend.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Verifies the {@code @PrePersist}/{@code @PreUpdate} rounding hooks on the material-amount
 * entities normalise SCU amounts to three decimals using commercial rounding ({@code HALF_UP}), the
 * same way {@link InventoryItem} does at its persistence chokepoint. This guarantees no row is
 * stored with more than three decimals regardless of which write path produced the value (order
 * creation, handover decrement, claim, refinery store). Lives in the {@code model} package so it
 * can invoke the package-private lifecycle callbacks directly without a full persistence
 * round-trip.
 */
class MaterialAmountRoundingTest {

  /** A job-order material requirement rounds its amount to three decimals on persist. */
  @Test
  void jobOrderMaterialRoundsAmountToThreeDecimals() {
    JobOrderMaterial mat = new JobOrderMaterial();

    mat.setAmount(10.12349);
    mat.roundAmountToScuScale();
    assertEquals(10.123, mat.getAmount(), 1e-9);

    mat.setAmount(0.0015);
    mat.roundAmountToScuScale();
    assertEquals(0.002, mat.getAmount(), 1e-9);
  }

  /** A material claim rounds its committed amount to three decimals on persist. */
  @Test
  void materialClaimRoundsAmountToThreeDecimals() {
    MaterialClaim claim = new MaterialClaim();
    claim.setAmount(1.23456);
    claim.roundAmountToScuScale();
    assertEquals(1.235, claim.getAmount(), 1e-9);
  }

  /** A handover line rounds its operator-entered amount, carrying the half-up rule across .9995. */
  @Test
  void jobOrderHandoverItemRoundsAmountToThreeDecimals() {
    JobOrderHandoverItem handover = new JobOrderHandoverItem();
    handover.setAmount(12.9995);
    handover.roundAmountToScuScale();
    assertEquals(13.0, handover.getAmount(), 1e-9);
  }

  /** Whole-number (PIECE) amounts are untouched and {@code null} is left as-is for @NotNull. */
  @Test
  void roundingLeavesWholeAndNullAmountsUnchanged() {
    JobOrderMaterial whole = new JobOrderMaterial();
    whole.setAmount(5.0);
    whole.roundAmountToScuScale();
    assertEquals(5.0, whole.getAmount(), 1e-9);

    MaterialClaim nullAmount = new MaterialClaim();
    nullAmount.setAmount(null);
    nullAmount.roundAmountToScuScale();
    assertNull(nullAmount.getAmount());
  }
}
