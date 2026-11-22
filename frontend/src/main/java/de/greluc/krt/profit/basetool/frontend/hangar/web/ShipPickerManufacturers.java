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

package de.greluc.krt.profit.basetool.frontend.hangar.web;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.ManufacturerDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.ShipTypeDto;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Picks the manufacturers the manufacturer filter of a ship dialog offers, shared by „Meine
 * Schiffe" and the admin member hangar (REQ-HANGAR-020).
 */
final class ShipPickerManufacturers {

  private ShipPickerManufacturers() {}

  /**
   * Keeps the catalogue manufacturers that make at least one of the ship types the dialog's
   * ship-type picker offers, ordered by name ignoring case.
   *
   * @param manufacturers the manufacturer catalogue
   * @param shipTypes the ship types the picker offers; a type without a manufacturer adds none
   * @return the manufacturers with an offered ship type
   */
  static @NotNull @Unmodifiable List<ManufacturerDto> ofShipTypes(
      @NotNull List<ManufacturerDto> manufacturers, @NotNull List<ShipTypeDto> shipTypes) {
    Set<UUID> makers =
        shipTypes.stream()
            .map(ShipTypeDto::manufacturer)
            .filter(Objects::nonNull)
            .map(ManufacturerDto::id)
            .collect(Collectors.toSet());
    return manufacturers.stream()
        .filter(manufacturer -> makers.contains(manufacturer.id()))
        .sorted(Comparator.comparing(ManufacturerDto::name, String.CASE_INSENSITIVE_ORDER))
        .toList();
  }
}
