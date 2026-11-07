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

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Data transfer record carrying Location payload.
 *
 * @param id the location
 * @param name the curated name, the only name that reaches the exchange (REQ-P4K-037)
 * @param description the admin's description
 * @param hidden whether normal dropdowns skip the location
 * @param homeLocation whether the hangar's home-location picker offers it
 * @param version the optimistic-lock version
 * @param starmapObjectId the starmap object the place was created on (REQ-P4K-036), or {@code
 *     null}; required on create, ignored on update
 * @param p4kName the English P4K name of the starmap object the place is linked to, directly or
 *     through its city or space station, or {@code null}; a game text, read-only
 * @param p4kNameDe the German P4K name of that object, or {@code null}; a game text, read-only
 */
public record LocationDto(
    UUID id,
    String name,
    String description,
    boolean hidden,
    boolean homeLocation,
    Long version,
    @Nullable UUID starmapObjectId,
    @Nullable String p4kName,
    @Nullable String p4kNameDe) {

  /**
   * Returns this location with the P4K names of its linked starmap object.
   *
   * @param name the English P4K name, or {@code null}
   * @param nameDe the German P4K name, or {@code null}
   * @return the copy
   */
  public LocationDto withP4kNames(@Nullable String name, @Nullable String nameDe) {
    return new LocationDto(
        id, this.name, description, hidden, homeLocation, version, starmapObjectId, name, nameDe);
  }
}
