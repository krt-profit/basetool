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

package de.greluc.krt.profit.basetool.frontend.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Render-ready projection of the materials trade matrix for the client-side virtual-scroll grid
 * ({@code /js/materials-matrix.js}).
 *
 * <p>Holds terminal columns, star-system header spans and material rows grouped by category; each
 * row's price map is sparse, containing only terminals that trade the material.
 *
 * @param terminals the matrix columns, in display order (left to right)
 * @param systemGroups adjacent-column counts for the spanning star-system header row, aligned to
 *     {@code terminals}
 * @param groups the material rows, grouped by category and in display order (top to bottom)
 */
public record MatrixGridDto(
    List<Column> terminals, List<SystemGroup> systemGroups, List<Group> groups) {

  /**
   * One terminal column of the matrix.
   *
   * @param name terminal display name (also the key into {@link Row#prices()})
   * @param nickname terminal short name shown in the header when present
   * @param starSystemName parent star system, used for the system-filter and header grouping
   * @param planetName effective planet system, shown in the header tooltip; may be {@code null}
   * @param planetCssClass CSS class for the planet tint (e.g. {@code planet-hurston})
   * @param hasLoadingDock whether the terminal has a loading dock (loading-dock filter)
   * @param isAutoLoad whether the terminal supports auto-load (auto-load filter)
   */
  public record Column(
      String name,
      String nickname,
      String starSystemName,
      String planetName,
      String planetCssClass,
      @JsonProperty("hasLoadingDock") boolean hasLoadingDock,
      @JsonProperty("isAutoLoad") boolean isAutoLoad) {}

  /**
   * Spanning star-system header segment over a run of adjacent columns.
   *
   * @param name star system name shown above the spanning header
   * @param count number of contiguous terminal columns under this header
   */
  public record SystemGroup(String name, int count) {}

  /**
   * A category group of material rows.
   *
   * @param kind category name, or the sentinel {@code "Unsortiert"} for uncategorized materials
   *     (the client localizes that sentinel for display)
   * @param rows the material rows in this category, in display order
   */
  public record Group(String kind, List<Row> rows) {}

  /**
   * One material row of the matrix.
   *
   * @param materialName material display name
   * @param isIllegal whether the material is contraband (drives a warning badge)
   * @param isVolatileQt whether the material decays during quantum travel (warning badge)
   * @param isVolatileTime whether the material has a real-time decay timer (warning badge)
   * @param prices sparse map from terminal name to that terminal's price cell; terminals absent
   *     from the map do not trade this material and render as an empty cell
   */
  public record Row(
      String materialName,
      @JsonProperty("isIllegal") boolean isIllegal,
      @JsonProperty("isVolatileQt") boolean isVolatileQt,
      @JsonProperty("isVolatileTime") boolean isVolatileTime,
      Map<String, Cell> prices) {}

  /**
   * Buy/sell price pair for a single material×terminal cell. Either side may be {@code null} when
   * that terminal does not buy (resp. sell) the material.
   *
   * @param priceBuy price the terminal pays to buy the material from the player, or {@code null}
   * @param priceSell price the terminal charges to sell the material to the player, or {@code null}
   */
  public record Cell(BigDecimal priceBuy, BigDecimal priceSell) {}
}
