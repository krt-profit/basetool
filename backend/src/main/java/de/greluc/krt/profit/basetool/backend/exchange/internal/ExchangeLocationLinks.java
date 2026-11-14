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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository.ExchangeLocationGuid;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeLocationDto;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the UEX places and starmap GUIDs of locations known by id, so the exchange names them as
 * the stock feed does (REQ-XCH-018).
 */
@Component
@RequiredArgsConstructor
class ExchangeLocationLinks {

  private final LocationRepository locationRepository;

  /**
   * Reads the links of the given locations, one query for the locations and one for the GUIDs.
   *
   * @param ids the locations
   * @return the links by location; a location that no longer exists is absent
   */
  @Transactional(readOnly = true)
  @NotNull
  @Unmodifiable
  Map<UUID, Links> byId(@NotNull Collection<UUID> ids) {
    if (ids.isEmpty()) {
      return Map.of();
    }
    Map<UUID, UUID> guids =
        locationRepository.findExchangeGuids(ids).stream()
            .collect(
                Collectors.toMap(
                    ExchangeLocationGuid::getLocationId, ExchangeLocationGuid::getGuid));
    return locationRepository.findAllById(ids).stream()
        .collect(
            Collectors.toUnmodifiableMap(
                Location::getId,
                location -> new Links(uex(location), guids.get(location.getId()))));
  }

  /**
   * Returns the UEX place of a location; a city link wins over a space-station link.
   *
   * @param location the location
   * @return the place, or {@code null} when the location links none
   */
  private static @Nullable ExchangeLocationDto.UexRef uex(@NotNull Location location) {
    return ExchangeCatalogService.uex(
        location.getCity() == null ? null : location.getCity().getIdCity(),
        location.getSpaceStation() == null ? null : location.getSpaceStation().getIdSpaceStation());
  }

  /**
   * The UEX place and the starmap GUID of one location.
   *
   * @param uex the UEX place, or {@code null}
   * @param guid the starmap GUID, or {@code null}
   */
  record Links(@Nullable ExchangeLocationDto.UexRef uex, @Nullable UUID guid) {

    /** The links of a location that names neither. */
    static final Links NONE = new Links(null, null);
  }
}
