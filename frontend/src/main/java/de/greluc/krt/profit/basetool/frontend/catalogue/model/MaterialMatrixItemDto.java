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
 * One material/terminal price row of the materials trade matrix.
 *
 * <p>{@code planetName} is the effective planet system of the terminal (direct, via its moon, or
 * via a like-named orbit), or {@code null} when the terminal belongs to no planet.
 *
 * @param materialNameDe the material's German name, or {@code null} (REQ-P4K-023)
 */
@DtoMirror
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
    Boolean isAutoLoad,
    @Nullable String materialNameDe) {

  /**
   * Creates a matrix row without a German material name.
   *
   * @param materialId material id
   * @param materialName material name
   * @param isIllegal contraband flag
   * @param isVolatileQt quick-decay flag
   * @param isVolatileTime time-decay flag
   * @param category the material's category, or {@code null}
   * @param terminalId terminal id
   * @param terminalName terminal display name
   * @param terminalNickname terminal short name
   * @param starSystemName parent star system
   * @param priceBuy current buy price at the terminal
   * @param priceSell current sell price at the terminal
   * @param cityName parent city, if any
   * @param spaceStationName parent space station, if any
   * @param outpostName parent outpost, if any
   * @param planetName effective planet name, or {@code null}
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
    this(
        materialId,
        materialName,
        isIllegal,
        isVolatileQt,
        isVolatileTime,
        category,
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
        isAutoLoad,
        null);
  }

  /**
   * Returns the material name the page shows.
   *
   * @param locale the page locale
   * @return the German name on a German page when there is one, else the name
   */
  public String materialDisplayName(@Nullable Locale locale) {
    return LocalisedNames.display(materialName, materialNameDe, locale);
  }
}
