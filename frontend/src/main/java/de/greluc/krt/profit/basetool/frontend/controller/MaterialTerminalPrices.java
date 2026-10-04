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

package de.greluc.krt.profit.basetool.frontend.controller;

import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialMatrixItemDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialPriceDto;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The terminal table and the price figures of the material detail page: each terminal's prices with
 * its location and planet tint, sorted by sale price, and the best sale, best purchase and average
 * sale across them.
 */
public final class MaterialTerminalPrices {

  /** Placeholder shown for a price that does not exist. */
  private static final String NO_PRICE = "–";

  /** Separator between the parts of a location label. */
  private static final String LOCATION_SEPARATOR = " · ";

  /** Utility class — not instantiable. */
  private MaterialTerminalPrices() {}

  /**
   * One terminal row of the detail table.
   *
   * @param terminalName the terminal's name
   * @param location planet (or star system) and the city, station or outpost, or {@code null} when
   *     the terminal is not in the price matrix
   * @param planetCssClass the planet tint class from {@link PlanetColorResolver}
   * @param priceBuy the purchase price per SCU, or {@code null} when the terminal sells none
   * @param priceSell the sale price per SCU, or {@code null} when the terminal buys none
   */
  public record Row(
      @NotNull String terminalName,
      @Nullable String location,
      @NotNull String planetCssClass,
      @Nullable BigDecimal priceBuy,
      @Nullable BigDecimal priceSell) {

    /**
     * The sale price as the table shows it.
     *
     * @return the formatted price, or a dash when the terminal buys none
     */
    @NotNull
    public String sellText() {
      return format(priceSell);
    }

    /**
     * The purchase price as the table shows it.
     *
     * @return the formatted price, or a dash when the terminal sells none
     */
    @NotNull
    public String buyText() {
      return format(priceBuy);
    }
  }

  /**
   * The four price figures above the table.
   *
   * @param bestSell the highest sale price, or {@code null} when no terminal buys the material
   * @param bestSellTerminal the terminal paying {@code bestSell}, or {@code null}
   * @param bestBuy the lowest purchase price, or {@code null} when no terminal sells the material
   * @param bestBuyTerminal the terminal asking {@code bestBuy}, or {@code null}
   * @param averageSell the mean sale price, or {@code null} when no terminal buys the material
   * @param sellTerminals the number of terminals that buy the material
   */
  public record Summary(
      @Nullable BigDecimal bestSell,
      @Nullable String bestSellTerminal,
      @Nullable BigDecimal bestBuy,
      @Nullable String bestBuyTerminal,
      @Nullable BigDecimal averageSell,
      int sellTerminals) {

    /**
     * The best sale price as the figure shows it.
     *
     * @return the formatted price, or a dash
     */
    @NotNull
    public String bestSellText() {
      return format(bestSell);
    }

    /**
     * The best purchase price as the figure shows it.
     *
     * @return the formatted price, or a dash
     */
    @NotNull
    public String bestBuyText() {
      return format(bestBuy);
    }

    /**
     * The average sale price as the figure shows it.
     *
     * @return the formatted price, or a dash
     */
    @NotNull
    public String averageSellText() {
      return format(averageSell);
    }
  }

  /**
   * Builds the table rows from the complete price list, taking each terminal's location from the
   * matrix rows of the same material; rows that buy the material come first, highest sale price
   * first, the rest by name.
   *
   * @param prices the material's complete price list
   * @param matrixItems the price-matrix rows of the material
   * @return the sorted rows
   */
  @NotNull
  @Unmodifiable
  public static List<Row> rows(
      @NotNull List<MaterialPriceDto> prices, @NotNull List<MaterialMatrixItemDto> matrixItems) {
    Map<String, MaterialMatrixItemDto> byTerminal = new HashMap<>();
    for (MaterialMatrixItemDto item : matrixItems) {
      if (item.terminalName() != null) {
        byTerminal.putIfAbsent(item.terminalName(), item);
      }
    }
    List<Row> rows = new ArrayList<>(prices.size());
    for (MaterialPriceDto price : prices) {
      String name = price.terminalName() != null ? price.terminalName() : "";
      MaterialMatrixItemDto item = byTerminal.get(name);
      rows.add(
          new Row(
              name,
              item == null ? null : location(item),
              item == null
                  ? PlanetColorResolver.UNKNOWN_CLASS
                  : PlanetColorResolver.cssClassFor(item.starSystemName(), item.planetName()),
              positive(price.priceBuy()),
              positive(price.priceSell())));
    }
    rows.sort(
        Comparator.comparing(
                Row::priceSell, Comparator.nullsLast(Comparator.<BigDecimal>reverseOrder()))
            .thenComparing(Row::terminalName, String.CASE_INSENSITIVE_ORDER));
    return List.copyOf(rows);
  }

