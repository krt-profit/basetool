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

package de.greluc.krt.profit.basetool.backend.hangar.internal;

import de.greluc.krt.profit.basetool.backend.hangar.api.Ship;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipOrigin;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** The rules a web create and an edit apply to a ship's origin (REQ-HANGAR-017). */
final class ShipOriginRules {

  private ShipOriginRules() {}

  /**
   * Refuses a create from the web form that names neither {@code PLEDGE} nor {@code IN_GAME}.
   *
   * @param origin the requested origin
   * @return the origin, now known to be a choice
   * @throws BadRequestException when it is absent or {@code UNKNOWN}
   */
  @Contract("null -> fail")
  static @NotNull ShipOrigin requireChoice(@Nullable ShipOrigin origin) {
    if (origin == null || !origin.isChoice()) {
      throw new BadRequestException("Choose whether the ship was pledged or bought in-game");
    }
    return origin;
  }

  /**
   * Sets an edit's origin on a ship; an absent origin, or the one the ship already reads as,
   * changes nothing.
   *
   * @param ship the ship to change
   * @param requested the edit's origin, or {@code null} to keep it
   * @return {@code true} when the stored origin changed
   * @throws BadRequestException when the ship is locked and the edit names another origin than
   *     {@code PLEDGE}
   */
  static boolean apply(@NotNull Ship ship, @Nullable ShipOrigin requested) {
    if (requested == null || requested == ship.effectiveOrigin()) {
      return false;
    }
    if (ship.isOriginLocked()) {
      throw new BadRequestException("An import fixed this ship's origin to PLEDGE");
    }
    ship.setOrigin(requested);
    return true;
  }
}
