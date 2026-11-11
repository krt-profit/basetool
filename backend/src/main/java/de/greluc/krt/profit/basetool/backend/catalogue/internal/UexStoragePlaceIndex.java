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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Storage-place associations and name reservations held by one UEX chunk transaction. */
public final class UexStoragePlaceIndex {

  private final Map<String, Location> byName = new HashMap<>();
  private final Map<UUID, Location> byCity = new HashMap<>();
  private final Map<UUID, Location> byStation = new HashMap<>();

  /**
   * Indexes existing associations and names for the chunk.
   *
   * @param locations the matching stored locations
   */
  UexStoragePlaceIndex(@NotNull List<Location> locations) {
    for (Location location : locations) {
      byName.put(location.getName(), location);
      if (location.getCity() != null) {
        byCity.put(location.getCity().getId(), location);
      }
      if (location.getSpaceStation() != null) {
        byStation.put(location.getSpaceStation().getId(), location);
      }
    }
  }

  /**
   * Finds the stored location linked to a city.
   *
   * @param id the local city identifier
   * @return its location, or absent
   */
  @Nullable
  Location city(@Nullable UUID id) {
    return byCity.get(id);
  }

  /**
   * Finds the stored location linked to a space station.
   *
   * @param id the local station identifier
   * @return its location, or absent
   */
  @Nullable
  Location station(@Nullable UUID id) {
    return byStation.get(id);
  }

  /**
   * Checks whether a name is unclaimed within this chunk.
   *
   * @param name the proposed location name
   * @return whether no stored or newly created location holds it
   */
  boolean isFree(String name) {
    return !byName.containsKey(name);
  }

  /**
   * Reserves the names and associations of a location written by the chunk.
   *
   * @param location the saved or managed location
   */
  void remember(@NotNull Location location) {
    byName.put(location.getName(), location);
    if (location.getCity() != null) {
      byCity.put(location.getCity().getId(), location);
    }
    if (location.getSpaceStation() != null) {
      byStation.put(location.getSpaceStation().getId(), location);
    }
  }

  /**
   * Renames a location only when no other location owns the name.
   *
   * @param location the location being refreshed
   * @param name its current source name
   */
  void renameIfFree(@NotNull Location location, String name) {
    Location other = byName.get(name);
    if (other == null || other.equals(location)) {
      byName.remove(location.getName(), location);
      location.setName(name);
      byName.put(name, location);
    }
  }
}
