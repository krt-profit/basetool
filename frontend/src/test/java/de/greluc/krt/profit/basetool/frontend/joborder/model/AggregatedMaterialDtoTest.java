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

package de.greluc.krt.profit.basetool.frontend.joborder.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link AggregatedMaterialDto#collectionProgressPercent()} (REQ-ORDERS-017). */
class AggregatedMaterialDtoTest {

  private static AggregatedMaterialDto row(Double ordered, Double manufactured, Double stock) {
    return new AggregatedMaterialDto(
        null, "NONE", null, 0.0, ordered, manufactured, stock, List.of(), null);
  }

  @Test
  void countsLinkedStockAndBuiltMaterialAgainstTheOrderedQuantity() {
    assertThat(row(40.0, 30.0, 6.0).collectionProgressPercent()).isEqualTo(90.0);
  }

  @Test
  void aFullyManufacturedBucketIsComplete() {
    assertThat(row(12.0, 12.0, 0.0).collectionProgressPercent()).isEqualTo(100.0);
  }

  @Test
  void neverExceedsOneHundredPercent() {
    assertThat(row(10.0, 8.0, 5.0).collectionProgressPercent()).isEqualTo(100.0);
  }

  @Test
  void treatsMissingFiguresAsZero() {
    assertThat(row(10.0, null, null).collectionProgressPercent()).isZero();
  }

  @Test
  void aBucketWithNothingOrderedHasNoProgress() {
    assertThat(row(0.0, 0.0, 3.0).collectionProgressPercent()).isZero();
    assertThat(row(null, 2.0, 3.0).collectionProgressPercent()).isZero();
  }
}
