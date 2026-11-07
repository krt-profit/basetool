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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDomain;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditService;
import de.greluc.krt.profit.basetool.backend.catalogue.api.City;
import de.greluc.krt.profit.basetool.backend.catalogue.api.CityRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobRepository;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.DuplicateEntityException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

/**
 * A storage place sits on a city, a space station or a visible starmap node an admin picks, and a
 * linked place carries the P4K names of its object (REQ-P4K-036, REQ-P4K-037).
 */
@SpringBootTest
@Transactional
class LocationStarmapNodeTest {

  private static final UUID SYSTEM = UUID.fromString("5a000000-0000-4000-8000-000000000031");
  private static final UUID PLANET = UUID.fromString("5a000000-0000-4000-8000-000000000032");
  private static final UUID OUTPOST = UUID.fromString("5a000000-0000-4000-8000-000000000033");
  private static final UUID HIDDEN = UUID.fromString("5a000000-0000-4000-8000-000000000034");
  private static final UUID CITY = UUID.fromString("5a000000-0000-4000-8000-000000000035");

  @Autowired private P4kImportService service;
  @Autowired private P4kImportJobRepository jobRepository;
  @Autowired private StarmapObjectRepository objectRepository;
  @Autowired private StarSystemRepository starSystemRepository;
  @Autowired private PlanetRepository planetRepository;
  @Autowired private CityRepository cityRepository;
  @Autowired private LocationService locationService;
  @Autowired private LocationRepository locationRepository;
  @Autowired private AuditService auditService;

  private P4kStarmapFixtures fixtures;

  @BeforeEach
  void setUp() {
    fixtures = new P4kStarmapFixtures(starSystemRepository, planetRepository, cityRepository);
    fixtures.starSystem(P4kStarmapFixtures.SYSTEM, true);
    fixtures.planet("Krt Daymar", P4kStarmapFixtures.SYSTEM);
    ObjectNode hidden = P4kStarmapFixtures.object(HIDDEN, "Outpost", "Krt Secret Base", PLANET);
    hidden.put("hideInStarmap", true);
    new P4kImportRuns(service, jobRepository)
        .apply(
            P4kTestCatalogues.bytes(
                P4kStarmapFixtures.catalogue(
                    P4kStarmapFixtures.object(
                        SYSTEM, "SolarSystem", P4kStarmapFixtures.SYSTEM, null),
                    P4kStarmapFixtures.object(PLANET, "Planet", "Krt Daymar", SYSTEM),
                    P4kStarmapFixtures.object(OUTPOST, "Outpost", "Krt Shubin Mine", PLANET),
                    P4kStarmapFixtures.object(CITY, "LandingZone", "Krt Daymar City", PLANET),
                    hidden)),
            false);
  }

  private Location onNode(UUID guid, String name) {
    Location location = new Location();
    location.setName(name);
    location.setStarmapObjectId(objectRepository.findByP4kUuid(guid).orElseThrow().getId());
    return location;
  }

  @Test
  void anAdminCreatesAStoragePlaceOnAVisibleOutpost_auditedOnce() {
    Location saved = locationService.createLocation(onNode(OUTPOST, "Krt Mine Depot"));

    assertThat(locationRepository.findById(saved.getId())).isPresent();
    assertThat(
            auditService
                .getEvents(
                    AuditDomain.CATALOGUE,
                    null,
                    null,
                    null,
                    AuditEventType.CATALOGUE_STORAGE_PLACE_CREATED,
                    null,
                    PageRequest.of(0, 50))
                .getContent())
        .filteredOn(e -> saved.getId().equals(e.subjectId()))
        .hasSize(1);
  }

  @Test
  void aHiddenObject_orNoObject_isRefused() {
    assertThatThrownBy(() -> locationService.createLocation(onNode(HIDDEN, "Krt Secret Depot")))
        .isInstanceOf(BadRequestException.class);
    Location free = new Location();
    free.setName("Krt Free Depot");
    assertThatThrownBy(() -> locationService.createLocation(free))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void aSecondStoragePlaceOnTheSameObject_isRefused() {
    locationService.createLocation(onNode(OUTPOST, "Krt Mine Depot"));

    assertThatThrownBy(() -> locationService.createLocation(onNode(OUTPOST, "Krt Mine Depot 2")))
        .isInstanceOf(DuplicateEntityException.class);
  }

  @Test
  void aLinkedCitysPlace_carriesTheP4kNames_whileItsCuratedNameStays() {
    City city = fixtures.city("Krt Daymar City", P4kStarmapFixtures.SYSTEM, "Krt Daymar");
    Location place = new Location();
    place.setName("Daymar City (curated)");
    place.setCity(city);
    locationRepository.save(place);
    new P4kImportRuns(service, jobRepository)
        .apply(
            P4kTestCatalogues.bytes(
                P4kStarmapFixtures.catalogue(
                    P4kStarmapFixtures.object(
                        SYSTEM, "SolarSystem", P4kStarmapFixtures.SYSTEM, null),
                    P4kStarmapFixtures.object(PLANET, "Planet", "Krt Daymar", SYSTEM),
                    P4kStarmapFixtures.object(CITY, "LandingZone", "Krt Daymar City", PLANET))),
            false);

    LocationDto dto = locationService.getLocation(place.getId());

    assertThat(dto.name()).isEqualTo("Daymar City (curated)");
    assertThat(dto.p4kName()).isEqualTo("Krt Daymar City");
    assertThat(dto.p4kNameDe()).isEqualTo("Krt Daymar City (DE)");
  }
}
