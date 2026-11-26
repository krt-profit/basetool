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

import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialSalePrices;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialSaleQuote;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialSaleQuote.TerminalSalePrice;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service-layer implementation of {@link MaterialSalePrices} over the UEX terminal prices
 * (REQ-INV-072).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MaterialSalePriceService implements MaterialSalePrices {

  /** Scale of the average price, the material page's average sale price. */
  private static final int AVERAGE_SCALE = 2;

  private final MaterialPriceRepository materialPriceRepository;

  /**
   * Reads every buying terminal of the given materials and condenses each material's prices.
   *
   * @param materialIds the materials to price; an empty collection runs no query
   * @return the prices per material id; materials without a buying terminal are absent
   */
  @Override
  @NotNull
  @Unmodifiable
  public Map<UUID, MaterialSaleQuote> quotes(@NotNull Collection<UUID> materialIds) {
    if (materialIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, List<MaterialSaleRow>> rowsByMaterial =
        materialPriceRepository.findSaleRows(materialIds).stream()
            .collect(
                Collectors.groupingBy(
                    MaterialSaleRow::materialId, LinkedHashMap::new, Collectors.toList()));
    return rowsByMaterial.entrySet().stream()
        .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> quote(entry.getValue())));
  }

  /**
   * Condenses one material's rows; the rows arrive by terminal name, so the first of equal prices
   * wins a tie.
   *
   * @param rows the material's buying terminals by name; never empty
   * @return the highest, lowest and average price
   */
  @NotNull
  private static MaterialSaleQuote quote(@NotNull List<MaterialSaleRow> rows) {
    MaterialSaleRow highest = rows.getFirst();
    MaterialSaleRow lowest = rows.getFirst();
    BigDecimal sum = BigDecimal.ZERO;
    for (MaterialSaleRow row : rows) {
      sum = sum.add(row.priceSell());
      if (row.priceSell().compareTo(highest.priceSell()) > 0) {
        highest = row;
      }
      if (row.priceSell().compareTo(lowest.priceSell()) < 0) {
        lowest = row;
      }
    }
    return new MaterialSaleQuote(
        terminalPrice(highest),
        terminalPrice(lowest),
        sum.divide(BigDecimal.valueOf(rows.size()), AVERAGE_SCALE, RoundingMode.HALF_UP),
        rows.size());
  }

  /**
   * One row's price.
   *
   * @param row the row
   * @return the price and its terminal
   */
  @NotNull
  private static TerminalSalePrice terminalPrice(@NotNull MaterialSaleRow row) {
    return new TerminalSalePrice(row.priceSell(), row.terminalName());
  }
}
