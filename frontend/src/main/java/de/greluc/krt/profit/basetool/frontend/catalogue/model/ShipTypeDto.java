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

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Data transfer record carrying Ship Type payload.
 *
 * @param id the ship type's id
 * @param name its name
 * @param manufacturer its manufacturer
 * @param description its description
 * @param scu its cargo capacity
 * @param hidden whether an admin hid it from the pickers
 * @param noLongerInGame whether the P4K retired it; a ship of the type then shows the "no longer in
 *     the game" notice (REQ-P4K-013)
 */
@DtoMirror
public record ShipTypeDto(
    UUID id,
    String name,
    ManufacturerDto manufacturer,
    String description,
    BigDecimal scu,
    boolean hidden,
    boolean noLongerInGame) {

  /**
   * Builds a ship type still in the game.
   *
   * @param id the ship type's id
   * @param name its name
   * @param manufacturer its manufacturer
   * @param description its description
   * @param scu its cargo capacity
   * @param hidden whether an admin hid it from the pickers
   */
  public ShipTypeDto(
      UUID id,
      String name,
      ManufacturerDto manufacturer,
      String description,
      BigDecimal scu,
      boolean hidden) {
    this(id, name, manufacturer, description, scu, hidden, false);
  }
}
