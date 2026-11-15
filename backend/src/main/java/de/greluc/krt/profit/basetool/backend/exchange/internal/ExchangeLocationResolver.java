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
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeLocationRef;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * Finds the non-hidden Lager location an exchange write names (REQ-XCH-016, REQ-XCH-017,
 * REQ-XCH-026): by its starmap GUID first, then by its UEX place, then by its exact name, then by
 * the name the exchange cut its longer name to; a reference that does not match at one step falls
 * through to the next.
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
    if (ref.guid() != null) {
      Optional<Location> byGuid = locationRepository.findExchangeByGuid(ref.guid());
      if (byGuid.isPresent()) {
        return byGuid;
      }
    }
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
      Optional<Location> exact =
          locationRepository.findFirstByNameAndHiddenFalseAndReferenceOnlyFalseOrderByIdAsc(
              ref.name());
      return exact.isPresent() ? exact : byCutName(ref.name());
    }
    return Optional.empty();
  }

  /**
   * Finds the one place whose longer name the exchange cut to the name a client sent back.
   *
   * @param name the name the client sent
   * @return the place, or empty when no place or several places cut to that name
   */
  private @NotNull Optional<Location> byCutName(@NotNull String name) {
    List<Location> cut =
        ExchangeNames.cutTo(
            name,
            locationRepository::findAllByNameStartingWithAndHiddenFalseAndReferenceOnlyFalse,
            Location::getName);
    return cut.size() == 1 ? Optional.of(cut.getFirst()) : Optional.empty();
  }
}
