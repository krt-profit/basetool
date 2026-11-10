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

import de.greluc.krt.profit.basetool.backend.catalogue.api.City;
import de.greluc.krt.profit.basetool.backend.catalogue.api.CityRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.SpaceStation;
import de.greluc.krt.profit.basetool.backend.catalogue.api.SpaceStationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Terminal;
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
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Imports the UEX universe topology: factions, jurisdictions, planets, moons, orbits, cities,
 * outposts, points of interest, space stations and terminals.
 *
 * <p>Parents must be synced before their children; a child whose parent is not yet mirrored is
 * dropped until the next sweep. {@link #syncTerminals()} has no parent constraint and runs first
 * (REQ-REFINERY-020).
 *
 * <p>Each method fetches outside any transaction and writes through {@link SyncChunkWriter} in
 * chunk transactions of their own (REQ-DATA-005), upserting by UEX id with a name fallback. An
 * empty response writes nothing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UexUniverseSyncService {

  private final UexClient uexClient;
  private final CityRepository cityRepository;
  private final FactionRepository factionRepository;
  private final JurisdictionRepository jurisdictionRepository;
  private final MoonRepository moonRepository;
  private final OrbitRepository orbitRepository;
  private final OutpostRepository outpostRepository;
  private final PlanetRepository planetRepository;
  private final PoiRepository poiRepository;
  private final SpaceStationRepository spacestationRepository;
  private final TerminalRepository terminalRepository;
  private final LocationRepository locationRepository;

  /** Writes each catalogue in short isolated transactions after its fetch (BE-PERF-09). */
  private final SyncChunkWriter chunkWriter;

  /**
   * Syncs UEX cities into {@code city}. Parents (planet, moon) are resolved against the local
   * mirror; rows with an unresolved parent are still upserted but with the parent reference
   * cleared.
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void syncCities() {
    log.info("Starting sync for Citys...");
    UexClient.FetchResult<UexCityDto> fetched = uexClient.getCities();
    List<UexCityDto> dtos = fetched.data();
    if (dtos.isEmpty()) {
      log.warn("No cities received from UEX API. Aborting city synchronization.");
      return;
    }
    chunkWriter.write(
        dtos,
        SyncChunkWriter.DEFAULT_CHUNK_SIZE,
        chunk -> {
          List<Integer> ids = chunk.stream().map(UexCityDto::id).filter(Objects::nonNull).toList();
          List<String> names =
              chunk.stream().filter(dto -> dto.id() != null).map(UexCityDto::name).toList();
          List<City> existing =
              ids.isEmpty()
                  ? List.of()
                  : cityRepository.findByIdCityInOrNameInOrderByIdAsc(ids, names);
          UexChunkIndex<City> index =
              UexChunkIndex.of(existing, City::getIdCity, City::getName, false);
          List<String> placeNames =
              chunk.stream()
                  .filter(dto -> dto.id() != null)
                  .flatMap(
                      dto -> Stream.of(dto.name(), dto.name() + " (UEX city " + dto.id() + ")"))
                  .toList();
          UexStoragePlaceIndex places =
              new UexStoragePlaceIndex(
                  ids.isEmpty()
                      ? List.of()
                      : locationRepository.findForUexChunk(
                          existing.stream().map(City::getId).toList(), List.of(), placeNames));
          for (UexCityDto dto : chunk) {
            if (dto.id() == null) {
              continue;
            }
            City entity = index.resolve(dto.id(), dto.name(), City::new);
            entity.setIdCity(dto.id());
            entity.setName(dto.name());
            entity.setCode(dto.code());
            entity.setIsAvailableLive(dto.checkIsAvailableLive());
            entity.setIsAvailable(UexValues.asBooleanOrFalse(dto.isAvailable()));
            entity.setIsVisible(UexValues.asBooleanOrFalse(dto.isVisible()));
            entity.setIsDefault(UexValues.asBooleanOrFalse(dto.isDefault()));
            entity.setIsMonitored(UexValues.asBooleanOrFalse(dto.isMonitored()));
            entity.setIsArmistice(UexValues.asBooleanOrFalse(dto.isArmistice()));
            entity.setIsLandable(UexValues.asBooleanOrFalse(dto.isLandable()));
            entity.setIsDecommissioned(UexValues.asBooleanOrFalse(dto.isDecommissioned()));
            entity.setHasQuantumMarker(UexValues.asBooleanOrFalse(dto.hasQuantumMarker()));
            entity.setHasTradeTerminal(UexValues.asBooleanOrFalse(dto.hasTradeTerminal()));
            entity.setHasHabitation(UexValues.asBooleanOrFalse(dto.hasHabitation()));
            entity.setHasRefinery(UexValues.asBooleanOrFalse(dto.hasRefinery()));
            entity.setHasCargoCenter(UexValues.asBooleanOrFalse(dto.hasCargoCenter()));
            entity.setHasClinic(UexValues.asBooleanOrFalse(dto.hasClinic()));
            entity.setHasFood(UexValues.asBooleanOrFalse(dto.hasFood()));
            entity.setHasShops(UexValues.asBooleanOrFalse(dto.hasShops()));
            entity.setHasRefuel(UexValues.asBooleanOrFalse(dto.hasRefuel()));
            entity.setHasRepair(UexValues.asBooleanOrFalse(dto.hasRepair()));
            entity.setHasGravity(UexValues.asBooleanOrFalse(dto.hasGravity()));
            if (!Boolean.TRUE.equals(entity.getHasLoadingDockOverridden())) {
              entity.setHasLoadingDock(UexValues.asBooleanOrFalse(dto.hasLoadingDock()));
            }
            entity.setHasDockingPort(UexValues.asBooleanOrFalse(dto.hasDockingPort()));
            entity.setHasFreightElevator(UexValues.asBooleanOrFalse(dto.hasFreightElevator()));
            entity.setPadTypes(dto.padTypes());
            entity.setStarSystemName(dto.starSystemName());
            entity.setPlanetName(dto.planetName());
            entity.setOrbitName(dto.orbitName());
            entity.setMoonName(dto.moonName());
            entity.setFactionName(dto.factionName());
            entity.setJurisdictionName(dto.jurisdictionName());
            cityRepository.save(entity);
            syncStoragePlace(
                places.city(entity.getId()),
                entity.getName(),
                entity.getName() + " (UEX city " + entity.getIdCity() + ")",
                Boolean.TRUE.equals(entity.getIsAvailableLive()),
                l -> l.setCity(entity),
                places);
          }
          return chunk;
        },
        "city",
        dto -> "(id=" + dto.id() + ")");
    log.info("Finished sync for Citys.");
  }

  /**
   * Keeps the storage place of a synced city or space station consistent (REQ-P4K-039). A live
   * place reuses its linked location or gets a new one, and the row loses the reference-only
   * marker; a location of the same name belongs to another place and is never taken over. A place
   * that is not live gets no new location; a reference-only location already linked to it follows
   * its name.
   *
   * @param linked the location already linked to the place, or {@code null}
   * @param name the place's synced name
   * @param fallbackName the name of a new location when another location holds {@code name}
   * @param live whether UEX reports the place as available live
   * @param link links a location to the place
   * @param places the chunk's storage-place links and name reservations
   */
  private void syncStoragePlace(
      @Nullable Location linked,
      String name,
      @NotNull String fallbackName,
      boolean live,
      @NotNull Consumer<Location> link,
      @NotNull UexStoragePlaceIndex places) {
    if (live) {
      Location location =
          linked != null ? linked : newStoragePlace(name, fallbackName, link, places);
      if (location == null) {
        log.warn("No free storage-place name for {}; the place gets no location.", fallbackName);
        return;
      }
      location.setReferenceOnly(false);
      places.renameIfFree(location, name);
      locationRepository.save(location);
      places.remember(location);
      return;
    }
    if (linked != null && Boolean.TRUE.equals(linked.getReferenceOnly())) {
      places.renameIfFree(linked, name);
      locationRepository.save(linked);
      places.remember(linked);
    }
  }

  /**
   * Creates the location of a live place under the first of its names no location holds yet.
   *
   * @param name the place's synced name
   * @param fallbackName the name to take when {@code name} is held
   * @param link links the location to the place
   * @param places the chunk's storage-place links and name reservations
   * @return the unsaved location, or {@code null} when both names are held
   */
  @Nullable
  private Location newStoragePlace(
      String name,
      @NotNull String fallbackName,
      @NotNull Consumer<Location> link,
      @NotNull UexStoragePlaceIndex places) {
    String free = Stream.of(name, fallbackName).filter(places::isFree).findFirst().orElse(null);
    if (free == null) {
      return null;
    }
    Location location = new Location();
    location.setName(free);
    link.accept(location);
    places.remember(location);
    return location;
  }

  /**
   * Syncs UEX factions. No parent table — factions stand alone, so this method can run first in the
   * universe sweep.
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void syncFactions() {
    log.info("Starting sync for Factions...");
    UexClient.FetchResult<UexFactionDto> fetched = uexClient.getFactions();
    List<UexFactionDto> dtos = fetched.data();
    if (dtos.isEmpty()) {
      log.warn("No factions received from UEX API. Aborting faction synchronization.");
      return;
    }
    chunkWriter.write(
        dtos,
        SyncChunkWriter.DEFAULT_CHUNK_SIZE,
        chunk -> {
          List<Integer> ids =
              chunk.stream().map(UexFactionDto::id).filter(Objects::nonNull).toList();
          List<String> names =
              chunk.stream().filter(dto -> dto.id() != null).map(UexFactionDto::name).toList();
          UexChunkIndex<Faction> index =
              UexChunkIndex.of(
                  ids.isEmpty()
                      ? List.of()
                      : factionRepository.findByIdFactionInOrNameInOrderByIdAsc(ids, names),
                  Faction::getIdFaction,
                  Faction::getName,
                  false);
          for (UexFactionDto dto : chunk) {
            if (dto.id() == null) {
              continue;
            }
            Faction entity = index.resolve(dto.id(), dto.name(), Faction::new);
            entity.setIdFaction(dto.id());
            entity.setName(dto.name());
            entity.setWiki(dto.wiki());
            entity.setIsPiracy(dto.checkIsPiracy());
            entity.setIsBountyHunting(dto.checkIsBountyHunting());
            factionRepository.save(entity);
          }
          return chunk;
        },
        "faction",
        dto -> "(id=" + dto.id() + ")");
    log.info("Finished sync for Factions.");
  }

  /**
   * Syncs UEX jurisdictions. Parent is faction; unresolved faction → jurisdiction's faction
   * reference stays null.
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void syncJurisdictions() {
    log.info("Starting sync for Jurisdictions...");
    UexClient.FetchResult<UexJurisdictionDto> fetched = uexClient.getJurisdictions();
    List<UexJurisdictionDto> dtos = fetched.data();
    if (dtos.isEmpty()) {
      log.warn("No jurisdictions received from UEX API. Aborting jurisdiction synchronization.");
      return;
    }
    chunkWriter.write(
        dtos,
        SyncChunkWriter.DEFAULT_CHUNK_SIZE,
        chunk -> {
          List<Integer> ids =
              chunk.stream().map(UexJurisdictionDto::id).filter(Objects::nonNull).toList();
          List<String> names =
              chunk.stream().filter(dto -> dto.id() != null).map(UexJurisdictionDto::name).toList();
          UexChunkIndex<Jurisdiction> index =
              UexChunkIndex.of(
                  ids.isEmpty()
                      ? List.of()
                      : jurisdictionRepository.findByIdJurisdictionInOrNameInOrderByIdAsc(
                          ids, names),
                  Jurisdiction::getIdJurisdiction,
                  Jurisdiction::getName,
                  false);
          for (UexJurisdictionDto dto : chunk) {
            if (dto.id() == null) {
              continue;
            }
            Jurisdiction entity = index.resolve(dto.id(), dto.name(), Jurisdiction::new);
            entity.setIdJurisdiction(dto.id());
            entity.setName(dto.name());
            entity.setIsAvailableLive(dto.checkIsAvailableLive());

            entity.setNickname(dto.nickname());
            entity.setWiki(dto.wiki());
            entity.setFactionName(dto.factionName());
            jurisdictionRepository.save(entity);
          }
          return chunk;
        },
        "jurisdiction",
        dto -> "(id=" + dto.id() + ")");
    log.info("Finished sync for Jurisdictions.");
  }

  /** Syncs UEX moons. Parent is planet. */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void syncMoons() {
    log.info("Starting sync for Moons...");
    UexClient.FetchResult<UexMoonDto> fetched = uexClient.getMoons();
    List<UexMoonDto> dtos = fetched.data();
    if (dtos.isEmpty()) {
      log.warn("No moons received from UEX API. Aborting moon synchronization.");
      return;
    }
    chunkWriter.write(
        dtos,
        SyncChunkWriter.DEFAULT_CHUNK_SIZE,
        chunk -> {
          List<Integer> ids = chunk.stream().map(UexMoonDto::id).filter(Objects::nonNull).toList();
          List<String> names =
              chunk.stream().filter(dto -> dto.id() != null).map(UexMoonDto::name).toList();
          UexChunkIndex<Moon> index =
              UexChunkIndex.of(
                  ids.isEmpty()
                      ? List.of()
                      : moonRepository.findByIdMoonInOrNameInOrderByIdAsc(ids, names),
                  Moon::getIdMoon,
                  Moon::getName,
                  false);
          for (UexMoonDto dto : chunk) {
            if (dto.id() == null) {
              continue;
            }
            Moon entity = index.resolve(dto.id(), dto.name(), Moon::new);
            entity.setIdMoon(dto.id());
            entity.setName(dto.name());
            entity.setCode(dto.code());
            entity.setIsAvailableLive(dto.checkIsAvailableLive());
            entity.setIsAvailable(UexValues.asBooleanOrFalse(dto.isAvailable()));
            entity.setIsVisible(UexValues.asBooleanOrFalse(dto.isVisible()));
            entity.setIsDefault(UexValues.asBooleanOrFalse(dto.isDefault()));
            entity.setStarSystemName(dto.starSystemName());
            entity.setPlanetName(dto.planetName());
            entity.setOrbitName(dto.orbitName());
            entity.setFactionName(dto.factionName());
            entity.setJurisdictionName(dto.jurisdictionName());
            moonRepository.save(entity);
          }
          return chunk;
        },
        "moon",
        dto -> "(id=" + dto.id() + ")");
    log.info("Finished sync for Moons.");
  }

  /** Syncs UEX orbits (orbital positions / lagrange points around a planet). Parent is planet. */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void syncOrbits() {
    log.info("Starting sync for Orbits...");
    UexClient.FetchResult<UexOrbitDto> fetched = uexClient.getOrbits();
    List<UexOrbitDto> dtos = fetched.data();
    if (dtos.isEmpty()) {
      log.warn("No orbits received from UEX API. Aborting orbit synchronization.");
      return;
    }
    chunkWriter.write(
        dtos,
        SyncChunkWriter.DEFAULT_CHUNK_SIZE,
        chunk -> {
          List<Integer> ids = chunk.stream().map(UexOrbitDto::id).filter(Objects::nonNull).toList();
          List<String> names =
              chunk.stream().filter(dto -> dto.id() != null).map(UexOrbitDto::name).toList();
          UexChunkIndex<Orbit> index =
              UexChunkIndex.of(
                  ids.isEmpty()
                      ? List.of()
                      : orbitRepository.findByIdOrbitInOrNameInOrderByIdAsc(ids, names),
                  Orbit::getIdOrbit,
                  Orbit::getName,
                  false);
          for (UexOrbitDto dto : chunk) {
            if (dto.id() == null) {
              continue;
            }
            Orbit entity = index.resolve(dto.id(), dto.name(), Orbit::new);
            entity.setIdOrbit(dto.id());
            entity.setName(dto.name());
            entity.setCode(dto.code());
            entity.setIsAvailableLive(dto.checkIsAvailableLive());

            entity.setStarSystemName(dto.starSystemName());
            entity.setFactionName(dto.factionName());
            entity.setJurisdictionName(dto.jurisdictionName());
            orbitRepository.save(entity);
          }
          return chunk;
        },
        "orbit",
        dto -> "(id=" + dto.id() + ")");
    log.info("Finished sync for Orbits.");
  }

  /**
   * Syncs UEX outposts (surface settlements). Parent is planet or moon — UEX provides exactly one
   * of the two ids.
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void syncOutposts() {
    log.info("Starting sync for Outposts...");
    UexClient.FetchResult<UexOutpostDto> fetched = uexClient.getOutposts();
    List<UexOutpostDto> dtos = fetched.data();
    if (dtos.isEmpty()) {
      log.warn("No outposts received from UEX API. Aborting outpost synchronization.");
      return;
    }
    chunkWriter.write(
        dtos,
        SyncChunkWriter.DEFAULT_CHUNK_SIZE,
        chunk -> {
          List<Integer> ids =
              chunk.stream().map(UexOutpostDto::id).filter(Objects::nonNull).toList();
          List<String> names =
              chunk.stream().filter(dto -> dto.id() != null).map(UexOutpostDto::name).toList();
          UexChunkIndex<Outpost> index =
              UexChunkIndex.of(
                  ids.isEmpty()
                      ? List.of()
                      : outpostRepository.findByIdOutpostInOrNameInOrderByIdAsc(ids, names),
                  Outpost::getIdOutpost,
                  Outpost::getName,
                  false);
          for (UexOutpostDto dto : chunk) {
            if (dto.id() == null) {
              continue;
            }
            Outpost entity = index.resolve(dto.id(), dto.name(), Outpost::new);
            entity.setIdOutpost(dto.id());
            entity.setName(dto.name());
            entity.setIsAvailableLive(dto.checkIsAvailableLive());
            entity.setIsAvailable(UexValues.asBooleanOrFalse(dto.isAvailable()));
            entity.setIsVisible(UexValues.asBooleanOrFalse(dto.isVisible()));
            entity.setIsDefault(UexValues.asBooleanOrFalse(dto.isDefault()));
            entity.setIsMonitored(UexValues.asBooleanOrFalse(dto.isMonitored()));
            entity.setIsArmistice(UexValues.asBooleanOrFalse(dto.isArmistice()));
            entity.setIsLandable(UexValues.asBooleanOrFalse(dto.isLandable()));
            entity.setIsDecommissioned(UexValues.asBooleanOrFalse(dto.isDecommissioned()));
            entity.setHasQuantumMarker(UexValues.asBooleanOrFalse(dto.hasQuantumMarker()));
            entity.setHasTradeTerminal(UexValues.asBooleanOrFalse(dto.hasTradeTerminal()));
            entity.setHasHabitation(UexValues.asBooleanOrFalse(dto.hasHabitation()));
            entity.setHasRefinery(UexValues.asBooleanOrFalse(dto.hasRefinery()));
            entity.setHasCargoCenter(UexValues.asBooleanOrFalse(dto.hasCargoCenter()));
            entity.setHasClinic(UexValues.asBooleanOrFalse(dto.hasClinic()));
            entity.setHasFood(UexValues.asBooleanOrFalse(dto.hasFood()));
            entity.setHasShops(UexValues.asBooleanOrFalse(dto.hasShops()));
            entity.setHasRefuel(UexValues.asBooleanOrFalse(dto.hasRefuel()));
            entity.setHasRepair(UexValues.asBooleanOrFalse(dto.hasRepair()));
            entity.setHasGravity(UexValues.asBooleanOrFalse(dto.hasGravity()));
            if (!Boolean.TRUE.equals(entity.getHasLoadingDockOverridden())) {
              entity.setHasLoadingDock(UexValues.asBooleanOrFalse(dto.hasLoadingDock()));
            }
            entity.setHasDockingPort(UexValues.asBooleanOrFalse(dto.hasDockingPort()));
            entity.setHasFreightElevator(UexValues.asBooleanOrFalse(dto.hasFreightElevator()));
            entity.setPadTypes(dto.padTypes());
            entity.setNickname(dto.nickname());
            entity.setStarSystemName(dto.starSystemName());
            entity.setPlanetName(dto.planetName());
            entity.setOrbitName(dto.orbitName());
            entity.setMoonName(dto.moonName());
            entity.setFactionName(dto.factionName());
            entity.setJurisdictionName(dto.jurisdictionName());
            outpostRepository.save(entity);
          }
          return chunk;
        },
        "outpost",
        dto -> "(id=" + dto.id() + ")");
    log.info("Finished sync for Outposts.");
  }

  /**
   * Syncs UEX planets. Parent is star system; this method must run AFTER the star-system sync
   * (which lives in {@link UexStarSystemService}) so unresolved parents stay rare.
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void syncPlanets() {
    log.info("Starting sync for Planets...");
    UexClient.FetchResult<UexPlanetDto> fetched = uexClient.getPlanets();
    List<UexPlanetDto> dtos = fetched.data();
    if (dtos.isEmpty()) {
      log.warn("No planets received from UEX API. Aborting planet synchronization.");
      return;
    }
    chunkWriter.write(
        dtos,
        SyncChunkWriter.DEFAULT_CHUNK_SIZE,
        chunk -> {
          List<Integer> ids =
              chunk.stream().map(UexPlanetDto::id).filter(Objects::nonNull).toList();
          List<String> names =
              chunk.stream().filter(dto -> dto.id() != null).map(UexPlanetDto::name).toList();
          UexChunkIndex<Planet> index =
              UexChunkIndex.of(
                  ids.isEmpty()
                      ? List.of()
                      : planetRepository.findByIdPlanetInOrNameInOrderByIdAsc(ids, names),
                  Planet::getIdPlanet,
                  Planet::getName,
                  false);
          for (UexPlanetDto dto : chunk) {
            if (dto.id() == null) {
              continue;
            }
            Planet entity = index.resolve(dto.id(), dto.name(), Planet::new);
            entity.setIdPlanet(dto.id());
            entity.setName(dto.name());
            entity.setCode(dto.code());
            entity.setIsAvailableLive(dto.checkIsAvailableLive());
            entity.setIsAvailable(UexValues.asBooleanOrFalse(dto.isAvailable()));
            entity.setIsVisible(UexValues.asBooleanOrFalse(dto.isVisible()));
            entity.setIsDefault(UexValues.asBooleanOrFalse(dto.isDefault()));
            entity.setStarSystemName(dto.starSystemName());
            entity.setFactionName(dto.factionName());
            entity.setJurisdictionName(dto.jurisdictionName());
            planetRepository.save(entity);
          }
          return chunk;
        },
        "planet",
        dto -> "(id=" + dto.id() + ")");
    log.info("Finished sync for Planets.");
  }

  /**
   * Syncs UEX points of interest (derelicts, anomalies, lagrange POIs). Parent varies by POI type —
   * star system, planet or moon.
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void syncPois() {
    log.info("Starting sync for Pois...");
    UexClient.FetchResult<UexPoiDto> fetched = uexClient.getPoi();
    List<UexPoiDto> dtos = fetched.data();
    if (dtos.isEmpty()) {
      log.warn("No points of interest received from UEX API. Aborting POI synchronization.");
      return;
    }
    chunkWriter.write(
        dtos,
        SyncChunkWriter.DEFAULT_CHUNK_SIZE,
        chunk -> {
          List<Integer> ids = chunk.stream().map(UexPoiDto::id).filter(Objects::nonNull).toList();
          List<String> names =
              chunk.stream().filter(dto -> dto.id() != null).map(UexPoiDto::name).toList();
          UexChunkIndex<Poi> index =
              UexChunkIndex.of(
                  ids.isEmpty()
                      ? List.of()
                      : poiRepository.findByIdPoiInOrNameInOrderByIdAsc(ids, names),
                  Poi::getIdPoi,
                  Poi::getName,
                  false);
          for (UexPoiDto dto : chunk) {
            if (dto.id() == null) {
              continue;
            }
            Poi entity = index.resolve(dto.id(), dto.name(), Poi::new);
            entity.setIdPoi(dto.id());
            entity.setName(dto.name());
            entity.setIsAvailableLive(dto.checkIsAvailableLive());
            entity.setIsAvailable(UexValues.asBooleanOrFalse(dto.isAvailable()));
            entity.setIsVisible(UexValues.asBooleanOrFalse(dto.isVisible()));
            entity.setIsDefault(UexValues.asBooleanOrFalse(dto.isDefault()));
            entity.setIsMonitored(UexValues.asBooleanOrFalse(dto.isMonitored()));
            entity.setIsArmistice(UexValues.asBooleanOrFalse(dto.isArmistice()));
            entity.setIsLandable(UexValues.asBooleanOrFalse(dto.isLandable()));
            entity.setIsDecommissioned(UexValues.asBooleanOrFalse(dto.isDecommissioned()));
            entity.setHasQuantumMarker(UexValues.asBooleanOrFalse(dto.hasQuantumMarker()));
            entity.setHasTradeTerminal(UexValues.asBooleanOrFalse(dto.hasTradeTerminal()));
            entity.setHasHabitation(UexValues.asBooleanOrFalse(dto.hasHabitation()));
            entity.setHasRefinery(UexValues.asBooleanOrFalse(dto.hasRefinery()));
            entity.setHasCargoCenter(UexValues.asBooleanOrFalse(dto.hasCargoCenter()));
            entity.setHasClinic(UexValues.asBooleanOrFalse(dto.hasClinic()));
            entity.setHasFood(UexValues.asBooleanOrFalse(dto.hasFood()));
            entity.setHasShops(UexValues.asBooleanOrFalse(dto.hasShops()));
            entity.setHasRefuel(UexValues.asBooleanOrFalse(dto.hasRefuel()));
            entity.setHasRepair(UexValues.asBooleanOrFalse(dto.hasRepair()));
            entity.setHasGravity(UexValues.asBooleanOrFalse(dto.hasGravity()));
            if (!Boolean.TRUE.equals(entity.getHasLoadingDockOverridden())) {
              entity.setHasLoadingDock(UexValues.asBooleanOrFalse(dto.hasLoadingDock()));
            }
            entity.setHasDockingPort(UexValues.asBooleanOrFalse(dto.hasDockingPort()));
            entity.setHasFreightElevator(UexValues.asBooleanOrFalse(dto.hasFreightElevator()));
            entity.setPadTypes(dto.padTypes());
            entity.setNickname(dto.nickname());
            entity.setStarSystemName(dto.starSystemName());
            entity.setPlanetName(dto.planetName());
            entity.setOrbitName(dto.orbitName());
            entity.setMoonName(dto.moonName());
            entity.setSpaceStationName(dto.spaceStationName());
            entity.setOutpostName(dto.outpostName());
            entity.setCityName(dto.cityName());
            entity.setFactionName(dto.factionName());
            entity.setJurisdictionName(dto.jurisdictionName());
            poiRepository.save(entity);
          }
          return chunk;
        },
        "point of interest",
        dto -> "(id=" + dto.id() + ")");
    log.info("Finished sync for Pois.");
  }

  /**
   * Syncs UEX space stations. Parent is star system or orbit; carries the loading-dock / jump-point
   * / auto-load flags used by the profit-calculation page.
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void syncSpaceStations() {
    log.info("Starting sync for SpaceStations...");
    UexClient.FetchResult<UexSpaceStationDto> fetched = uexClient.getSpaceStations();
    List<UexSpaceStationDto> dtos = fetched.data();
    if (dtos.isEmpty()) {
      log.warn("No space stations received from UEX API. Aborting space station synchronization.");
      return;
    }
    chunkWriter.write(
        dtos,
        SyncChunkWriter.DEFAULT_CHUNK_SIZE,
        chunk -> {
          List<Integer> ids =
              chunk.stream().map(UexSpaceStationDto::id).filter(Objects::nonNull).toList();
          List<String> names =
              chunk.stream().filter(dto -> dto.id() != null).map(UexSpaceStationDto::name).toList();
          List<SpaceStation> existing =
              ids.isEmpty()
                  ? List.of()
                  : spacestationRepository.findByIdSpaceStationInOrNameInOrderByIdAsc(ids, names);
          UexChunkIndex<SpaceStation> index =
              UexChunkIndex.of(
                  existing, SpaceStation::getIdSpaceStation, SpaceStation::getName, false);
          List<String> placeNames =
              chunk.stream()
                  .filter(dto -> dto.id() != null)
                  .flatMap(dto ->
                      Stream.of(dto.name(), dto.name() + " (UEX space station " + dto.id() + ")"))
                  .toList();
          UexStoragePlaceIndex places =
              new UexStoragePlaceIndex(
                  ids.isEmpty()
                      ? List.of()
                      : locationRepository.findForUexChunk(
                          List.of(),
                          existing.stream().map(SpaceStation::getId).toList(),
                          placeNames));
          for (UexSpaceStationDto dto : chunk) {
            if (dto.id() == null) {
              continue;
            }
            SpaceStation entity = index.resolve(dto.id(), dto.name(), SpaceStation::new);
            entity.setIdSpaceStation(dto.id());
            entity.setName(dto.name());
            entity.setIsAvailableLive(dto.checkIsAvailableLive());
            entity.setIsAvailable(UexValues.asBooleanOrFalse(dto.isAvailable()));
            entity.setIsVisible(UexValues.asBooleanOrFalse(dto.isVisible()));
            entity.setIsDefault(UexValues.asBooleanOrFalse(dto.isDefault()));
            entity.setIsMonitored(UexValues.asBooleanOrFalse(dto.isMonitored()));
            entity.setIsArmistice(UexValues.asBooleanOrFalse(dto.isArmistice()));
            entity.setIsLandable(UexValues.asBooleanOrFalse(dto.isLandable()));
            entity.setIsDecommissioned(UexValues.asBooleanOrFalse(dto.isDecommissioned()));
            entity.setIsLagrange(UexValues.asBooleanOrFalse(dto.isLagrange()));
            entity.setIsJumpPoint(UexValues.asBooleanOrFalse(dto.isJumpPoint()));
            entity.setHasQuantumMarker(UexValues.asBooleanOrFalse(dto.hasQuantumMarker()));
            entity.setHasTradeTerminal(UexValues.asBooleanOrFalse(dto.hasTradeTerminal()));
            entity.setHasHabitation(UexValues.asBooleanOrFalse(dto.hasHabitation()));
            entity.setHasRefinery(UexValues.asBooleanOrFalse(dto.hasRefinery()));
            entity.setHasCargoCenter(UexValues.asBooleanOrFalse(dto.hasCargoCenter()));
            entity.setHasClinic(UexValues.asBooleanOrFalse(dto.hasClinic()));
            entity.setHasFood(UexValues.asBooleanOrFalse(dto.hasFood()));
            entity.setHasShops(UexValues.asBooleanOrFalse(dto.hasShops()));
            entity.setHasRefuel(UexValues.asBooleanOrFalse(dto.hasRefuel()));
            entity.setHasRepair(UexValues.asBooleanOrFalse(dto.hasRepair()));
            entity.setHasGravity(UexValues.asBooleanOrFalse(dto.hasGravity()));
            if (!Boolean.TRUE.equals(entity.getHasLoadingDockOverridden())) {
              entity.setHasLoadingDock(UexValues.asBooleanOrFalse(dto.hasLoadingDock()));
            }
            entity.setHasDockingPort(UexValues.asBooleanOrFalse(dto.hasDockingPort()));
            entity.setHasFreightElevator(UexValues.asBooleanOrFalse(dto.hasFreightElevator()));
            entity.setPadTypes(dto.padTypes());
            entity.setNickname(dto.nickname());
            entity.setStarSystemName(dto.starSystemName());
            entity.setPlanetName(dto.planetName());
            entity.setOrbitName(dto.orbitName());
            entity.setMoonName(dto.moonName());
            entity.setCityName(dto.cityName());
            entity.setFactionName(dto.factionName());
            entity.setJurisdictionName(dto.jurisdictionName());
            spacestationRepository.save(entity);
            syncStoragePlace(
                places.station(entity.getId()),
                entity.getName(),
                entity.getName() + " (UEX space station " + entity.getIdSpaceStation() + ")",
                Boolean.TRUE.equals(entity.getIsAvailableLive()),
                l -> l.setSpaceStation(entity),
                places);
          }
          return chunk;
        },
        "space station",
        dto -> "(id=" + dto.id() + ")");
    log.info("Finished sync for SpaceStations.");
  }

  /**
   * Syncs UEX terminals (trade kiosks at any parent location type). The parent is stored as a
   * denormalised name, so terminals have no ordering constraint and run first in the sweep.
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void syncTerminals() {
    log.info("Starting sync for Terminals...");
    UexClient.FetchResult<UexTerminalDto> fetched = uexClient.getTerminals();
    List<UexTerminalDto> dtos = fetched.data();
    if (dtos.isEmpty()) {
      log.warn("No terminals received from UEX API. Aborting terminal synchronization.");
      return;
    }
    Instant syncedAt = Instant.now();
    chunkWriter.write(
        dtos,
        SyncChunkWriter.DEFAULT_CHUNK_SIZE,
        chunk -> {
          List<Integer> ids =
              chunk.stream().map(UexTerminalDto::id).filter(Objects::nonNull).toList();
          List<String> names =
              chunk.stream().filter(dto -> dto.id() != null).map(UexTerminalDto::name).toList();
          UexChunkIndex<Terminal> index =
              UexChunkIndex.of(
                  ids.isEmpty()
                      ? List.of()
                      : terminalRepository.findByIdTerminalInOrNameInOrderByIdAsc(ids, names),
                  Terminal::getIdTerminal,
                  Terminal::getName,
                  true);
          for (UexTerminalDto dto : chunk) {
            if (dto.id() == null) {
              continue;
            }
            Terminal entity = index.resolve(dto.id(), dto.name(), Terminal::new);
            entity.setIdTerminal(dto.id());
            entity.setName(dto.name());
            entity.setCode(dto.code());
            entity.setType(dto.type());
            entity.setIsAvailableLive(dto.checkIsAvailableLive());
            entity.setIsAvailable(UexValues.asBooleanOrFalse(dto.isAvailable()));
            entity.setIsVisible(UexValues.asBooleanOrFalse(dto.isVisible()));
            entity.setIsJumpPoint(UexValues.asBooleanOrFalse(dto.isJumpPoint()));
            Boolean uexLoadingDock =
                dto.hasLoadingDock() == null ? null : dto.hasLoadingDock() == 1;
            Boolean uexAutoLoad = dto.isAutoLoad() == null ? null : dto.isAutoLoad() == 1;
            entity.setUexHasLoadingDock(uexLoadingDock);
            entity.setUexIsAutoLoad(uexAutoLoad);
            entity.setUexSyncedAt(syncedAt);
            if (!Boolean.TRUE.equals(entity.getHasLoadingDockOverridden())) {
              entity.setHasLoadingDock(Boolean.TRUE.equals(uexLoadingDock));
            }
            entity.setHasDockingPort(UexValues.asBooleanOrFalse(dto.hasDockingPort()));
            entity.setHasFreightElevator(UexValues.asBooleanOrFalse(dto.hasFreightElevator()));
            if (!Boolean.TRUE.equals(entity.getIsAutoLoadOverridden())) {
              entity.setIsAutoLoad(Boolean.TRUE.equals(uexAutoLoad));
            }
            entity.setIsRefinery(UexValues.asBooleanOrNull(dto.isRefinery()));
            entity.setIsRefuel(UexValues.asBooleanOrNull(dto.isRefuel()));
            entity.setIsRepair(UexValues.asBooleanOrNull(dto.isRepair()));
            entity.setMaxContainerSize(dto.maxContainerSize());
            entity.setGameVersion(
                StringUtils.hasText(dto.gameVersion()) ? dto.gameVersion() : null);
            entity.setNickname(dto.nickname());
            entity.setStarSystemName(dto.starSystemName());
            entity.setPlanetName(dto.planetName());
            entity.setOrbitName(dto.orbitName());
            entity.setMoonName(dto.moonName());
            entity.setSpaceStationName(dto.spaceStationName());
            entity.setOutpostName(dto.outpostName());
            entity.setCityName(dto.cityName());
            entity.setFactionName(dto.factionName());
            entity.setCompanyName(dto.companyName());
            terminalRepository.save(entity);
          }
          return chunk;
        },
        "terminal",
        dto -> "(id=" + dto.id() + ")");
    log.info("Finished sync for Terminals.");
  }

  /**
   * Recomputes {@code city.has_refinery_terminal} / {@code space_station.has_refinery_terminal}
   * from the synced terminals: a parent is flagged iff it hosts at least one live {@code type =
   * 'refinery'} terminal (REQ-REFINERY-020).
   *
   * <p>Corrects UEX's unreliable parent-level {@code has_refinery} claim, which stays stored for
   * diagnostics. Called once per sweep from {@code UexScheduler}'s {@code finally}, after all sync
   * steps, and issues no network call. Matches by the terminal's parent-name columns; a station
   * within a city does not flag the city.
   *
   * <p>The explicit {@code @Transactional} must stay: the class default is read-only, in which
   * Hibernate silently discards the writes.
   */
  @Transactional
  public void reconcileRefineryTerminalFlags() {
    Set<String> refineryCities = new HashSet<>();
    Set<String> refineryStations = new HashSet<>();
    for (Terminal terminal :
        terminalRepository.findByTypeAndIsAvailableLiveTrue(Terminal.TYPE_REFINERY)) {
      if (terminal.getSpaceStationName() != null) {
        refineryStations.add(terminal.getSpaceStationName());
      } else if (terminal.getCityName() != null) {
        refineryCities.add(terminal.getCityName());
      }
    }

    int changed = 0;
    for (City city : cityRepository.findAll()) {
      boolean expected = city.getName() != null && refineryCities.contains(city.getName());
      if (expected != Boolean.TRUE.equals(city.getHasRefineryTerminal())) {
        city.setHasRefineryTerminal(expected);
        cityRepository.save(city);
        changed++;
      }
    }
    for (SpaceStation station : spacestationRepository.findAll()) {
      boolean expected = station.getName() != null && refineryStations.contains(station.getName());
      if (expected != Boolean.TRUE.equals(station.getHasRefineryTerminal())) {
        station.setHasRefineryTerminal(expected);
        spacestationRepository.save(station);
        changed++;
      }
    }
    log.info(
        "Reconciled refinery-terminal flags: {} refinery terminals ({} stations, {} cities),"
            + " {} parent rows updated",
        refineryStations.size() + refineryCities.size(),
        refineryStations.size(),
        refineryCities.size(),
        changed);
  }
}
