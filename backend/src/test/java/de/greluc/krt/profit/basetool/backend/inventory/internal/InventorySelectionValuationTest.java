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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link InventorySelectionValuation} (REQ-INV-072): the member's marked entries are
 * read for that member and valued at that member's basis.
 */
class InventorySelectionValuationTest {

  private static final UUID MEMBER = UUID.randomUUID();

  private final InventoryItemRepository repository = mock(InventoryItemRepository.class);
  private final InventorySaleValuation saleValuation = mock(InventorySaleValuation.class);
  private final InventorySelectionValuation selectionValuation =
      new InventorySelectionValuation(repository, saleValuation);

  @Test
  void valuesTheSlicesOfTheMembersMarkedEntries() {
    List<UUID> ids = List.of(UUID.randomUUID());
    List<InventorySaleSlice> slices =
        List.of(new InventorySaleSlice(UUID.randomUUID(), QuantityType.SCU, false, 3.0));
    InventorySelectionSaleValueDto value =
        new InventorySelectionSaleValueDto(SalePriceBasis.HIGHEST, new BigDecimal("900"), 0, false);
    when(repository.findSaleSlices(MEMBER, ids)).thenReturn(slices);
    when(saleValuation.valuedSelection(slices, MEMBER)).thenReturn(value);

    assertThat(selectionValuation.valueOf(MEMBER, ids)).isSameAs(value);
  }
}
