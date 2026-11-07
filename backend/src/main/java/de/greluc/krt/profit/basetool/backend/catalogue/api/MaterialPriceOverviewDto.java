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

package de.greluc.krt.profit.basetool.backend.catalogue.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * One material of the price overview with its best buy and sell price across every visible
 * terminal.
 *
 * @param id the material's id
 * @param name the material's name
 * @param category the material's category, or {@code null}
 * @param isIllegal whether the material is contraband
 * @param isVolatileQt whether the material decays during quantum travel
 * @param isVolatileTime whether the material decays over time
 * @param minPriceBuy the lowest positive buy price, or {@code null}
 * @param maxPriceSell the highest positive sell price, or {@code null}
 * @param nameDe the material's German name, or {@code null} (REQ-P4K-023)
 */
public record MaterialPriceOverviewDto(
    UUID id,
    String name,
    @Nullable MaterialCategoryDto category,
    @JsonProperty("isIllegal") boolean isIllegal,
    @JsonProperty("isVolatileQt") boolean isVolatileQt,
    @JsonProperty("isVolatileTime") boolean isVolatileTime,
    BigDecimal minPriceBuy,
    BigDecimal maxPriceSell,
    @Nullable String nameDe) {
  /**
   * Creates an overview row without a German name.
   *
   * @param id the material's id
   * @param name the material's name
   * @param category the material's category, or {@code null}
   * @param isIllegal whether the material is contraband
   * @param isVolatileQt whether the material decays during quantum travel
   * @param isVolatileTime whether the material decays over time
   * @param minPriceBuy the lowest positive buy price, or {@code null}
   * @param maxPriceSell the highest positive sell price, or {@code null}
   */
  public MaterialPriceOverviewDto(
      UUID id,
      String name,
      @Nullable MaterialCategoryDto category,
      boolean isIllegal,
      boolean isVolatileQt,
      boolean isVolatileTime,
      BigDecimal minPriceBuy,
      BigDecimal maxPriceSell) {
    this(
        id,
        name,
        category,
        isIllegal,
        isVolatileQt,
        isVolatileTime,
        minPriceBuy,
        maxPriceSell,
        null);
  }

  /**
   * Convenience constructor used by JPA tuple-mapping queries: flattened category columns get
   * wrapped into a {@link MaterialCategoryDto}, and UEX-style {@code Integer} flag columns are
   * normalised to {@code boolean}.
   */
  public MaterialPriceOverviewDto(
      UUID id,
      String name,
      UUID categoryId,
      String categoryName,
      Long categoryVersion,
      Integer isIllegal,
      Integer isVolatileQt,
      Integer isVolatileTime,
      BigDecimal minPriceBuy,
      BigDecimal maxPriceSell,
      @Nullable String nameDe) {
    this(
        id,
        name,
        categoryId != null
            ? new MaterialCategoryDto(categoryId, categoryName, categoryVersion)
            : null,
        isIllegal != null && isIllegal == 1,
        isVolatileQt != null && isVolatileQt == 1,
        isVolatileTime != null && isVolatileTime == 1,
        minPriceBuy,
        maxPriceSell,
        nameDe);
  }

  /**
   * Test-only convenience constructor that accepts flattened category columns plus the boolean
   * flags directly (no Integer normalisation needed).
   */
  public MaterialPriceOverviewDto(
      UUID id,
      String name,
      UUID categoryId,
      String categoryName,
      Long categoryVersion,
      boolean isIllegal,
      boolean isVolatileQt,
      boolean isVolatileTime,
      BigDecimal minPriceBuy,
      BigDecimal maxPriceSell) {
    this(
        id,
        name,
        categoryId != null
            ? new MaterialCategoryDto(categoryId, categoryName, categoryVersion)
            : null,
        isIllegal,
        isVolatileQt,
        isVolatileTime,
        minPriceBuy,
        maxPriceSell,
        null);
  }
}
