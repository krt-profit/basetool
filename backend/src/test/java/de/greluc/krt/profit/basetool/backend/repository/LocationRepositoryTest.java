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

package de.greluc.krt.profit.basetool.backend.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import de.greluc.krt.profit.basetool.backend.catalogue.api.City;
import de.greluc.krt.profit.basetool.backend.catalogue.api.CityRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationReferenceDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pins the curated home-location query backing the hangar bulk "set home location" picker: only
 * rows flagged {@code is_home_location} and not {@code hidden}, ordered by name descending
 * (Z-&gt;A); and that no list, picker or exchange read offers a reference-only row (REQ-P4K-039).
 */
@SpringBootTest
@Transactional
class LocationRepositoryTest {

  private static final PageRequest PAGE = PageRequest.of(0, 500);

  @Autowired private LocationRepository repository;
  @Autowired private CityRepository cityRepository;

  @Test
  void findHomeLocations_returnsOnlyCuratedVisible_orderedByNameDescending() {
    repository.save(loc("HL-Test-Alpha", true, false));
    repository.save(loc("HL-Test-Charlie", true, false));
    repository.save(loc("HL-Test-Bravo", true, false));
    repository.save(loc("HL-Test-NotHome", false, false));
    repository.save(loc("HL-Test-HiddenHome", true, true));

    List<String> mine =
        repository
            .findByHomeLocationTrueAndHiddenFalseAndReferenceOnlyFalseOrderByNameDesc()
            .stream()
            .map(Location::getName)
            .filter(name -> name.startsWith("HL-Test-"))
            .toList();

    assertEquals(List.of("HL-Test-Charlie", "HL-Test-Bravo", "HL-Test-Alpha"), mine);
  }

  @Test
  void aReferenceOnlyRowIsInNoListPickerOrExchangeRead() {
    City city = new City();
    city.setIdCity(990_339);
    city.setName("RO-Test City");
    city.setHasRefineryTerminal(true);
    city = cityRepository.save(city);
    Location offered = loc("RO-Test Offered", true, false);
    Location marked = loc("RO-Test City", true, false);
    marked.setCity(city);
    marked.setReferenceOnly(true);
    repository.save(offered);
    repository.save(marked);

    assertThat(repository.findAllReference())
        .extracting(LocationReferenceDto::name)
        .contains("RO-Test Offered")
        .doesNotContain("RO-Test City");
    assertThat(repository.searchReference("RO-Test", PAGE).map(LocationReferenceDto::name))
        .containsExactly("RO-Test Offered");
    assertThat(repository.findByReferenceOnlyFalse(PAGE).map(Location::getName))
        .doesNotContain("RO-Test City");
    assertThat(repository.findByHiddenFalseAndReferenceOnlyFalse(PAGE).map(Location::getName))
        .doesNotContain("RO-Test City");
    assertThat(
            repository
                .findByReferenceOnlyFalseAndNameContainingIgnoreCase("RO-Test", PAGE)
                .map(Location::getName))
        .containsExactly("RO-Test Offered");
    assertThat(
            repository
                .findByHiddenFalseAndReferenceOnlyFalseAndNameContainingIgnoreCase("RO-Test", PAGE)
                .map(Location::getName))
        .containsExactly("RO-Test Offered");
    assertThat(
            repository.findByHomeLocationTrueAndHiddenFalseAndReferenceOnlyFalseOrderByNameDesc())
        .extracting(Location::getName)
        .doesNotContain("RO-Test City");
    assertThat(repository.findLocationsWithRefinery()).doesNotContain(marked);
    assertThat(repository.findExchangeLocations())
        .extracting(LocationRepository.ExchangeLocationRow::getName)
        .doesNotContain("RO-Test City");
    assertThat(repository.findExchangeByCity(990_339)).isEmpty();
    assertThat(
            repository.findFirstByNameAndHiddenFalseAndReferenceOnlyFalseOrderByIdAsc(
                "RO-Test City"))
        .isEmpty();
    assertThat(repository.findByCityId(city.getId())).contains(marked);
  }

  private static Location loc(String name, boolean homeLocation, boolean hidden) {
    Location location = new Location();
    location.setName(name);
    location.setHomeLocation(homeLocation);
    location.setHidden(hidden);
    return location;
  }
}
