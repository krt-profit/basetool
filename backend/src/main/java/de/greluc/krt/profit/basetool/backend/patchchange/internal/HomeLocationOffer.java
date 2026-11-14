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

package de.greluc.krt.profit.basetool.backend.patchchange.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The home locations a member may choose (REQ-PATCH-021): only the admin-curated ones, offered and
 * not hidden, as the hangar's home-location picker lists them.
 */
@Component
@RequiredArgsConstructor
class HomeLocationOffer {

  private final LocationRepository locations;

  /**
   * Lists the curated home locations by name.
   *
   * @return the locations on offer
   */
  @Transactional(readOnly = true)
  @NotNull
  @Unmodifiable
  List<HomeLocationOptionDto> offered() {
    return curated().stream()
        .map(HomeLocationOffer::toOption)
        .sorted(Comparator.comparing(HomeLocationOptionDto::name, String.CASE_INSENSITIVE_ORDER))
        .toList();
  }

  /**
   * Resolves a chosen home location.
   *
   * @param id the location
   * @return the location
   * @throws BadRequestException {@code error.patch_change.home_location_not_offered} when it is not
   *     a curated home location on offer
   */
  @Transactional(readOnly = true)
  @NotNull
  Location requireOffered(@NotNull UUID id) {
    return curated().stream()
        .filter(location -> id.equals(location.getId()))
        .findFirst()
        .orElseThrow(() -> new BadRequestException("error.patch_change.home_location_not_offered"));
  }

  /**
   * Describes a location as an option.
   *
   * @param location the location
   * @return the option with the star system of its city or station
   */
  static @NotNull HomeLocationOptionDto toOption(@NotNull Location location) {
    return new HomeLocationOptionDto(location.getId(), location.getName(), systemOf(location));
  }

  /**
   * Reads the curated home locations.
   *
   * @return the locations, hidden and reference-only ones left out
   */
  private @NotNull List<Location> curated() {
    return locations.findByHomeLocationTrueAndHiddenFalseAndReferenceOnlyFalseOrderByNameDesc();
  }

  /**
   * Names the star system a location lies in.
   *
   * @param location the location
   * @return the system of its city, else of its station, else {@code null}
   */
  private static @Nullable String systemOf(@NotNull Location location) {
    if (location.getCity() != null) {
      return location.getCity().getStarSystemName();
    }
    if (location.getSpaceStation() != null) {
      return location.getSpaceStation().getStarSystemName();
    }
    return null;
  }
}
