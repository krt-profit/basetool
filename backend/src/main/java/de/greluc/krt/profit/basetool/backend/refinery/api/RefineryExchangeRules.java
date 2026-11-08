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

package de.greluc.krt.profit.basetool.backend.refinery.api;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import org.jetbrains.annotations.NotNull;

/**
 * The rules a refinery order's location and goods follow in the web and in the exchange alike
 * (REQ-REFINERY-020, REQ-XCH-052).
 */
public final class RefineryExchangeRules {

  /** Non-instantiable static-helper holder. */
  private RefineryExchangeRules() {}

  /**
   * Whether a location hosts a refinery: its city's or its space station's derived {@code
   * hasRefineryTerminal} flag, the signal the web's picker uses. Reads the parent through the
   * association, never a query, so it cannot trigger an auto-flush mid-update.
   *
   * @param location the location with its city and space station reachable
   * @return {@code true} when the city or the space station has a refinery terminal
   */
  public static boolean hostsRefinery(@NotNull Location location) {
    return (location.getCity() != null
            && Boolean.TRUE.equals(location.getCity().getHasRefineryTerminal()))
        || (location.getSpaceStation() != null
            && Boolean.TRUE.equals(location.getSpaceStation().getHasRefineryTerminal()));
  }

  /**
   * Whether a material may be a good's input: a {@code RAW} material or a manual raw one.
   *
   * @param material the material
   * @return {@code true} when it may be refined
   */
  public static boolean refinable(@NotNull Material material) {
    return material.getType() == MaterialType.RAW
        || Boolean.TRUE.equals(material.getIsManualRawMaterial());
  }

  /**
   * The output a good of the input yields: the input's refined material, else the input itself.
   *
   * @param input the input material
   * @return the output material
   */
  public static @NotNull Material output(@NotNull Material input) {
    return input.getRefinedMaterial() != null ? input.getRefinedMaterial() : input;
  }
}
