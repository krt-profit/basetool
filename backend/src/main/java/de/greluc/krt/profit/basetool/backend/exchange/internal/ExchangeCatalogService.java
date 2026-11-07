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

import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository.ExchangeLocationRow;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeLocationDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeLocationListDto;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The exchange catalogue: reference data external clients offer their members (REQ-XCH-018). */
@Service
@RequiredArgsConstructor
public class ExchangeCatalogService {

  /** The UEX kind of a location linked to a city. */
  static final String KIND_CITY = "CITY";

  /** The UEX kind of a location linked to a space station. */
  static final String KIND_SPACE_STATION = "SPACE_STATION";

  private final LocationRepository locationRepository;

  /**
   * Lists the Lager's non-hidden locations with their UEX place and starmap GUID, in one query.
   *
   * @return the locations, ordered by name
   */
  @NotNull
  @Transactional(readOnly = true)
  public ExchangeLocationListDto locations() {
    return new ExchangeLocationListDto(
        locationRepository.findExchangeLocations().stream()
            .map(ExchangeCatalogService::toDto)
            .toList());
  }

  /**
   * Maps one row.
   *
   * @param row the row
   * @return the DTO
   */
  @NotNull
  private static ExchangeLocationDto toDto(@NotNull ExchangeLocationRow row) {
    return new ExchangeLocationDto(
        row.getName(), uex(row.getUexCityId(), row.getUexSpaceStationId()), row.getGuid());
  }

  /**
   * Returns the UEX place of a location; a city link wins over a space-station link.
   *
   * @param uexCityId the UEX id of the location's city, or {@code null}
   * @param uexSpaceStationId the UEX id of the location's space station, or {@code null}
   * @return the place, or {@code null} when the location links none
   */
  static @Nullable ExchangeLocationDto.UexRef uex(
      @Nullable Integer uexCityId, @Nullable Integer uexSpaceStationId) {
    if (uexCityId != null && uexCityId > 0) {
      return new ExchangeLocationDto.UexRef(KIND_CITY, uexCityId);
    }
    if (uexSpaceStationId != null && uexSpaceStationId > 0) {
      return new ExchangeLocationDto.UexRef(KIND_SPACE_STATION, uexSpaceStationId);
    }
    return null;
  }
}
