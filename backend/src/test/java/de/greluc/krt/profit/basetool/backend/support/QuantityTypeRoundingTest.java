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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link QuantityTypeRounding}, pinning the shared PIECE-vs-SCU rounding contract
 * that {@code JobOrderItemService} and {@code BlueprintCraftabilityService} both depend on (a
 * divergence would let a blueprint's craftable count drift from the order it fulfils).
 */
class QuantityTypeRoundingTest {

  @Test
  void pieceQuantityType_roundsToWholeUnit() {
    assertThat(QuantityTypeRounding.roundForQuantityType(1.7999999999999998, QuantityType.PIECE))
        .isEqualTo(2.0);
  }

  @Test
  void scuQuantityType_roundsToThreeDecimals() {
    // 0.36 * 5 == 1.7999999999999998 in binary double; must clean to 1.8.
    assertThat(QuantityTypeRounding.roundForQuantityType(0.36 * 5, QuantityType.SCU))
        .isEqualTo(1.8);
  }

  @Test
  void nullQuantityType_treatedAsScu() {
    assertThat(QuantityTypeRounding.roundForQuantityType(1.23456, (QuantityType) null))
        .isEqualTo(1.235);
  }

  @Test
  void materialOverload_readsQuantityTypeOffMaterial() {
    Material piece = mock(Material.class);
    when(piece.getQuantityType()).thenReturn(QuantityType.PIECE);
    Material scu = mock(Material.class);
    when(scu.getQuantityType()).thenReturn(QuantityType.SCU);

    assertThat(QuantityTypeRounding.roundForQuantityType(2.4, piece)).isEqualTo(2.0);
    assertThat(QuantityTypeRounding.roundForQuantityType(1.23456, scu)).isEqualTo(1.235);
  }

  @Test
  void nullMaterial_treatedAsScu() {
    assertThat(QuantityTypeRounding.roundForQuantityType(1.23456, (Material) null))
        .isEqualTo(1.235);
  }
}
