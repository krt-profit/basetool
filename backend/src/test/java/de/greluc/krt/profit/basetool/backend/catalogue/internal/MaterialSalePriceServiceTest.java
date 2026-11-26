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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialSaleQuote;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialSaleQuote.TerminalSalePrice;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MaterialSalePriceService} (REQ-INV-072): no query for no material, the
 * highest and lowest price with the first terminal by name on a tie, and the average rounded like
 * the material page's.
 */
class MaterialSalePriceServiceTest {

  private final MaterialPriceRepository repository = mock(MaterialPriceRepository.class);
  private final MaterialSalePriceService service = new MaterialSalePriceService(repository);

  @Test
  void noMaterialRunsNoQuery() {
    assertThat(service.quotes(List.of())).isEmpty();

    verifyNoInteractions(repository);
  }

  @Test
  void condensesEachMaterialsTerminals_firstByNameOnATie() {
    UUID agricium = UUID.randomUUID();
    UUID titanium = UUID.randomUUID();
    UUID unpriced = UUID.randomUUID();
    List<UUID> ids = List.of(agricium, titanium, unpriced);
    when(repository.findSaleRows(ids))
        .thenReturn(
            List.of(
                row(agricium, "Alpha", "2700"),
                row(titanium, "Baijini Point", "820"),
                row(agricium, "Bravo", "2700"),
                row(agricium, "Charlie", "2500"),
                row(agricium, "Delta", "2500"),
                row(agricium, "Echo", "2601")));

    assertThat(service.quotes(ids))
        .containsOnlyKeys(agricium, titanium)
        .containsEntry(
            agricium,
            new MaterialSaleQuote(
                price("2700", "Alpha"), price("2500", "Charlie"), new BigDecimal("2600.20"), 5))
        .containsEntry(
            titanium,
            new MaterialSaleQuote(
                price("820", "Baijini Point"),
                price("820", "Baijini Point"),
                new BigDecimal("820.00"),
                1));
  }

  private static MaterialSaleRow row(UUID material, String terminal, String price) {
    return new MaterialSaleRow(material, terminal, new BigDecimal(price));
  }

  private static TerminalSalePrice price(String price, String terminal) {
    return new TerminalSalePrice(new BigDecimal(price), terminal);
  }
}
