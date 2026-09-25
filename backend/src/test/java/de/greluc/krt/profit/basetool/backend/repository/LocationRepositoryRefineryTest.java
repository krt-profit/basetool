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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.greluc.krt.profit.basetool.backend.model.City;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.SpaceStation;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pins the refinery-location picker source (REQ-REFINERY-020): eligibility follows the derived
 * {@code hasRefineryTerminal} flag, never UEX's parent-level {@code hasRefinery} claim.
 *
 * <p>The two disagreement cases are the reported bug. UEX reports {@code has_refinery = 0} for
 * MIC-L5 Modern Icarus Station, ARC-L4 Faint Glen Station and Patch City while listing a live
 * refinery terminal at each, and reports {@code has_refinery = 1} for four People's Service
 * Stations that host no refinery terminal at all. How the flag itself is recomputed from the
 * terminal table is pinned by {@code UexUniverseSyncRefineryFlagTest}.
 *
 * <p>Eligibility carries a second condition: the location must not be hidden. This query was once
 * the only Location lookup that ignored the admin's {@code hidden} flag, which let a hidden
 * refinery stay selectable here while disappearing from the storage pickers on the very same page.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class LocationRepositoryRefineryTest {

  @Autowired private LocationRepository locationRepository;

  @Autowired private CityRepository cityRepository;

  @Autowired private SpaceStationRepository spaceStationRepository;

  /**
   * Persists a station-backed location.
   *
   * @param name station name; the location is named after it
   * @param uexClaim UEX's raw (untrusted) {@code has_refinery} flag
   * @param derived the reconciled {@code has_refinery_terminal} flag the picker reads
   * @return the persisted location
   */
  private Location saveStationLocation(String name, boolean uexClaim, boolean derived) {
    SpaceStation station = new SpaceStation();
    station.setName(name);
    station.setHasRefinery(uexClaim);
    station.setHasRefineryTerminal(derived);
    spaceStationRepository.save(station);

    Location location = new Location();
    location.setName(name + " Loc");
    location.setSpaceStation(station);
    return locationRepository.save(location);
  }

  @Test
  public void testFindLocationsWithRefinery() {
    City city = new City();
    city.setName("Refinery City");
    city.setHasRefineryTerminal(true);
    cityRepository.save(city);

    Location cityLocation = new Location();
    cityLocation.setName("Refinery City Loc");
    cityLocation.setCity(city);
    locationRepository.save(cityLocation);

    Location stationLocation = saveStationLocation("Refinery Station", false, true);

    City plainCity = new City();
    plainCity.setName("Normal City");
    plainCity.setHasRefineryTerminal(false);
    cityRepository.save(plainCity);

    Location plainLocation = new Location();
    plainLocation.setName("Normal City Loc");
    plainLocation.setCity(plainCity);
    locationRepository.save(plainLocation);

    locationRepository.flush();

    List<Location> refineries = locationRepository.findLocationsWithRefinery();

    assertTrue(refineries.contains(cityLocation));
    assertTrue(refineries.contains(stationLocation));
    assertFalse(refineries.contains(plainLocation));
  }

  @Test
  public void testUexClaimFalseButRefineryTerminalPresentIsIncluded() {
    Location micL5 = saveStationLocation("MIC-L5 Modern Icarus Station", false, true);
    locationRepository.flush();

    assertTrue(locationRepository.findLocationsWithRefinery().contains(micL5));
  }

  @Test
  public void testUexClaimTrueWithoutRefineryTerminalIsExcluded() {
    Location bogus = saveStationLocation("People's Service Station Alpha", true, false);
    locationRepository.flush();

    assertFalse(locationRepository.findLocationsWithRefinery().contains(bogus));
  }

  @Test
  public void testHiddenStationBackedRefineryIsExcluded() {
    Location hidden = saveStationLocation("Hidden Refinery Station", false, true);
    hidden.setHidden(true);
    locationRepository.save(hidden);
    locationRepository.flush();

    assertFalse(locationRepository.findLocationsWithRefinery().contains(hidden));
  }

  @Test
  public void testHiddenCityBackedRefineryIsExcluded() {
    City city = new City();
    city.setName("Hidden Refinery City");
    city.setHasRefineryTerminal(true);
    cityRepository.save(city);

    Location hidden = new Location();
    hidden.setName("Hidden Refinery City Loc");
    hidden.setCity(city);
    hidden.setHidden(true);
    locationRepository.save(hidden);
    locationRepository.flush();

    assertFalse(locationRepository.findLocationsWithRefinery().contains(hidden));
  }

  @Test
  public void testVisibleRefineryIsStillOfferedAlongsideAHiddenOne() {
    Location visible = saveStationLocation("Visible Refinery Station", false, true);
    Location hidden = saveStationLocation("Suppressed Refinery Station", false, true);
    hidden.setHidden(true);
    locationRepository.save(hidden);
    locationRepository.flush();

    List<Location> refineries = locationRepository.findLocationsWithRefinery();

    assertTrue(refineries.contains(visible));
    assertFalse(refineries.contains(hidden));
  }
}
