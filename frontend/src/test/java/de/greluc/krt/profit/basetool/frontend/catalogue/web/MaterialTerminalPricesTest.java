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

package de.greluc.krt.profit.basetool.frontend.catalogue.web;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialMatrixItemDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialPriceDto;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests of {@link MaterialTerminalPrices}: row order, locations and planet tints, and the
 * price figures of the material detail page.
 */
class MaterialTerminalPricesTest {

  /** The material every case is about. */
  private static final UUID MATERIAL = UUID.fromString("00000000-0000-0000-0000-000000000001");

  /**
   * Rows that buy the material come first, highest sale first; the rest follow by name, and a zero
   * price counts as not traded.
   */
  @Test
  void rows_sortBySalePriceAndDropNonPositivePrices() {
    List<MaterialTerminalPrices.Row> rows =
        MaterialTerminalPrices.rows(
            List.of(
                price("Zeta", "10", null),
                price("Lorville CBD", null, "88.2"),
                price("Area18 TDD", "80", "89.6"),
                price("Alpha", "0", "0")),
            List.of());

    assertThat(rows)
        .extracting(MaterialTerminalPrices.Row::terminalName)
        .containsExactly("Area18 TDD", "Lorville CBD", "Alpha", "Zeta");
    assertThat(rows.get(2).priceBuy()).isNull();
    assertThat(rows.get(2).sellText()).isEqualTo("–");
    assertThat(rows.get(0).sellText()).isEqualTo("89,6");
  }

  /** A terminal in the matrix gets its location and planet tint; one outside it gets neither. */
  @Test
  void rows_takeLocationAndTintFromTheMatrix() {
    List<MaterialTerminalPrices.Row> rows =
        MaterialTerminalPrices.rows(
            List.of(price("Area18 TDD", null, "89.6"), price("Unknown", null, "50")),
            List.of(
                matrix("Area18 TDD", "Stanton", "ArcCorp", "Area18", null),
                matrix("Everus Harbor", "Stanton", "Hurston", null, "Everus Harbor")));

    assertThat(rows.get(0).location()).isEqualTo("ArcCorp · Area18");
    assertThat(rows.get(0).planetCssClass()).isEqualTo("planet-arccorp");
    assertThat(rows.get(1).location()).isNull();
    assertThat(rows.get(1).planetCssClass()).isEqualTo(PlanetColorResolver.UNKNOWN_CLASS);
  }

  /** The figures pick the best sale, the best purchase and the mean sale with its count. */
  @Test
  void summarize_picksBestPricesAndAverage() {
    MaterialTerminalPrices.Summary summary =
        MaterialTerminalPrices.summarize(
            MaterialTerminalPrices.rows(
                List.of(
                    price("Area18 TDD", "80", "89.6"),
                    price("Lorville CBD", "75.5", "88.2"),
                    price("Everus Harbor", null, "86.9")),
                List.of()));

    assertThat(summary.bestSell()).isEqualByComparingTo("89.6");
    assertThat(summary.bestSellTerminal()).isEqualTo("Area18 TDD");
    assertThat(summary.bestBuy()).isEqualByComparingTo("75.5");
    assertThat(summary.bestBuyTerminal()).isEqualTo("Lorville CBD");
    assertThat(summary.averageSell()).isEqualByComparingTo("88.23");
    assertThat(summary.sellTerminals()).isEqualTo(3);
    assertThat(summary.averageSellText()).isEqualTo("88,23");
  }

  /** Without any price every figure is empty and shown as a dash. */
  @Test
  void summarize_withoutPrices_isEmpty() {
    MaterialTerminalPrices.Summary summary = MaterialTerminalPrices.summarize(List.of());

    assertThat(summary.bestSell()).isNull();
    assertThat(summary.bestBuy()).isNull();
    assertThat(summary.averageSell()).isNull();
    assertThat(summary.sellTerminals()).isZero();
    assertThat(summary.bestSellText()).isEqualTo("–");
    assertThat(summary.bestBuyText()).isEqualTo("–");
  }

  /** Only the matrix rows of the requested material are kept. */
  @Test
  void forMaterial_keepsOnlyTheMaterialsRows() {
    MaterialMatrixItemDto other =
        new MaterialMatrixItemDto(
            UUID.randomUUID(),
            "Gold",
            false,
            false,
            false,
            null,
            UUID.randomUUID(),
            "X",
            "X",
            "Stanton",
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            false,
            false);

    assertThat(
            MaterialTerminalPrices.forMaterial(
                List.of(matrix("Area18 TDD", "Stanton", "ArcCorp", "Area18", null), other),
                MATERIAL))
        .extracting(MaterialMatrixItemDto::terminalName)
        .containsExactly("Area18 TDD");
  }

  /**
   * One price of a terminal.
   *
   * @param terminal the terminal
   * @param buy the purchase price, or {@code null}
   * @param sell the sale price, or {@code null}
   * @return the price
   */
  private static MaterialPriceDto price(String terminal, String buy, String sell) {
    return new MaterialPriceDto(
        UUID.randomUUID(),
        terminal,
        buy == null ? null : new BigDecimal(buy),
        sell == null ? null : new BigDecimal(sell),
        null,
        null,
        true,
        true);
  }

  /**
   * One matrix row of the material at a terminal.
   *
   * @param terminal the terminal
   * @param system the star system
   * @param planet the planet
   * @param city the city, or {@code null}
   * @param station the space station, or {@code null}
   * @return the matrix row
   */
  private static MaterialMatrixItemDto matrix(
      String terminal, String system, String planet, String city, String station) {
    return new MaterialMatrixItemDto(
        MATERIAL,
        "Quantanium",
        false,
        false,
        false,
        null,
        UUID.randomUUID(),
        terminal,
        terminal,
        system,
        null,
        null,
        city,
        station,
        null,
        planet,
        false,
        false,
        false);
  }
}
