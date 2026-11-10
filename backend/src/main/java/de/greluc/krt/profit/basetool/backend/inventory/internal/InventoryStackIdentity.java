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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import org.jetbrains.annotations.NotNull;

/** Compares a stock row with a transfer target. */
public final class InventoryStackIdentity {
  private InventoryStackIdentity() {}

  /**
   * Whether a row already sits at the requested transfer target, so a {@code LOCATION} bulk
   * rebooking skips it.
   *
   * @param item the source row
   * @param targetUser the row's resolved destination owner (never {@code null})
   * @param targetLocation the row's resolved destination location (may be {@code null} on a row
   *     without one)
   * @return {@code true} iff neither the owner nor the location would change
   */
  static boolean isSameStackTarget(
      @NotNull InventoryItem item, @NotNull User targetUser, Location targetLocation) {
    final boolean sameUser = targetUser.getId().equals(item.getUser().getId());
    final boolean sameLocation =
        targetLocation == null
            ? item.getLocation() == null
            : item.getLocation() != null
                && targetLocation.getId().equals(item.getLocation().getId());
    return sameUser && sameLocation;
  }
}
