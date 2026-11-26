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

package de.greluc.krt.profit.basetool.frontend.inventory.web;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.inventory.model.GroupedInventoryDto;
import de.greluc.krt.profit.basetool.frontend.inventory.model.InventorySaleValueDto;
import de.greluc.krt.profit.basetool.frontend.inventory.model.InventorySelectionSaleValueDto;
import de.greluc.krt.profit.basetool.frontend.inventory.model.SalePriceBasis;
import de.greluc.krt.profit.basetool.frontend.inventory.web.LagerSaleValueSummary.Scope;
import de.greluc.krt.profit.basetool.frontend.kernel.model.InventoryGameItemReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.MaterialReferenceDto;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LagerSaleValueSummary} (REQ-INV-072): the total of the shown groups, the
 * shared price basis, the unpriced material count, the stolen note, the filter scope, no sum row
 * without a valued group, and the selection's row.
 */
class LagerSaleValueSummaryTest {

  @Test
  void sumsTheShownValuesAndCountsTheUnpricedMaterials() {
    LagerSaleValueSummary summary =
        LagerSaleValueSummary.of(
            List.of(
                material("324675", 0.0),
                material(null, 0.0),
                material("172200", 0.0),
                item(),
                material(null, 0.0)),
            false);

    assertThat(summary)
        .isEqualTo(
            new LagerSaleValueSummary(
                new BigDecimal("496875"), SalePriceBasis.LOWEST, 2, false, Scope.SHOWN));
  }

  @Test
  void notesStolenStockAndTheActiveFilter() {
    LagerSaleValueSummary summary =
        LagerSaleValueSummary.of(List.of(material("216000", 40.25), material("0", 5.0)), true);

    assertThat(summary)
        .isEqualTo(
            new LagerSaleValueSummary(
                new BigDecimal("216000"), SalePriceBasis.LOWEST, 0, true, Scope.FILTERED));
  }

  @Test
  void theMarkedEntriesMakeASelectionRowEvenAtZero() {
    LagerSaleValueSummary summary =
        LagerSaleValueSummary.ofSelection(
            new InventorySelectionSaleValueDto(SalePriceBasis.HIGHEST, BigDecimal.ZERO, 1, true));

    assertThat(summary)
        .isEqualTo(
            new LagerSaleValueSummary(
                BigDecimal.ZERO, SalePriceBasis.HIGHEST, 1, true, Scope.SELECTION));
    assertThat(summary.scope().getLabelKey()).isEqualTo("inventory.saleValue.totalSelection");
  }

  @Test
  void withoutAPositiveValueThereIsNoSumRow() {
    assertThat(LagerSaleValueSummary.of(List.of(), false)).isNull();
    assertThat(LagerSaleValueSummary.of(List.of(material(null, 0.0), material("0", 3.0)), false))
        .isNull();
  }

  private static GroupedInventoryDto material(String value, double stolen) {
    InventorySaleValueDto saleValue =
        value == null
            ? null
            : new InventorySaleValueDto(
                SalePriceBasis.LOWEST,
                new BigDecimal("100"),
                "Terminal",
                2,
                new BigDecimal(value),
                stolen);
    return new GroupedInventoryDto(
        new MaterialReferenceDto(UUID.randomUUID(), "Material", "SCU"),
        null,
        1.0,
        500.0,
        500,
        List.of(),
        false,
        saleValue);
  }

  private static GroupedInventoryDto item() {
    return new GroupedInventoryDto(
        null,
        new InventoryGameItemReferenceDto(UUID.randomUUID(), "Drive", null, null),
        1.0,
        null,
        null,
        List.of());
  }
}
