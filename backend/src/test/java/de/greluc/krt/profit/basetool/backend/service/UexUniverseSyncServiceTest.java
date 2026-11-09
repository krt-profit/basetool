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

package de.greluc.krt.profit.basetool.backend.service;

import static de.greluc.krt.profit.basetool.backend.service.UexFetchResults.fetched;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.catalogue.api.City;
import de.greluc.krt.profit.basetool.backend.catalogue.api.CityRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.SpaceStation;
import de.greluc.krt.profit.basetool.backend.catalogue.api.SpaceStationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Terminal;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.Faction;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.FactionRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.Jurisdiction;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.JurisdictionRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.Moon;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.MoonRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.Orbit;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.OrbitRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.Outpost;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.OutpostRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.Planet;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.PlanetRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.Poi;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.PoiRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.SyncChunkWriter;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.SyncRunProgress;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.TerminalRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.UexClient;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.UexUniverseSyncService;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexCityDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexFactionDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexJurisdictionDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexMoonDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexOrbitDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexOutpostDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexPlanetDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexPoiDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexSpaceStationDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexTerminalDto;
import de.greluc.krt.profit.basetool.backend.repository.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UexUniverseSyncServiceTest {

  @Mock private UexClient uexClient;

  @Mock private CityRepository cityRepository;
  @Mock private FactionRepository factionRepository;
  @Mock private JurisdictionRepository jurisdictionRepository;
  @Mock private MoonRepository moonRepository;
  @Mock private OrbitRepository orbitRepository;
  @Mock private OutpostRepository outpostRepository;
  @Mock private PlanetRepository planetRepository;
  @Mock private PoiRepository poiRepository;
  @Mock private SpaceStationRepository spaceStationRepository;
  @Mock private TerminalRepository terminalRepository;

  @Mock private LocationRepository locationRepository;

  /** A real chunk writer, so the rows are actually written through its callbacks (BE-PERF-09). */
  @Spy
  private SyncChunkWriter chunkWriter =
      new SyncChunkWriter(new RecordingTransactionManager(), new SyncRunProgress());

  @InjectMocks private UexUniverseSyncService service;

  @Test
  void shouldSyncCitiesSuccessfully() {
    UexCityDto cityDto = UexCityDto.builder().id(1).name("Lorville").isAvailableLive(1).build();

    when(uexClient.getCities()).thenReturn(fetched(List.of(cityDto)));
    when(cityRepository.findByIdCity(1)).thenReturn(Optional.empty());
    when(cityRepository.findByName("Lorville")).thenReturn(Optional.empty());
    when(cityRepository.save(any(City.class)))
        .thenAnswer(invocation -> {
          City c = invocation.getArgument(0);
          if (c.getId() == null) c.setId(java.util.UUID.randomUUID());
          return c;
        });

    de.greluc.krt.profit.basetool.backend.catalogue.api.Location mockLocation =
        new de.greluc.krt.profit.basetool.backend.catalogue.api.Location();
    when(locationRepository.findByCityId(any())).thenReturn(Optional.of(mockLocation));
    when(locationRepository.save(any())).thenReturn(mockLocation);

    service.syncCities();

    verify(uexClient, times(1)).getCities();
    verify(cityRepository, times(2)).save(any(City.class));
  }

  @Test
  void shouldSkipSyncIfNoData() {
    when(uexClient.getCities()).thenReturn(fetched(List.of()));

    service.syncCities();

    verify(uexClient, times(1)).getCities();
    verify(cityRepository, never()).save(any());
  }

  @Test
  void syncCities_skipsDtoWithoutId() {
    UexCityDto noId = UexCityDto.builder().name("Unknown").build();
    when(uexClient.getCities()).thenReturn(fetched(List.of(noId)));

    service.syncCities();

    verify(cityRepository, never()).save(any());
    verify(locationRepository, never()).save(any());
  }

  @Test
  void syncCities_doesNotMaterialiseLocation_whenCityNotAvailableLive() {
    UexCityDto offline = UexCityDto.builder().id(2).name("Old Lorville").isAvailableLive(0).build();
    when(uexClient.getCities()).thenReturn(fetched(List.of(offline)));
    when(cityRepository.findByIdCity(2)).thenReturn(Optional.empty());
    when(cityRepository.findByName("Old Lorville")).thenReturn(Optional.empty());
    when(cityRepository.save(any(City.class)))
        .thenAnswer(invocation -> {
          City c = invocation.getArgument(0);
          if (c.getId() == null) c.setId(UUID.randomUUID());
          return c;
        });

    service.syncCities();

    verify(cityRepository, atLeastOnce()).save(any(City.class));
    verify(locationRepository, never()).save(any());
  }

  @Test
  void syncCities_linksByName_whenIdCityIsNewButNameMatches() {
    City existing = new City();
    existing.setId(UUID.randomUUID());
    existing.setName("Area18");

    UexCityDto dto = UexCityDto.builder().id(7).name("Area18").isAvailableLive(0).build();
    when(uexClient.getCities()).thenReturn(fetched(List.of(dto)));
    when(cityRepository.findByIdCity(7)).thenReturn(Optional.empty());
    when(cityRepository.findByName("Area18")).thenReturn(Optional.of(existing));
    when(cityRepository.save(any(City.class))).thenAnswer(i -> i.getArgument(0));

    service.syncCities();

    ArgumentCaptor<City> cap = ArgumentCaptor.forClass(City.class);
    verify(cityRepository, atLeastOnce()).save(cap.capture());
    assertEquals(7, cap.getValue().getIdCity());
  }

  @Test
  void syncFactions_upsertsNewFaction() {
    UexFactionDto dto =
        UexFactionDto.builder()
            .id(1)
            .name("UEE")
            .wiki("https://wiki/UEE")
            .isPiracy(0)
            .isBountyHunting(1)
            .build();
    when(uexClient.getFactions()).thenReturn(fetched(List.of(dto)));
    when(factionRepository.findByIdFaction(1)).thenReturn(Optional.empty());
    when(factionRepository.findByName("UEE")).thenReturn(Optional.empty());
    when(factionRepository.save(any(Faction.class))).thenAnswer(i -> i.getArgument(0));

    service.syncFactions();

    ArgumentCaptor<Faction> cap = ArgumentCaptor.forClass(Faction.class);
    verify(factionRepository, atLeastOnce()).save(cap.capture());
    Faction saved = cap.getValue();
    assertEquals(1, saved.getIdFaction());
    assertEquals("UEE", saved.getName());
    assertNull(saved.getCode());
    assertNull(saved.getIsAvailableLive());
    assertFalse(saved.getIsPiracy());
    assertTrue(saved.getIsBountyHunting());
  }

  @Test
  void syncFactions_skipsDtoWithoutId() {
    UexFactionDto noId = UexFactionDto.builder().name("X").build();
    when(uexClient.getFactions()).thenReturn(fetched(List.of(noId)));

    service.syncFactions();

    verify(factionRepository, never()).save(any());
  }

  @Test
  void syncFactions_emptyResponse_doesNotInvokeRepositories() {
    when(uexClient.getFactions()).thenReturn(fetched(List.of()));
    service.syncFactions();
    verifyNoInteractions(factionRepository);
  }

  @Test
  void syncJurisdictions_upsertsNewJurisdiction() {
    UexJurisdictionDto dto =
        UexJurisdictionDto.builder()
            .id(10)
            .name("Hurston Sec")
            .isAvailableLive(1)
            .nickname("HUR")
            .wiki("https://wiki")
            .factionName("HDC")
            .build();
    when(uexClient.getJurisdictions()).thenReturn(fetched(List.of(dto)));
    when(jurisdictionRepository.findByIdJurisdiction(10)).thenReturn(Optional.empty());
    when(jurisdictionRepository.findByName("Hurston Sec")).thenReturn(Optional.empty());
    when(jurisdictionRepository.save(any(Jurisdiction.class))).thenAnswer(i -> i.getArgument(0));

    service.syncJurisdictions();

    ArgumentCaptor<Jurisdiction> cap = ArgumentCaptor.forClass(Jurisdiction.class);
    verify(jurisdictionRepository, atLeastOnce()).save(cap.capture());
    assertEquals(10, cap.getValue().getIdJurisdiction());
    assertEquals("HUR", cap.getValue().getNickname());
    assertEquals("HDC", cap.getValue().getFactionName());
  }

  @Test
  void syncJurisdictions_emptyResponse_aborts() {
    when(uexClient.getJurisdictions()).thenReturn(fetched(List.of()));
    service.syncJurisdictions();
    verifyNoInteractions(jurisdictionRepository);
  }

  @Test
  void syncMoons_upsertsNewMoon() {
    UexMoonDto dto =
        UexMoonDto.builder()
            .id(20)
            .name("Lyria")
            .code("LYR")
            .isAvailableLive(1)
            .isAvailable(1)
            .isVisible(1)
            .isDefault(0)
            .starSystemName("Stanton")
            .planetName("Crusader")
            .build();
    when(uexClient.getMoons()).thenReturn(fetched(List.of(dto)));
    when(moonRepository.findByIdMoon(20)).thenReturn(Optional.empty());
    when(moonRepository.findByName("Lyria")).thenReturn(Optional.empty());
    when(moonRepository.save(any(Moon.class))).thenAnswer(i -> i.getArgument(0));

    service.syncMoons();

    ArgumentCaptor<Moon> cap = ArgumentCaptor.forClass(Moon.class);
    verify(moonRepository, atLeastOnce()).save(cap.capture());
    assertEquals(20, cap.getValue().getIdMoon());
    assertTrue(cap.getValue().getIsAvailable());
    assertTrue(cap.getValue().getIsVisible());
    assertFalse(cap.getValue().getIsDefault());
  }

  @Test
  void syncMoons_emptyResponse_aborts() {
    when(uexClient.getMoons()).thenReturn(fetched(List.of()));
    service.syncMoons();
    verifyNoInteractions(moonRepository);
  }

  @Test
  void syncOrbits_upsertsNewOrbit() {
    UexOrbitDto dto =
        UexOrbitDto.builder()
            .id(30)
            .name("HurstonOrbit")
            .code("HO")
            .isAvailableLive(1)
            .starSystemName("Stanton")
            .factionName("UEE")
            .build();
    when(uexClient.getOrbits()).thenReturn(fetched(List.of(dto)));
    when(orbitRepository.findByIdOrbit(30)).thenReturn(Optional.empty());
    when(orbitRepository.findByName("HurstonOrbit")).thenReturn(Optional.empty());
    when(orbitRepository.save(any(Orbit.class))).thenAnswer(i -> i.getArgument(0));

    service.syncOrbits();

    ArgumentCaptor<Orbit> cap = ArgumentCaptor.forClass(Orbit.class);
    verify(orbitRepository, atLeastOnce()).save(cap.capture());
    assertEquals(30, cap.getValue().getIdOrbit());
    assertEquals("UEE", cap.getValue().getFactionName());
  }

  @Test
  void syncOrbits_emptyResponse_aborts() {
    when(uexClient.getOrbits()).thenReturn(fetched(List.of()));
    service.syncOrbits();
    verifyNoInteractions(orbitRepository);
  }

  @Test
  void syncOutposts_upsertsNewOutpost() {
    UexOutpostDto dto =
        UexOutpostDto.builder()
            .id(40)
            .name("HDMS-Anderson")
            .isAvailableLive(1)
            .isAvailable(1)
            .padTypes("M,L")
            .nickname("HDMS-A")
            .build();
    when(uexClient.getOutposts()).thenReturn(fetched(List.of(dto)));
    when(outpostRepository.findByIdOutpost(40)).thenReturn(Optional.empty());
    when(outpostRepository.findByName("HDMS-Anderson")).thenReturn(Optional.empty());
    when(outpostRepository.save(any(Outpost.class))).thenAnswer(i -> i.getArgument(0));

    service.syncOutposts();

    ArgumentCaptor<Outpost> cap = ArgumentCaptor.forClass(Outpost.class);
    verify(outpostRepository, atLeastOnce()).save(cap.capture());
    assertEquals(40, cap.getValue().getIdOutpost());
    assertEquals("M,L", cap.getValue().getPadTypes());
  }

  @Test
  void syncOutposts_emptyResponse_aborts() {
    when(uexClient.getOutposts()).thenReturn(fetched(List.of()));
    service.syncOutposts();
    verifyNoInteractions(outpostRepository);
  }

  @Test
  void syncPlanets_upsertsNewPlanet() {
    UexPlanetDto dto =
        UexPlanetDto.builder()
            .id(50)
            .name("Hurston")
            .code("HUR")
            .isAvailableLive(1)
            .isAvailable(1)
            .isVisible(1)
            .isDefault(0)
            .starSystemName("Stanton")
            .build();
    when(uexClient.getPlanets()).thenReturn(fetched(List.of(dto)));
    when(planetRepository.findByIdPlanet(50)).thenReturn(Optional.empty());
    when(planetRepository.findByName("Hurston")).thenReturn(Optional.empty());
    when(planetRepository.save(any(Planet.class))).thenAnswer(i -> i.getArgument(0));

    service.syncPlanets();

    ArgumentCaptor<Planet> cap = ArgumentCaptor.forClass(Planet.class);
    verify(planetRepository, atLeastOnce()).save(cap.capture());
    assertEquals(50, cap.getValue().getIdPlanet());
    assertTrue(cap.getValue().getIsAvailable());
    assertFalse(cap.getValue().getIsDefault());
  }

  @Test
  void syncPlanets_emptyResponse_aborts() {
    when(uexClient.getPlanets()).thenReturn(fetched(List.of()));
    service.syncPlanets();
    verifyNoInteractions(planetRepository);
  }

  @Test
  void syncPois_upsertsNewPoi() {
    UexPoiDto dto =
        UexPoiDto.builder().id(60).name("Klescher").isAvailableLive(1).isAvailable(1).build();
    when(uexClient.getPoi()).thenReturn(fetched(List.of(dto)));
    when(poiRepository.findByIdPoi(60)).thenReturn(Optional.empty());
    when(poiRepository.findByName("Klescher")).thenReturn(Optional.empty());
    when(poiRepository.save(any(Poi.class))).thenAnswer(i -> i.getArgument(0));

    service.syncPois();

    ArgumentCaptor<Poi> cap = ArgumentCaptor.forClass(Poi.class);
    verify(poiRepository, atLeastOnce()).save(cap.capture());
    assertEquals(60, cap.getValue().getIdPoi());
  }

  @Test
  void syncPois_emptyResponse_aborts() {
    when(uexClient.getPoi()).thenReturn(fetched(List.of()));
    service.syncPois();
    verifyNoInteractions(poiRepository);
  }

  @Test
  void syncSpaceStations_upsertsAndMaterializesLocation_whenAvailableLive() {
    UexSpaceStationDto dto =
        UexSpaceStationDto.builder()
            .id(70)
            .name("Port Olisar")
            .isAvailableLive(1)
            .isAvailable(1)
            .isLagrange(1)
            .build();
    when(uexClient.getSpaceStations()).thenReturn(fetched(List.of(dto)));
    when(spaceStationRepository.findByIdSpaceStation(70)).thenReturn(Optional.empty());
    when(spaceStationRepository.findByName("Port Olisar")).thenReturn(Optional.empty());
    when(spaceStationRepository.save(any(SpaceStation.class)))
        .thenAnswer(i -> {
          SpaceStation s = i.getArgument(0);
          if (s.getId() == null) s.setId(UUID.randomUUID());
          return s;
        });
    when(locationRepository.findBySpaceStationId(any())).thenReturn(Optional.empty());
    when(locationRepository.findByName("Port Olisar")).thenReturn(Optional.empty());
    when(locationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

    service.syncSpaceStations();

    verify(spaceStationRepository, atLeastOnce()).save(any(SpaceStation.class));
    verify(locationRepository, atLeastOnce()).save(any());
  }

  @Test
  void syncSpaceStations_doesNotMaterializeLocation_whenNotAvailableLive() {
    UexSpaceStationDto offline =
        UexSpaceStationDto.builder()
            .id(71)
            .name("Decommissioned Station")
            .isAvailableLive(0)
            .build();
    when(uexClient.getSpaceStations()).thenReturn(fetched(List.of(offline)));
    when(spaceStationRepository.findByIdSpaceStation(71)).thenReturn(Optional.empty());
    when(spaceStationRepository.findByName("Decommissioned Station")).thenReturn(Optional.empty());
    when(spaceStationRepository.save(any(SpaceStation.class)))
        .thenAnswer(i -> {
          SpaceStation s = i.getArgument(0);
          if (s.getId() == null) s.setId(UUID.randomUUID());
          return s;
        });

    service.syncSpaceStations();

    verify(spaceStationRepository, atLeastOnce()).save(any());
    verify(locationRepository, never()).save(any());
  }

  @Test
  void syncCities_clearsTheReferenceOnlyMarker_whenTheCityBecomesLive() {
    City city = savedCity(3, "Rising City");
    Location marked = referenceOnly(city, "Rising City");
    when(uexClient.getCities()).thenReturn(fetched(List.of(cityDto(3, "Rising City", 1))));
    when(locationRepository.findByCityId(city.getId())).thenReturn(Optional.of(marked));
    when(locationRepository.findByName("Rising City")).thenReturn(Optional.of(marked));

    service.syncCities();

    assertFalse(marked.getReferenceOnly());
    assertEquals("Rising City", marked.getName());
    verify(locationRepository).save(marked);
  }

  @Test
  void syncCities_keepsAReferenceOnlyLocationOfAnOfflineCityNamedAfterIt() {
    City city = savedCity(4, "Renamed City");
    Location marked = referenceOnly(city, "Old Name");
    when(uexClient.getCities()).thenReturn(fetched(List.of(cityDto(4, "Renamed City", 0))));
    when(locationRepository.findByCityId(city.getId())).thenReturn(Optional.of(marked));
    when(locationRepository.findByName("Renamed City")).thenReturn(Optional.empty());

    service.syncCities();

    assertTrue(marked.getReferenceOnly());
    assertEquals("Renamed City", marked.getName());
    verify(locationRepository).save(marked);
  }

  @Test
  void syncCities_keepsTheNameOfAReferenceOnlyLocation_whenAnotherLocationHoldsIt() {
    City city = savedCity(5, "Shared Name");
    Location marked = referenceOnly(city, "Shared Name (UEX city 5)");
    Location other = new Location();
    other.setId(UUID.randomUUID());
    other.setName("Shared Name");
    when(uexClient.getCities()).thenReturn(fetched(List.of(cityDto(5, "Shared Name", 1))));
    when(locationRepository.findByCityId(city.getId())).thenReturn(Optional.of(marked));
    when(locationRepository.findByName("Shared Name")).thenReturn(Optional.of(other));

    service.syncCities();

    assertFalse(marked.getReferenceOnly());
    assertEquals("Shared Name (UEX city 5)", marked.getName());
  }

  @Test
  void syncCities_givesALiveCityItsOwnLocation_whenAStarmapNodePlaceHoldsItsName() {
    City city = savedCity(6, "Krt Node Name");
    Location nodePlace = new Location();
    nodePlace.setId(UUID.randomUUID());
    nodePlace.setName("Krt Node Name");
    nodePlace.setStarmapObjectId(UUID.randomUUID());
    when(uexClient.getCities()).thenReturn(fetched(List.of(cityDto(6, "Krt Node Name", 1))));
    when(locationRepository.findByCityId(city.getId())).thenReturn(Optional.empty());
    when(locationRepository.findByName("Krt Node Name")).thenReturn(Optional.of(nodePlace));
    when(locationRepository.findByName("Krt Node Name (UEX city 6)")).thenReturn(Optional.empty());

    service.syncCities();

    ArgumentCaptor<Location> cap = ArgumentCaptor.forClass(Location.class);
    verify(locationRepository).save(cap.capture());
    assertNotSame(nodePlace, cap.getValue());
    assertEquals("Krt Node Name (UEX city 6)", cap.getValue().getName());
    assertSame(city, cap.getValue().getCity());
    assertNull(nodePlace.getCity());
  }

  @Test
  void syncCities_createsNoLocation_whenBothNamesAreHeld() {
    City city = savedCity(8, "Krt Taken");
    when(uexClient.getCities()).thenReturn(fetched(List.of(cityDto(8, "Krt Taken", 1))));
    when(locationRepository.findByCityId(city.getId())).thenReturn(Optional.empty());
    when(locationRepository.findByName(any())).thenReturn(Optional.of(new Location()));

    service.syncCities();

    verify(locationRepository, never()).save(any());
  }

  @Test
  void syncSpaceStations_leavesALocationThatIsNotReferenceOnly_whenNotAvailableLive() {
    SpaceStation station = new SpaceStation();
    station.setId(UUID.randomUUID());
    station.setIdSpaceStation(72);
    station.setName("Quiet Station");
    Location storage = new Location();
    storage.setName("Curated Name");
    UexSpaceStationDto dto =
        UexSpaceStationDto.builder().id(72).name("Quiet Station").isAvailableLive(0).build();
    when(uexClient.getSpaceStations()).thenReturn(fetched(List.of(dto)));
    when(spaceStationRepository.findByIdSpaceStation(72)).thenReturn(Optional.of(station));
    when(spaceStationRepository.save(any(SpaceStation.class))).thenAnswer(i -> i.getArgument(0));
    when(locationRepository.findBySpaceStationId(station.getId())).thenReturn(Optional.of(storage));

    service.syncSpaceStations();

    assertEquals("Curated Name", storage.getName());
    verify(locationRepository, never()).save(any());
  }

  private static UexCityDto cityDto(int uexId, String name, int live) {
    return UexCityDto.builder().id(uexId).name(name).isAvailableLive(live).build();
  }

  private City savedCity(int uexId, String name) {
    City city = new City();
    city.setId(UUID.randomUUID());
    city.setIdCity(uexId);
    city.setName(name);
    when(cityRepository.findByIdCity(uexId)).thenReturn(Optional.of(city));
    when(cityRepository.save(any(City.class))).thenAnswer(i -> i.getArgument(0));
    return city;
  }

  private static Location referenceOnly(City city, String name) {
    Location location = new Location();
    location.setId(UUID.randomUUID());
    location.setName(name);
    location.setCity(city);
    location.setReferenceOnly(true);
    return location;
  }

  @Test
  void syncSpaceStations_emptyResponse_aborts() {
    when(uexClient.getSpaceStations()).thenReturn(fetched(List.of()));
    service.syncSpaceStations();
    verifyNoInteractions(spaceStationRepository, locationRepository);
  }

  @Test
  void syncTerminals_upsertsNewTerminal() {
    UexTerminalDto dto =
        UexTerminalDto.builder()
            .id(80)
            .name("Lorville TDD")
            .code("LV-TDD")
            .isAvailableLive(1)
            .nickname("TDD LV")
            .starSystemName("Stanton")
            .planetName("Hurston")
            .cityName("Lorville")
            .build();
    when(uexClient.getTerminals()).thenReturn(fetched(List.of(dto)));
    when(terminalRepository.findByIdTerminal(80)).thenReturn(Optional.empty());
    when(terminalRepository.findFirstByNameAndIdTerminalIsNullOrderByIdAsc("Lorville TDD"))
        .thenReturn(Optional.empty());
    when(terminalRepository.save(any(Terminal.class))).thenAnswer(i -> i.getArgument(0));

    service.syncTerminals();

    ArgumentCaptor<Terminal> cap = ArgumentCaptor.forClass(Terminal.class);
    verify(terminalRepository, atLeastOnce()).save(cap.capture());
    assertEquals(80, cap.getValue().getIdTerminal());
    assertEquals("TDD LV", cap.getValue().getNickname());
    assertEquals("Lorville", cap.getValue().getCityName());
  }

  @Test
  void syncTerminals_writesTheServicesContainerLimitAndGameVersion() {
    UexTerminalDto dto =
        UexTerminalDto.builder()
            .id(81)
            .name("Everus Harbor Admin")
            .isRefinery(1)
            .isRefuel(0)
            .maxContainerSize(32)
            .gameVersion("4.3.1")
            .build();
    UexTerminalDto blank = UexTerminalDto.builder().id(82).name("Blank").gameVersion("").build();
    when(uexClient.getTerminals()).thenReturn(fetched(List.of(dto, blank)));
    when(terminalRepository.findByIdTerminal(any())).thenReturn(Optional.empty());
    when(terminalRepository.findFirstByNameAndIdTerminalIsNullOrderByIdAsc(any()))
        .thenReturn(Optional.empty());
    when(terminalRepository.save(any(Terminal.class))).thenAnswer(i -> i.getArgument(0));

    service.syncTerminals();

    ArgumentCaptor<Terminal> cap = ArgumentCaptor.forClass(Terminal.class);
    verify(terminalRepository, atLeastOnce()).save(cap.capture());
    Terminal written =
        cap.getAllValues().stream().filter(t -> t.getIdTerminal() == 81).toList().getLast();
    assertEquals(Boolean.TRUE, written.getIsRefinery());
    assertEquals(Boolean.FALSE, written.getIsRefuel());
    assertNull(written.getIsRepair(), "an absent flag stays unknown");
    assertEquals(32, written.getMaxContainerSize());
    assertEquals("4.3.1", written.getGameVersion());
    assertNull(cap.getValue().getGameVersion(), "a blank game version is stored as null");
  }

  @Test
  void syncTerminals_emptyResponse_aborts() {
    when(uexClient.getTerminals()).thenReturn(fetched(List.of()));
    service.syncTerminals();
    verifyNoInteractions(terminalRepository);
  }

  @Test
  void syncCities_respectsLoadingDockOverride() {
    City existing = new City();
    existing.setId(UUID.randomUUID());
    existing.setIdCity(11);
    existing.setName("Lorville");
    existing.setHasLoadingDock(true);
    existing.setHasLoadingDockOverridden(true);

    UexCityDto dto =
        UexCityDto.builder().id(11).name("Lorville").hasLoadingDock(0).isAvailableLive(0).build();
    when(uexClient.getCities()).thenReturn(fetched(List.of(dto)));
    when(cityRepository.findByIdCity(11)).thenReturn(Optional.of(existing));
    when(cityRepository.save(any(City.class))).thenAnswer(i -> i.getArgument(0));

    service.syncCities();

    ArgumentCaptor<City> cap = ArgumentCaptor.forClass(City.class);
    verify(cityRepository, atLeastOnce()).save(cap.capture());
    assertTrue(cap.getValue().getHasLoadingDock(), "Admin-pinned hasLoadingDock must survive sync");
  }

  @Test
  void syncOutposts_respectsLoadingDockOverride() {
    Outpost existing = new Outpost();
    existing.setId(UUID.randomUUID());
    existing.setIdOutpost(12);
    existing.setName("HDMS-Anderson");
    existing.setHasLoadingDock(true);
    existing.setHasLoadingDockOverridden(true);

    UexOutpostDto dto =
        UexOutpostDto.builder()
            .id(12)
            .name("HDMS-Anderson")
            .hasLoadingDock(0)
            .isAvailableLive(0)
            .build();
    when(uexClient.getOutposts()).thenReturn(fetched(List.of(dto)));
    when(outpostRepository.findByIdOutpost(12)).thenReturn(Optional.of(existing));
    when(outpostRepository.save(any(Outpost.class))).thenAnswer(i -> i.getArgument(0));

    service.syncOutposts();

    ArgumentCaptor<Outpost> cap = ArgumentCaptor.forClass(Outpost.class);
    verify(outpostRepository, atLeastOnce()).save(cap.capture());
    assertTrue(cap.getValue().getHasLoadingDock(), "Admin-pinned hasLoadingDock must survive sync");
  }

  @Test
  void syncPois_respectsLoadingDockOverride() {
    Poi existing = new Poi();
    existing.setId(UUID.randomUUID());
    existing.setIdPoi(13);
    existing.setName("Grim HEX");
    existing.setHasLoadingDock(true);
    existing.setHasLoadingDockOverridden(true);

    UexPoiDto dto =
        UexPoiDto.builder().id(13).name("Grim HEX").hasLoadingDock(0).isAvailableLive(0).build();
    when(uexClient.getPoi()).thenReturn(fetched(List.of(dto)));
    when(poiRepository.findByIdPoi(13)).thenReturn(Optional.of(existing));
    when(poiRepository.save(any(Poi.class))).thenAnswer(i -> i.getArgument(0));

    service.syncPois();

    ArgumentCaptor<Poi> cap = ArgumentCaptor.forClass(Poi.class);
    verify(poiRepository, atLeastOnce()).save(cap.capture());
    assertTrue(cap.getValue().getHasLoadingDock(), "Admin-pinned hasLoadingDock must survive sync");
  }

  @Test
  void syncSpaceStations_respectsLoadingDockOverride() {
    SpaceStation existing = new SpaceStation();
    existing.setId(UUID.randomUUID());
    existing.setIdSpaceStation(14);
    existing.setName("Port Olisar");
    existing.setHasLoadingDock(true);
    existing.setHasLoadingDockOverridden(true);

    UexSpaceStationDto dto =
        UexSpaceStationDto.builder()
            .id(14)
            .name("Port Olisar")
            .hasLoadingDock(0)
            .isAvailableLive(0)
            .build();
    when(uexClient.getSpaceStations()).thenReturn(fetched(List.of(dto)));
    when(spaceStationRepository.findByIdSpaceStation(14)).thenReturn(Optional.of(existing));
    when(spaceStationRepository.save(any(SpaceStation.class))).thenAnswer(i -> i.getArgument(0));

    service.syncSpaceStations();

    ArgumentCaptor<SpaceStation> cap = ArgumentCaptor.forClass(SpaceStation.class);
    verify(spaceStationRepository, atLeastOnce()).save(cap.capture());
    assertTrue(cap.getValue().getHasLoadingDock(), "Admin-pinned hasLoadingDock must survive sync");
  }

  @Test
  void syncTerminals_respectsLoadingDockOverride() {
    Terminal existing = new Terminal();
    existing.setId(UUID.randomUUID());
    existing.setIdTerminal(15);
    existing.setName("Lorville TDD");
    existing.setHasLoadingDock(true);
    existing.setHasLoadingDockOverridden(true);

    UexTerminalDto dto =
        UexTerminalDto.builder()
            .id(15)
            .name("Lorville TDD")
            .hasLoadingDock(0)
            .isAutoLoad(0)
            .build();
    when(uexClient.getTerminals()).thenReturn(fetched(List.of(dto)));
    when(terminalRepository.findByIdTerminal(15)).thenReturn(Optional.of(existing));
    when(terminalRepository.save(any(Terminal.class))).thenAnswer(i -> i.getArgument(0));

    service.syncTerminals();

    ArgumentCaptor<Terminal> cap = ArgumentCaptor.forClass(Terminal.class);
    verify(terminalRepository, atLeastOnce()).save(cap.capture());
    assertTrue(cap.getValue().getHasLoadingDock(), "Admin-pinned hasLoadingDock must survive sync");
    assertFalse(
        cap.getValue().getUexHasLoadingDock(),
        "Raw UEX mirror column must be written even when the override is active");
  }

  @Test
  void syncTerminals_respectsAutoLoadOverride() {
    Terminal existing = new Terminal();
    existing.setId(UUID.randomUUID());
    existing.setIdTerminal(16);
    existing.setName("CRU-L1 Cargo");
    existing.setIsAutoLoad(true);
    existing.setIsAutoLoadOverridden(true);

    UexTerminalDto dto =
        UexTerminalDto.builder()
            .id(16)
            .name("CRU-L1 Cargo")
            .hasLoadingDock(0)
            .isAutoLoad(0)
            .build();
    when(uexClient.getTerminals()).thenReturn(fetched(List.of(dto)));
    when(terminalRepository.findByIdTerminal(16)).thenReturn(Optional.of(existing));
    when(terminalRepository.save(any(Terminal.class))).thenAnswer(i -> i.getArgument(0));

    service.syncTerminals();

    ArgumentCaptor<Terminal> cap = ArgumentCaptor.forClass(Terminal.class);
    verify(terminalRepository, atLeastOnce()).save(cap.capture());
    assertTrue(cap.getValue().getIsAutoLoad(), "Admin-pinned isAutoLoad must survive sync");
    assertFalse(
        cap.getValue().getUexIsAutoLoad(),
        "Raw UEX mirror column must be written even when the override is active");
  }

  @Test
  void syncTerminals_stampsUexSyncedAt_andMirrorsRawValues() {
    java.time.Instant before = java.time.Instant.now();

    UexTerminalDto dto =
        UexTerminalDto.builder().id(81).name("Area 18 TDD").hasLoadingDock(1).isAutoLoad(0).build();
    when(uexClient.getTerminals()).thenReturn(fetched(List.of(dto)));
    when(terminalRepository.findByIdTerminal(81)).thenReturn(Optional.empty());
    when(terminalRepository.findFirstByNameAndIdTerminalIsNullOrderByIdAsc("Area 18 TDD"))
        .thenReturn(Optional.empty());
    when(terminalRepository.save(any(Terminal.class))).thenAnswer(i -> i.getArgument(0));

    service.syncTerminals();

    ArgumentCaptor<Terminal> cap = ArgumentCaptor.forClass(Terminal.class);
    verify(terminalRepository, atLeastOnce()).save(cap.capture());
    Terminal saved = cap.getValue();
    assertTrue(saved.getUexHasLoadingDock());
    assertFalse(saved.getUexIsAutoLoad());
    assertNotNull(saved.getUexSyncedAt(), "uexSyncedAt must be stamped on every sweep");
    assertFalse(saved.getUexSyncedAt().isBefore(before));
  }

  @Test
  void syncTerminals_recordsNullUexMirror_whenUpstreamFieldsAreNull() {
    UexTerminalDto dto = UexTerminalDto.builder().id(82).name("Empty Terminal").build();
    when(uexClient.getTerminals()).thenReturn(fetched(List.of(dto)));
    when(terminalRepository.findByIdTerminal(82)).thenReturn(Optional.empty());
    when(terminalRepository.findFirstByNameAndIdTerminalIsNullOrderByIdAsc("Empty Terminal"))
        .thenReturn(Optional.empty());
    when(terminalRepository.save(any(Terminal.class))).thenAnswer(i -> i.getArgument(0));

    service.syncTerminals();

    ArgumentCaptor<Terminal> cap = ArgumentCaptor.forClass(Terminal.class);
    verify(terminalRepository, atLeastOnce()).save(cap.capture());
    assertNull(cap.getValue().getUexHasLoadingDock());
    assertNull(cap.getValue().getUexIsAutoLoad());
    assertNotNull(cap.getValue().getUexSyncedAt());
  }
}
