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

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.greluc.krt.profit.basetool.backend.model.Location;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pins the curated home-location query backing the hangar bulk "set home location" picker: only
 * rows flagged {@code is_home_location} and not {@code hidden}, ordered by name descending
 * (Z-&gt;A).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LocationRepositoryTest {

  @Autowired private LocationRepository repository;

  @Test
  void findHomeLocations_returnsOnlyCuratedVisible_orderedByNameDescending() {
    repository.save(loc("HL-Test-Alpha", true, false));
    repository.save(loc("HL-Test-Charlie", true, false));
    repository.save(loc("HL-Test-Bravo", true, false));
    repository.save(loc("HL-Test-NotHome", false, false));
    repository.save(loc("HL-Test-HiddenHome", true, true));

    List<String> mine =
        repository.findByHomeLocationTrueAndHiddenFalseOrderByNameDesc().stream()
            .map(Location::getName)
            .filter(name -> name.startsWith("HL-Test-"))
            .toList();

    assertEquals(List.of("HL-Test-Charlie", "HL-Test-Bravo", "HL-Test-Alpha"), mine);
  }

  private static Location loc(String name, boolean homeLocation, boolean hidden) {
    Location location = new Location();
    location.setName(name);
    location.setHomeLocation(homeLocation);
    location.setHidden(hidden);
    return location;
  }
}
