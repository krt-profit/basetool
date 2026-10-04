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

package de.greluc.krt.profit.basetool.backend.service.exchange;

import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeLocationDto;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeLocationListDto;
import de.greluc.krt.profit.basetool.backend.repository.LocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.LocationRepository.ExchangeLocationRow;
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
   * Lists the Lager's non-hidden locations with their UEX place, in one query.
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
   * Maps one row; a city link wins over a space-station link.
   *
   * @param row the row
   * @return the DTO
   */
  @NotNull
  private static ExchangeLocationDto toDto(@NotNull ExchangeLocationRow row) {
    return new ExchangeLocationDto(row.name(), uex(row));
  }

  /**
   * Returns the UEX place of a row.
   *
   * @param row the row
   * @return the place, or {@code null} when the location links none
   */
  @Nullable
  private static ExchangeLocationDto.UexRef uex(@NotNull ExchangeLocationRow row) {
    if (row.uexCityId() != null && row.uexCityId() > 0) {
      return new ExchangeLocationDto.UexRef(KIND_CITY, row.uexCityId());
    }
    if (row.uexSpaceStationId() != null && row.uexSpaceStationId() > 0) {
      return new ExchangeLocationDto.UexRef(KIND_SPACE_STATION, row.uexSpaceStationId());
    }
    return null;
  }
}
