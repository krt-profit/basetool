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

import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeLocationRef;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.repository.LocationRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * Finds the non-hidden Lager location an exchange write names (REQ-XCH-016, REQ-XCH-017): by its
 * UEX place first, then by its exact name.
 */
@Component
@RequiredArgsConstructor
public class ExchangeLocationResolver {

  /** The UEX kind of a city. */
  private static final String CITY = "CITY";

  private final LocationRepository locationRepository;

  /**
   * Finds the location a client means.
   *
   * @param ref the client's reference
   * @return the location, or empty when the place has none
   */
  public @NotNull Optional<Location> resolve(@NotNull ExchangeLocationRef ref) {
    if (ref.uex() != null) {
      List<Location> byPlace =
          CITY.equals(ref.uex().kind())
              ? locationRepository.findExchangeByCity(ref.uex().id())
              : locationRepository.findExchangeBySpaceStation(ref.uex().id());
      if (!byPlace.isEmpty()) {
        return Optional.of(byPlace.getFirst());
      }
    }
    if (ref.name() != null) {
      return locationRepository.findFirstByNameAndHiddenFalseOrderByIdAsc(ref.name());
    }
    return Optional.empty();
  }
}
