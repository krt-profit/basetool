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

/** Data transfer record carrying Material Price Overview payload. */
public record MaterialPriceOverviewDto(
    UUID id,
    String name,
    @Nullable MaterialCategoryDto category,
    @JsonProperty("isIllegal") boolean isIllegal,
    @JsonProperty("isVolatileQt") boolean isVolatileQt,
    @JsonProperty("isVolatileTime") boolean isVolatileTime,
    BigDecimal minPriceBuy,
    BigDecimal maxPriceSell) {
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
      BigDecimal maxPriceSell) {
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
        maxPriceSell);
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
        maxPriceSell);
  }
}
