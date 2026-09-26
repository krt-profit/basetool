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

package de.greluc.krt.profit.basetool.backend.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One material price row of the material matrix at a single terminal.
 *
 * <p>{@code planetName} is the effective planet (direct, via moon, or via a like-named orbit), or
 * {@code null} for a terminal not attached to any planet.
 */
public record MaterialMatrixItemDto(
    UUID materialId,
    String materialName,
    @JsonProperty("isIllegal") Boolean isIllegal,
    @JsonProperty("isVolatileQt") Boolean isVolatileQt,
    @JsonProperty("isVolatileTime") Boolean isVolatileTime,
    MaterialCategoryDto category,
    UUID terminalId,
    String terminalName,
    String terminalNickname,
    String starSystemName,
    BigDecimal priceBuy,
    BigDecimal priceSell,
    String cityName,
    String spaceStationName,
    String outpostName,
    String planetName,
    Boolean isJumpPoint,
    Boolean hasLoadingDock,
    Boolean isAutoLoad) {
  /**
   * Convenience constructor used by JPA tuple-mapping queries that surface the {@code
   * MaterialCategory} fields as flat columns; wraps them into a {@link MaterialCategoryDto}.
   *
   * @param materialId material id
   * @param materialName material display name
   * @param isIllegal contraband flag
   * @param isVolatileQt quick-decay flag
   * @param isVolatileTime time-decay flag
   * @param categoryId category id (may be {@code null})
   * @param categoryName category name (may be {@code null})
   * @param categoryVersion category optimistic-lock version (may be {@code null})
   * @param terminalId terminal id
   * @param terminalName terminal display name
   * @param terminalNickname terminal short name
   * @param starSystemName parent star system
   * @param priceBuy current buy price at the terminal
   * @param priceSell current sell price at the terminal
   * @param cityName parent city, if any
   * @param spaceStationName parent space station, if any
   * @param outpostName parent outpost, if any
   * @param planetName effective planet name (direct, via moon, or via like-named orbit); {@code
   *     null} when the terminal is not attached to a planet
   * @param isJumpPoint whether the parent station is a jump point
   * @param hasLoadingDock whether the terminal has a loading dock
   * @param isAutoLoad whether the terminal supports automatic cargo loading
   */
  public MaterialMatrixItemDto(
      UUID materialId,
      String materialName,
      Boolean isIllegal,
      Boolean isVolatileQt,
      Boolean isVolatileTime,
      UUID categoryId,
      String categoryName,
      Long categoryVersion,
      UUID terminalId,
      String terminalName,
      String terminalNickname,
      String starSystemName,
      BigDecimal priceBuy,
      BigDecimal priceSell,
      String cityName,
      String spaceStationName,
      String outpostName,
      String planetName,
      Boolean isJumpPoint,
      Boolean hasLoadingDock,
      Boolean isAutoLoad) {
    this(
        materialId,
        materialName,
        isIllegal,
        isVolatileQt,
        isVolatileTime,
        categoryId != null
            ? new MaterialCategoryDto(categoryId, categoryName, categoryVersion)
            : null,
        terminalId,
        terminalName,
        terminalNickname,
        starSystemName,
        priceBuy,
        priceSell,
        cityName,
        spaceStationName,
        outpostName,
        planetName,
        isJumpPoint,
        hasLoadingDock,
        isAutoLoad);
  }
}
