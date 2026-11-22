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

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.ManufacturerDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.ShipTypeDto;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The manufacturer filter of the ship dialogs offers only the manufacturers of the ship types the
 * picker offers (REQ-HANGAR-020).
 */
class ShipPickerManufacturersTest {

  private static final ManufacturerDto DRAKE = manufacturer("Drake Interplanetary", "DRAK");
  private static final ManufacturerDto AEGIS = manufacturer("aegis Dynamics", "AEGS");
  private static final ManufacturerDto BEHRING = manufacturer("Behring", "BEHR");

  @Test
  void keepsOnlyTheMakersOfAnOfferedShipTypeOrderedByNameIgnoringCase() {
    List<ShipTypeDto> shipTypes =
        List.of(shipType("Cutlass Black", DRAKE), shipType("Avenger Titan", AEGIS));

    assertThat(ShipPickerManufacturers.ofShipTypes(List.of(DRAKE, BEHRING, AEGIS), shipTypes))
        .containsExactly(AEGIS, DRAKE);
  }

  @Test
  void aShipTypeWithoutAManufacturerOffersNone() {
    assertThat(
            ShipPickerManufacturers.ofShipTypes(
                List.of(DRAKE, BEHRING), List.of(shipType("Nameless", null))))
        .isEmpty();
  }

  @Test
  void aMakerTheCatalogueDoesNotListStaysOut() {
    assertThat(
            ShipPickerManufacturers.ofShipTypes(
                List.of(DRAKE), List.of(shipType("Avenger Titan", AEGIS))))
        .isEmpty();
  }

  /**
   * A catalogue manufacturer with a fresh id.
   *
   * @param name its name
   * @param abbreviation its short code
   * @return the manufacturer
   */
  private static ManufacturerDto manufacturer(String name, String abbreviation) {
    return new ManufacturerDto(UUID.randomUUID(), name, abbreviation, null, null, null, false);
  }

  /**
   * An offered ship type with a fresh id.
   *
   * @param name its name
   * @param manufacturer its manufacturer, or {@code null}
   * @return the ship type
   */
  private static ShipTypeDto shipType(String name, ManufacturerDto manufacturer) {
    return new ShipTypeDto(UUID.randomUUID(), name, manufacturer, null, BigDecimal.ZERO, false);
  }
}
