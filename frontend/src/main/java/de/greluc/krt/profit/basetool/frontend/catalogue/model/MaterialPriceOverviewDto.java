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

package de.greluc.krt.profit.basetool.frontend.catalogue.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import de.greluc.krt.profit.basetool.frontend.kernel.model.LocalisedNames;
import java.math.BigDecimal;
import java.util.Locale;
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
@DtoMirror
public record MaterialPriceOverviewDto(
    UUID id,
    String name,
    MaterialCategoryDto category,
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
      MaterialCategoryDto category,
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
   * Returns the name the page shows.
   *
   * @param locale the page locale
   * @return the German name on a German page when there is one, else the name
   */
  public String displayName(@Nullable Locale locale) {
    return LocalisedNames.display(name, nameDe, locale);
  }
}
