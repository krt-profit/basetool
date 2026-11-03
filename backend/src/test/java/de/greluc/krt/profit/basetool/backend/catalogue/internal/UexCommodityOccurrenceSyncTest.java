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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.UexKeyRef;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexCommodityDto;
import de.greluc.krt.profit.basetool.backend.service.RecordingTransactionManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link UexCommodityOccurrenceSync}: the diff of a material's stored locations
 * against the UEX {@code ids_*} lists, and the skipping of unknown ids (REQ-DATA-031).
 */
@ExtendWith(MockitoExtension.class)
class UexCommodityOccurrenceSyncTest {

  private static final int COMMODITY_ID = 1;
  private static final int KNOWN_MOON_ID = 18;
  private static final int UNKNOWN_MOON_ID = 99;
  private static final int KNOWN_PLANET_ID = 5;
  private static final int KNOWN_ORBIT_ID = 7;

  private final UUID materialId = UUID.randomUUID();
  private final UUID moonId = UUID.randomUUID();
  private final UUID planetId = UUID.randomUUID();
  private final UUID orbitId = UUID.randomUUID();

  @Mock private MaterialRepository materialRepository;
  @Mock private MaterialOccurrenceRepository occurrenceRepository;
  @Mock private StarSystemRepository starSystemRepository;
  @Mock private PlanetRepository planetRepository;
  @Mock private MoonRepository moonRepository;
  @Mock private OrbitRepository orbitRepository;
  @Mock private PoiRepository poiRepository;

  @Captor private ArgumentCaptor<Iterable<MaterialOccurrence>> savedCaptor;
  @Captor private ArgumentCaptor<Iterable<MaterialOccurrence>> deletedCaptor;

  private UexCommodityOccurrenceSync sync;

  @BeforeEach
  void setUp() {
    sync =
        new UexCommodityOccurrenceSync(
            materialRepository,
            occurrenceRepository,
            starSystemRepository,
            planetRepository,
            moonRepository,
            orbitRepository,
            poiRepository,
            new SyncChunkWriter(new RecordingTransactionManager()));
    lenient()
        .when(materialRepository.findUexCommodityRefs())
        .thenReturn(List.of(new Ref(COMMODITY_ID, materialId)));
    lenient().when(starSystemRepository.findUexRefs()).thenReturn(List.of());
    lenient()
        .when(planetRepository.findUexRefs())
        .thenReturn(List.of(new Ref(KNOWN_PLANET_ID, planetId)));
    lenient()
        .when(moonRepository.findUexRefs())
        .thenReturn(List.of(new Ref(KNOWN_MOON_ID, moonId)));
    lenient()
        .when(orbitRepository.findUexRefs())
        .thenReturn(List.of(new Ref(KNOWN_ORBIT_ID, orbitId)));
    lenient().when(poiRepository.findUexRefs()).thenReturn(List.of());
    lenient().when(materialRepository.getReferenceById(materialId)).thenReturn(material());
    lenient().when(planetRepository.getReferenceById(planetId)).thenReturn(planet());
    lenient().when(moonRepository.getReferenceById(moonId)).thenReturn(moon());
  }

  @Test
  @DisplayName("listed locations with a local row are inserted, unknown ids are skipped")
  void insertsResolvedLocationsAndSkipsUnknownIds() {
    when(occurrenceRepository.findByMaterialIds(Set.of(materialId))).thenReturn(List.of());

    sync.replaceAll(
        List.of(
            commodity(
                COMMODITY_ID,
                String.valueOf(KNOWN_PLANET_ID),
                KNOWN_MOON_ID + "," + UNKNOWN_MOON_ID)));

    verify(occurrenceRepository).saveAll(savedCaptor.capture());
    verify(occurrenceRepository, never()).deleteAllInBatch(any());
    assertEquals(
        Set.of(
            new MaterialOccurrenceTarget(MaterialOccurrenceKind.PLANET, planetId),
            new MaterialOccurrenceTarget(MaterialOccurrenceKind.MOON, moonId)),
        targets(savedCaptor.getValue()));
  }

  @Test
  @DisplayName("a stored location still listed stays, one no longer listed is deleted")
  void keepsListedAndDeletesUnlistedLocations() {
    MaterialOccurrence storedMoon = occurrence();
    storedMoon.setMoon(moon());
    MaterialOccurrence storedOrbit = occurrence();
    Orbit orbit = new Orbit();
    orbit.setId(orbitId);
    storedOrbit.setOrbit(orbit);
    when(occurrenceRepository.findByMaterialIds(Set.of(materialId)))
        .thenReturn(List.of(storedMoon, storedOrbit));

    sync.replaceAll(List.of(commodity(COMMODITY_ID, null, String.valueOf(KNOWN_MOON_ID))));

    verify(occurrenceRepository).deleteAllInBatch(deletedCaptor.capture());
    verify(occurrenceRepository).saveAll(savedCaptor.capture());
    assertEquals(List.of(storedOrbit), list(deletedCaptor.getValue()));
    assertEquals(List.of(), list(savedCaptor.getValue()));
  }

  @Test
  @DisplayName("an empty list clears the material's stored locations")
  void emptyListsClearStoredLocations() {
    MaterialOccurrence storedMoon = occurrence();
    storedMoon.setMoon(moon());
    when(occurrenceRepository.findByMaterialIds(Set.of(materialId)))
        .thenReturn(List.of(storedMoon));

    sync.replaceAll(List.of(commodity(COMMODITY_ID, "", null)));

    verify(occurrenceRepository).deleteAllInBatch(deletedCaptor.capture());
    assertEquals(List.of(storedMoon), list(deletedCaptor.getValue()));
  }

  @Test
  @DisplayName("a commodity without a local material, or without an id, touches nothing")
  void commodityWithoutMaterialIsSkipped() {
    sync.replaceAll(
        List.of(commodity(2, String.valueOf(KNOWN_PLANET_ID), null), commodity(null, "5", null)));

    verify(occurrenceRepository, never()).findByMaterialIds(any());
    verify(occurrenceRepository, never()).saveAll(any());
  }

  private static Set<MaterialOccurrenceTarget> targets(Iterable<MaterialOccurrence> rows) {
    return list(rows).stream().map(MaterialOccurrence::target).collect(Collectors.toSet());
  }

  private static List<MaterialOccurrence> list(Iterable<MaterialOccurrence> rows) {
    List<MaterialOccurrence> out = new ArrayList<>();
    rows.forEach(out::add);
    return out;
  }

  private MaterialOccurrence occurrence() {
    MaterialOccurrence occurrence = new MaterialOccurrence();
    occurrence.setId(UUID.randomUUID());
    occurrence.setMaterial(material());
    return occurrence;
  }

  private Material material() {
    Material material = new Material();
    material.setId(materialId);
    return material;
  }

  private Planet planet() {
    Planet planet = new Planet();
    planet.setId(planetId);
    return planet;
  }

  private Moon moon() {
    Moon moon = new Moon();
    moon.setId(moonId);
    return moon;
  }

  private static UexCommodityDto commodity(Integer id, String planets, String moons) {
    return UexCommodityDto.builder()
        .id(id)
        .name("Commodity-" + id)
        .idsPlanets(planets)
        .idsMoons(moons)
        .build();
  }

  /** A fixed (UEX id, local id) projection row. */
  private record Ref(Integer uexId, UUID id) implements UexKeyRef {

    @Override
    public Integer getUexId() {
      return uexId;
    }

    @Override
    public UUID getId() {
      return id;
    }
  }
}
