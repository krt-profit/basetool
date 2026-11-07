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

package de.greluc.krt.profit.basetool.backend.personalinventory.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

/**
 * The kind of place a personal-inventory location is, derived from what the {@code location} row is
 * linked to; display only, never stored.
 */
public enum PersonalInventoryLocationType {
  /** A location linked to a UEX city. */
  CITY,
  /** A location linked to a UEX space station. */
  SPACE_STATION,
  /** A location linked to neither, such as an admin-created storage place. */
  OTHER;

  /**
   * Derives the kind of a location from its city or space-station link; reads only whether the link
   * is set, so a lazy link stays unloaded.
   *
   * @param location the location, or {@code null} when the item's place no longer resolves
   * @return the kind, or {@code null} for a {@code null} location
   */
  @Contract("null -> null; !null -> !null")
  public static @Nullable PersonalInventoryLocationType of(@Nullable Location location) {
    if (location == null) {
      return null;
    }
    if (location.getCity() != null) {
      return CITY;
    }
    return location.getSpaceStation() != null ? SPACE_STATION : OTHER;
  }
}