  /**
   * Picks the best sale, the best purchase and the average sale across the rows.
   *
   * @param rows the table rows
   * @return the figures; all prices {@code null} when no row carries one
   */
  @NotNull
  public static Summary summarize(@NotNull List<Row> rows) {
    BigDecimal bestSell = null;
    String bestSellTerminal = null;
    BigDecimal bestBuy = null;
    String bestBuyTerminal = null;
    BigDecimal sellTotal = BigDecimal.ZERO;
    int sellCount = 0;
    for (Row row : rows) {
      BigDecimal sell = row.priceSell();
      if (sell != null) {
        sellTotal = sellTotal.add(sell);
        sellCount++;
        if (bestSell == null || sell.compareTo(bestSell) > 0) {
          bestSell = sell;
          bestSellTerminal = row.terminalName();
        }
      }
      BigDecimal buy = row.priceBuy();
      if (buy != null && (bestBuy == null || buy.compareTo(bestBuy) < 0)) {
        bestBuy = buy;
        bestBuyTerminal = row.terminalName();
      }
    }
    return new Summary(
        bestSell,
        bestSellTerminal,
        bestBuy,
        bestBuyTerminal,
        sellCount == 0
            ? null
            : sellTotal.divide(BigDecimal.valueOf(sellCount), 2, RoundingMode.HALF_UP),
        sellCount);
  }

  /**
   * Keeps only the matrix rows of one material.
   *
   * @param matrixItems the whole price matrix
   * @param materialId the material
   * @return the material's matrix rows
   */
  @NotNull
  @Unmodifiable
  public static List<MaterialMatrixItemDto> forMaterial(
      @NotNull List<MaterialMatrixItemDto> matrixItems, @NotNull UUID materialId) {
    return matrixItems.stream().filter(item -> materialId.equals(item.materialId())).toList();
  }

  /**
   * The location label of a terminal: planet, or star system when it has none, then the city,
   * station or outpost.
   *
   * @param item the terminal's matrix row
   * @return the label, or {@code null} when the row names no place
   */
  @Nullable
  private static String location(@NotNull MaterialMatrixItemDto item) {
    String region = firstPresent(item.planetName(), item.starSystemName(), null);
    String place = firstPresent(item.cityName(), item.spaceStationName(), item.outpostName());
    if (region == null) {
      return place;
    }
    return place == null || place.equalsIgnoreCase(region)
        ? region
        : region + LOCATION_SEPARATOR + place;
  }

  /**
   * The first non-blank of up to three values.
   *
   * @param first the preferred value
   * @param second the first fallback
   * @param third the second fallback
   * @return the first non-blank value, or {@code null}
   */
  @Nullable
  private static String firstPresent(
      @Nullable String first, @Nullable String second, @Nullable String third) {
    for (String value : new String[] {first, second, third}) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  /**
   * Drops a price that is absent, zero or negative, which the backend uses for "not traded here".
   *
   * @param price the raw price
   * @return the price when positive, otherwise {@code null}
   */
  @Nullable
  @Contract("null -> null")
  private static BigDecimal positive(@Nullable BigDecimal price) {
    return price != null && price.signum() > 0 ? price : null;
  }

  /**
   * Formats a price with German grouping and at most two decimals, like the price matrix.
   *
   * @param price the price, or {@code null}
   * @return the formatted price, or a dash for {@code null}
   */
  @NotNull
  private static String format(@Nullable BigDecimal price) {
    if (price == null) {
      return NO_PRICE;
    }
    DecimalFormat format =
        new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.GERMANY));
    format.setRoundingMode(RoundingMode.HALF_UP);
    return format.format(price);
  }
}
