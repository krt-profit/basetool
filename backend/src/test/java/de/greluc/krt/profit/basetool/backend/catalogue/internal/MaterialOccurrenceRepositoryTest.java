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
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pins the {@code material_occurrence} schema and its queries against the Flyway-built Postgres
 * schema: one location per row, one row per material and location, and the fetch query the read
 * endpoint uses (REQ-DATA-031).
 */
@SpringBootTest
@Transactional
class MaterialOccurrenceRepositoryTest {

  /** UEX ids far above anything the seed data carries. */
  private static final int TEST_UEX_ID = 990_001;

  @Autowired private MaterialOccurrenceRepository occurrenceRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private PlanetRepository planetRepository;
  @Autowired private MoonRepository moonRepository;

  private Material material;
  private Planet planet;
  private Moon moon;

  @BeforeEach
  void setUp() {
    Material newMaterial = new Material();
    newMaterial.setName("Occurrence-Test-" + UUID.randomUUID());
    newMaterial.setType(MaterialType.RAW);
    material = materialRepository.save(newMaterial);
    Planet newPlanet = new Planet();
    newPlanet.setIdPlanet(TEST_UEX_ID);
    newPlanet.setName("Occurrence-Test-Planet");
    newPlanet.setStarSystemName("Stanton");
    planet = planetRepository.save(newPlanet);
    Moon newMoon = new Moon();
    newMoon.setIdMoon(TEST_UEX_ID);
    newMoon.setName("Occurrence-Test-Moon");
    newMoon.setPlanetName("Occurrence-Test-Planet");
    moon = moonRepository.save(newMoon);
  }

  @Test
  void findWithLocationsByMaterialId_returnsEachOccurrenceWithItsLocation() {
    occurrenceRepository.saveAndFlush(atPlanet());
    occurrenceRepository.saveAndFlush(atMoon());

    List<MaterialOccurrence> found =
        occurrenceRepository.findWithLocationsByMaterialId(material.getId());

    assertEquals(
        Set.of(
            new MaterialOccurrenceTarget(MaterialOccurrenceKind.PLANET, planet.getId()),
            new MaterialOccurrenceTarget(MaterialOccurrenceKind.MOON, moon.getId())),
        Set.copyOf(found.stream().map(MaterialOccurrence::target).toList()));
    assertEquals(2, occurrenceRepository.findByMaterialIds(Set.of(material.getId())).size());
    assertEquals(List.of(), occurrenceRepository.findWithLocationsByMaterialId(UUID.randomUUID()));
  }

  @Test
  void aRowNamingTwoLocationsIsRejected() {
    MaterialOccurrence both = atPlanet();
    both.setMoon(moon);

    assertThrows(
        DataIntegrityViolationException.class, () -> occurrenceRepository.saveAndFlush(both));
  }

  @Test
  void aRowNamingNoLocationIsRejected() {
    MaterialOccurrence none = new MaterialOccurrence();
    none.setMaterial(material);

    assertThrows(
        DataIntegrityViolationException.class, () -> occurrenceRepository.saveAndFlush(none));
  }

  @Test
  void theSameLocationTwiceForOneMaterialIsRejected() {
    occurrenceRepository.saveAndFlush(atMoon());

    assertThrows(
        DataIntegrityViolationException.class, () -> occurrenceRepository.saveAndFlush(atMoon()));
  }

  private MaterialOccurrence atPlanet() {
    MaterialOccurrence occurrence = new MaterialOccurrence();
    occurrence.setMaterial(material);
    occurrence.setPlanet(planet);
    return occurrence;
  }

  private MaterialOccurrence atMoon() {
    MaterialOccurrence occurrence = new MaterialOccurrence();
    occurrence.setMaterial(material);
    occurrence.setMoon(moon);
    return occurrence;
  }
}
