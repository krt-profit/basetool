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

import java.math.BigDecimal;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Data transfer record carrying Ship Type payload.
 *
 * @param id the ship type's id
 * @param name its name
 * @param manufacturer its manufacturer
 * @param description its German description, falling back to the English one
 * @param scu its cargo capacity
 * @param hidden whether an admin hid it from the pickers
 * @param catalogueFacts the source-owned vehicle facts, or {@code null} in a summary
 * @param noLongerInGame whether the P4K stamped it as gone from the game or an admin hid it through
 *     a review case; an existing ship of the type then shows the "no longer in the game" notice
 *     (REQ-P4K-013)
 */
public record ShipTypeDto(
    UUID id,
    String name,
    ManufacturerDto manufacturer,
    String description,
    BigDecimal scu,
    boolean hidden,
    @Nullable ShipCatalogueFacts catalogueFacts,
    boolean noLongerInGame) {

  /** Creates a ship summary of a type still in the game, without source-owned catalogue facts. */
  public ShipTypeDto(
      UUID id,
      String name,
      ManufacturerDto manufacturer,
      String description,
      BigDecimal scu,
      boolean hidden) {
    this(id, name, manufacturer, description, scu, hidden, null, false);
  }
}
