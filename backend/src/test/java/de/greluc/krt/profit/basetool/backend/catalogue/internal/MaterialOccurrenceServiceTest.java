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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link MaterialOccurrenceService}: how each kind of location is named and placed,
 * and the order the occurrences are listed in (REQ-DATA-031).
 */
@ExtendWith(MockitoExtension.class)
class MaterialOccurrenceServiceTest {

  private final UUID materialId = UUID.randomUUID();

  @Mock private MaterialOccurrenceRepository occurrenceRepository;

  private MaterialOccurrenceService service;

  @BeforeEach
  void setUp() {
    service = new MaterialOccurrenceService(occurrenceRepository);
  }

  @Test
  @DisplayName("each kind names its star system and the body it belongs to")
  void mapsEachKindWithItsPlacement() {
    StarSystem stanton = new StarSystem();
    stanton.setId(UUID.randomUUID());
    stanton.setName("Stanton");
    Planet hurston = new Planet();
    hurston.setId(UUID.randomUUID());
    hurston.setName("Hurston");
    hurston.setStarSystemName("Stanton");
    Moon aberdeen = new Moon();
    aberdeen.setId(UUID.randomUUID());
    aberdeen.setName("Aberdeen");
    aberdeen.setStarSystemName("Stanton");
    aberdeen.setPlanetName("Hurston");
    Orbit orbit = new Orbit();
    orbit.setId(UUID.randomUUID());
    orbit.setName("Aaron Halo");
    orbit.setStarSystemName("Stanton");
    Poi poi = new Poi();
    poi.setId(UUID.randomUUID());
    poi.setName("Cave");
    poi.setStarSystemName("Stanton");
    poi.setPlanetName("Hurston");
    poi.setMoonName("Aberdeen");

    when(occurrenceRepository.findWithLocationsByMaterialId(materialId))
        .thenReturn(
            List.of(
                withPoi(poi),
                withOrbit(orbit),
                withMoon(aberdeen),
                withPlanet(hurston),
                withStarSystem(stanton)));

    assertEquals(
        List.of(
            new MaterialOccurrenceDto(
                stanton.getId(), MaterialOccurrenceKind.STAR_SYSTEM, "Stanton", null, null),
            new MaterialOccurrenceDto(
                hurston.getId(), MaterialOccurrenceKind.PLANET, "Hurston", "Stanton", null),
            new MaterialOccurrenceDto(
                aberdeen.getId(), MaterialOccurrenceKind.MOON, "Aberdeen", "Stanton", "Hurston"),
            new MaterialOccurrenceDto(
                orbit.getId(), MaterialOccurrenceKind.ORBIT, "Aaron Halo", "Stanton", null),
            new MaterialOccurrenceDto(
                poi.getId(), MaterialOccurrenceKind.POI, "Cave", "Stanton", "Aberdeen")),
        service.getOccurrences(materialId));
  }

  @Test
  @DisplayName("within a kind the locations are ordered by name, case-insensitively")
  void ordersByNameWithinKind() {
    Moon yela = moon("Yela");
    Moon daymar = moon("daymar");
    Moon cellin = moon("Cellin");
    when(occurrenceRepository.findWithLocationsByMaterialId(materialId))
        .thenReturn(List.of(withMoon(yela), withMoon(daymar), withMoon(cellin)));

    assertEquals(
        List.of("Cellin", "daymar", "Yela"),
        service.getOccurrences(materialId).stream().map(MaterialOccurrenceDto::name).toList());
  }

  @Test
  @DisplayName("a point of interest without a moon names its planet")
  void poiFallsBackToPlanet() {
    Poi poi = new Poi();
    poi.setId(UUID.randomUUID());
    poi.setName("Outpost");
    poi.setPlanetName("Hurston");
    poi.setMoonName("");

    assertEquals("Hurston", MaterialOccurrenceService.toDto(withPoi(poi)).parentName());
  }

  private static Moon moon(String name) {
    Moon moon = new Moon();
    moon.setId(UUID.randomUUID());
    moon.setName(name);
    return moon;
  }

  private static MaterialOccurrence withStarSystem(StarSystem starSystem) {
    MaterialOccurrence occurrence = new MaterialOccurrence();
    occurrence.setStarSystem(starSystem);
    return occurrence;
  }

  private static MaterialOccurrence withPlanet(Planet planet) {
    MaterialOccurrence occurrence = new MaterialOccurrence();
    occurrence.setPlanet(planet);
    return occurrence;
  }

  private static MaterialOccurrence withMoon(Moon moon) {
    MaterialOccurrence occurrence = new MaterialOccurrence();
    occurrence.setMoon(moon);
    return occurrence;
  }

  private static MaterialOccurrence withOrbit(Orbit orbit) {
    MaterialOccurrence occurrence = new MaterialOccurrence();
    occurrence.setOrbit(orbit);
    return occurrence;
  }

  private static MaterialOccurrence withPoi(Poi poi) {
    MaterialOccurrence occurrence = new MaterialOccurrence();
    occurrence.setPoi(poi);
    return occurrence;
  }
}
