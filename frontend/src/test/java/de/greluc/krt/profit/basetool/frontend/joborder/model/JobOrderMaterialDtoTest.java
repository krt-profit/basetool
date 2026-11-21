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
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link JobOrderMaterialDto#collectionProgressPercent()} (REQ-ORDERS-017). */
class JobOrderMaterialDtoTest {

  private static JobOrderMaterialDto line(Double requested, double handedOver, Double stock) {
    double remaining = requested == null ? 0.0 : Math.max(0.0, requested - handedOver);
    return new JobOrderMaterialDto(
        UUID.randomUUID(),
        null,
        null,
        null,
        requested,
        handedOver,
        remaining,
        stock,
        List.of(),
        null,
        1L);
  }

  @Test
  void countsLinkedStockAndHandedOverAmountAgainstTheRequestedAmount() {
    assertThat(line(10.0, 4.0, 3.0).collectionProgressPercent()).isEqualTo(70.0);
  }

  @Test
  void aFullyHandedOverLineIsComplete() {
    assertThat(line(10.0, 10.0, 0.0).collectionProgressPercent()).isEqualTo(100.0);
  }

  @Test
  void neverExceedsOneHundredPercent() {
    assertThat(line(10.0, 8.0, 5.0).collectionProgressPercent()).isEqualTo(100.0);
  }

  @Test
  void treatsMissingStockAsZero() {
    assertThat(line(8.0, 2.0, null).collectionProgressPercent()).isEqualTo(25.0);
  }

  @Test
  void aLineWithNothingRequestedHasNoProgress() {
    assertThat(line(0.0, 0.0, 3.0).collectionProgressPercent()).isZero();
    assertThat(line(null, 0.0, 3.0).collectionProgressPercent()).isZero();
  }
}
