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
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.AccessDeniedException;

/** The owner check every hangar write runs before it touches a ship. */
final class ShipOwnership {

  private ShipOwnership() {}

  /**
   * Refuses a ship the given user does not own.
   *
   * @param ship the ship
   * @param userId the user the write acts for
   * @throws AccessDeniedException when the user is not the owner
   */
  static void requireOwner(@NotNull Ship ship, @NotNull UUID userId) {
    if (ship.getOwner() == null
        || ship.getOwner().getId() == null
        || !ship.getOwner().getId().equals(userId)) {
      throw new AccessDeniedException("Access denied: You do not own this ship");
    }
  }
}
