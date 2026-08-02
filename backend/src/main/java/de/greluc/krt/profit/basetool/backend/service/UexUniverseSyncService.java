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

import de.greluc.krt.profit.basetool.backend.dto.uex.UexCityDto;
import de.greluc.krt.profit.basetool.backend.dto.uex.UexFactionDto;
import de.greluc.krt.profit.basetool.backend.dto.uex.UexJurisdictionDto;
import de.greluc.krt.profit.basetool.backend.dto.uex.UexMoonDto;
import de.greluc.krt.profit.basetool.backend.dto.uex.UexOrbitDto;
import de.greluc.krt.profit.basetool.backend.dto.uex.UexOutpostDto;
import de.greluc.krt.profit.basetool.backend.dto.uex.UexPlanetDto;
import de.greluc.krt.profit.basetool.backend.dto.uex.UexPoiDto;
import de.greluc.krt.profit.basetool.backend.dto.uex.UexSpaceStationDto;
import de.greluc.krt.profit.basetool.backend.dto.uex.UexTerminalDto;
import de.greluc.krt.profit.basetool.backend.integration.UexClient;
import de.greluc.krt.profit.basetool.backend.model.City;
import de.greluc.krt.profit.basetool.backend.model.Faction;
import de.greluc.krt.profit.basetool.backend.model.Jurisdiction;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Moon;
import de.greluc.krt.profit.basetool.backend.model.Orbit;
import de.greluc.krt.profit.basetool.backend.model.Outpost;
import de.greluc.krt.profit.basetool.backend.model.Planet;
import de.greluc.krt.profit.basetool.backend.model.Poi;
import de.greluc.krt.profit.basetool.backend.model.SpaceStation;
import de.greluc.krt.profit.basetool.backend.model.Terminal;
import de.greluc.krt.profit.basetool.backend.repository.CityRepository;
import de.greluc.krt.profit.basetool.backend.repository.FactionRepository;
import de.greluc.krt.profit.basetool.backend.repository.JurisdictionRepository;
import de.greluc.krt.profit.basetool.backend.repository.LocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.MoonRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrbitRepository;
import de.greluc.krt.profit.basetool.backend.repository.OutpostRepository;
import de.greluc.krt.profit.basetool.backend.repository.PlanetRepository;
import de.greluc.krt.profit.basetool.backend.repository.PoiRepository;
import de.greluc.krt.profit.basetool.backend.repository.SpaceStationRepository;
import de.greluc.krt.profit.basetool.backend.repository.TerminalRepository;
import de.greluc.krt.profit.basetool.backend.support.UexValues;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Imports the UEX universe topology — factions, jurisdictions, planets, moons, orbits, cities,
 * outposts, points of interest, space stations and terminals.
 *
 * <p>The order in which {@link de.greluc.krt.profit.basetool.backend.service.UexScheduler} calls
 * the sync methods matters: factions/jurisdictions first (no parent), then planets, then
 * moons/orbits/POI (parented by planet), then cities (parented by planet/moon), then outposts and
 * space stations (parented by city). Calling them out of order means a child row references a
 * parent that does not yet exist in the local mirror, and the child is silently dropped (the
 * scheduler retries the full sweep on the next tick so missed children eventually land).
 *
 * <p>{@link #syncTerminals()} is the exception and deliberately runs <em>first</em>
 * (REQ-REFINERY-020). Terminals resolve no parent entity at all — UEX ships the parent as a
 * denormalised name string that is stored verbatim — so they have no ordering constraint, and
 * running them ahead of every step that can abort the tick is what keeps {@code terminal.type}, the
 * refinery picker's only source, from staying unpopulated for a full 24-hour sweep interval.
 *
 * <p>Every sync method follows the same pattern: pull the full UEX catalog for that entity, upsert
 * by UEX id (with name-based fallback for legacy rows missing the id), per-field dirty checking to
 * minimize write traffic. Empty UEX responses short-circuit without wiping local data. An
 * <em>unchanged</em> catalogue ({@code 304 Not Modified}, served from the {@link UexClient}
 * conditional-GET cache) short-circuits the same way but is reported at INFO instead of WARN — a
 * fully-cached run is the healthy steady state and must not read like the outage the WARN
 * describes.
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

  /**
   * Syncs UEX cities into {@code city}. Parents (planet, moon) are resolved against the local
   * mirror; rows with an unresolved parent are still upserted but with the parent reference
   * cleared.
   */
  @Transactional
  public void syncCities() {
    log.info("Starting sync for Citys...");
    UexClient.FetchResult<UexCityDto> fetched = uexClient.getCities();
    if (fetched.notModified()) {
      log.info("UEX city catalogue unchanged since the last sync (304) — nothing to import.");
      return;
    }
    List<UexCityDto> dtos = fetched.data();
    if (dtos.isEmpty()) {
      log.warn("No cities received from UEX API. Aborting city synchronization.");
      return;
    }
    for (UexCityDto dto : dtos) {
      if (dto.id() == null) {
        continue;
      }
      City entity =
          cityRepository
              .findByIdCity(dto.id())
              .orElseGet(
                  () ->
                      cityRepository
                          .findByName(dto.name())
                          .map(
                              e -> {
                                e.setIdCity(dto.id());
                                return cityRepository.save(e);
                              })
                          .orElseGet(
                              () -> {
                                City n = new City();
                                n.setIdCity(dto.id());
                                n.setName(dto.name());
                                return cityRepository.save(n);
                              }));
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

      if (Boolean.TRUE.equals(entity.getIsAvailableLive())) {
        Location location =
            locationRepository
                .findByCityId(entity.getId())
                .orElseGet(
                    () ->
                        locationRepository
                            .findByName(entity.getName())
                            .map(
                                l -> {
                                  l.setCity(entity);
                                  return l;
                                })
                            .orElseGet(
                                () -> {
                                  Location l = new Location();
                                  l.setName(entity.getName());
                                  l.setCity(entity);
                                  return l;
                                }));
        location.setName(entity.getName());
        locationRepository.save(location);
      }
    }
    log.info("Finished sync for Citys.");
  }

  /**
   * Syncs UEX factions. No parent table — factions stand alone, so this method can run first in the
   * universe sweep.
   */
  @Transactional
  public void syncFactions() {
    log.info("Starting sync for Factions...");
    UexClient.FetchResult<UexFactionDto> fetched = uexClient.getFactions();
    if (fetched.notModified()) {
      log.info("UEX faction catalogue unchanged since the last sync (304) — nothing to import.");
      return;
    }
    List<UexFactionDto> dtos = fetched.data();
    if (dtos.isEmpty()) {
      log.warn("No factions received from UEX API. Aborting faction synchronization.");
      return;
    }
    for (UexFactionDto dto : dtos) {
      if (dto.id() == null) {
        continue;
      }
      Faction entity =
          factionRepository
              .findByIdFaction(dto.id())
              .orElseGet(
                  () ->
                      factionRepository
                          .findByName(dto.name())
                          .map(
                              e -> {
                                e.setIdFaction(dto.id());
                                return factionRepository.save(e);
                              })
                          .orElseGet(
                              () -> {
                                Faction n = new Faction();
                                n.setIdFaction(dto.id());
                                n.setName(dto.name());
                                return factionRepository.save(n);
                              }));
      entity.setName(dto.name());
      entity.setCode(dto.code());
      entity.setIsAvailableLive(dto.checkIsAvailableLive());

      entity.setWiki(dto.wiki());
      entity.setIsPiracy(dto.checkIsPiracy());
      entity.setIsBountyHunting(dto.checkIsBountyHunting());
      factionRepository.save(entity);
    }
    log.info("Finished sync for Factions.");
  }

  /**
   * Syncs UEX jurisdictions. Parent is faction; unresolved faction → jurisdiction's faction
   * reference stays null.
   */
  @Transactional
  public void syncJurisdictions() {
    log.info("Starting sync for Jurisdictions...");
    UexClient.FetchResult<UexJurisdictionDto> fetched = uexClient.getJurisdictions();
    if (fetched.notModified()) {
      log.info(
          "UEX jurisdiction catalogue unchanged since the last sync (304) — nothing to import.");
      return;
    }
    List<UexJurisdictionDto> dtos = fetched.data();
    if (dtos.isEmpty()) {
      log.warn("No jurisdictions received from UEX API. Aborting jurisdiction synchronization.");
      return;
    }
    for (UexJurisdictionDto dto : dtos) {
      if (dto.id() == null) {
        continue;
      }
      Jurisdiction entity =
          jurisdictionRepository
              .findByIdJurisdiction(dto.id())
              .orElseGet(
                  () ->
                      jurisdictionRepository
                          .findByName(dto.name())
                          .map(
                              e -> {
                                e.setIdJurisdiction(dto.id());
                                return jurisdictionRepository.save(e);
                              })
                          .orElseGet(
                              () -> {
                                Jurisdiction n = new Jurisdiction();
                                n.setIdJurisdiction(dto.id());
                                n.setName(dto.name());
                                return jurisdictionRepository.save(n);
                              }));
      entity.setName(dto.name());
      entity.setCode(dto.code());
      entity.setIsAvailableLive(dto.checkIsAvailableLive());

      entity.setNickname(dto.nickname());
      entity.setWiki(dto.wiki());
      entity.setFactionName(dto.factionName());
      jurisdictionRepository.save(entity);
    }
    log.info("Finished sync for Jurisdictions.");
  }

  /** Syncs UEX moons. Parent is planet. */
  @Transactional
  public void syncMoons() {
    log.info("Starting sync for Moons...");
    UexClient.FetchResult<UexMoonDto> fetched = uexClient.getMoons();
    if (fetched.notModified()) {
      log.info("UEX moon catalogue unchanged since the last sync (304) — nothing to import.");
      return;
    }
    List<UexMoonDto> dtos = fetched.data();
    if (dtos.isEmpty()) {
      log.warn("No moons received from UEX API. Aborting moon synchronization.");
      return;
    }
    for (UexMoonDto dto : dtos) {
      if (dto.id() == null) {
        continue;
      }
      Moon entity =
          moonRepository
              .findByIdMoon(dto.id())
              .orElseGet(
                  () ->
                      moonRepository
                          .findByName(dto.name())
                          .map(
                              e -> {
                                e.setIdMoon(dto.id());
                                return moonRepository.save(e);
                              })
                          .orElseGet(
                              () -> {
                                Moon n = new Moon();
                                n.setIdMoon(dto.id());
                                n.setName(dto.name());
                                return moonRepository.save(n);
                              }));
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
    log.info("Finished sync for Moons.");
  }

  /** Syncs UEX orbits (orbital positions / lagrange points around a planet). Parent is planet. */
  @Transactional
  public void syncOrbits() {
    log.info("Starting sync for Orbits...");
    UexClient.FetchResult<UexOrbitDto> fetched = uexClient.getOrbits();
    if (fetched.notModified()) {
      log.info("UEX orbit catalogue unchanged since the last sync (304) — nothing to import.");
      return;
    }
    List<UexOrbitDto> dtos = fetched.data();
    if (dtos.isEmpty()) {
      log.warn("No orbits received from UEX API. Aborting orbit synchronization.");
      return;
    }
    for (UexOrbitDto dto : dtos) {
      if (dto.id() == null) {
        continue;
      }
      Orbit entity =
          orbitRepository
              .findByIdOrbit(dto.id())
              .orElseGet(
                  () ->
                      orbitRepository
                          .findByName(dto.name())
                          .map(
                              e -> {
                                e.setIdOrbit(dto.id());
                                return orbitRepository.save(e);
                              })
                          .orElseGet(
                              () -> {
                                Orbit n = new Orbit();
                                n.setIdOrbit(dto.id());
                                n.setName(dto.name());
                                return orbitRepository.save(n);
                              }));
      entity.setName(dto.name());
      entity.setCode(dto.code());
      entity.setIsAvailableLive(dto.checkIsAvailableLive());

      entity.setStarSystemName(dto.starSystemName());
      entity.setFactionName(dto.factionName());
      entity.setJurisdictionName(dto.jurisdictionName());
      orbitRepository.save(entity);
    }
    log.info("Finished sync for Orbits.");
  }

  /**
   * Syncs UEX outposts (surface settlements). Parent is planet or moon — UEX provides exactly one
   * of the two ids.
   */
  @Transactional
  public void syncOutposts() {
    log.info("Starting sync for Outposts...");
    UexClient.FetchResult<UexOutpostDto> fetched = uexClient.getOutposts();
    if (fetched.notModified()) {
      log.info("UEX outpost catalogue unchanged since the last sync (304) — nothing to import.");
      return;
    }
    List<UexOutpostDto> dtos = fetched.data();
    if (dtos.isEmpty()) {
      log.warn("No outposts received from UEX API. Aborting outpost synchronization.");
      return;
    }
    for (UexOutpostDto dto : dtos) {
      if (dto.id() == null) {
        continue;
      }
      Outpost entity =
          outpostRepository
              .findByIdOutpost(dto.id())
              .orElseGet(
                  () ->
                      outpostRepository
                          .findByName(dto.name())
                          .map(
                              e -> {
                                e.setIdOutpost(dto.id());
                                return outpostRepository.save(e);
                              })
                          .orElseGet(
                              () -> {
                                Outpost n = new Outpost();
                                n.setIdOutpost(dto.id());
                                n.setName(dto.name());
                                return outpostRepository.save(n);
                              }));
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
      entity.setNickname(dto.nickname());
      entity.setStarSystemName(dto.starSystemName());
      entity.setPlanetName(dto.planetName());
      entity.setOrbitName(dto.orbitName());
      entity.setMoonName(dto.moonName());
      entity.setFactionName(dto.factionName());
      entity.setJurisdictionName(dto.jurisdictionName());
      outpostRepository.save(entity);
    }
    log.info("Finished sync for Outposts.");
  }

  /**
   * Syncs UEX planets. Parent is star system; this method must run AFTER the star-system sync
   * (which lives in {@link UexStarSystemService}) so unresolved parents stay rare.
   */
  @Transactional
  public void syncPlanets() {
    log.info("Starting sync for Planets...");
    UexClient.FetchResult<UexPlanetDto> fetched = uexClient.getPlanets();
    if (fetched.notModified()) {
      log.info("UEX planet catalogue unchanged since the last sync (304) — nothing to import.");
      return;
    }
    List<UexPlanetDto> dtos = fetched.data();
    if (dtos.isEmpty()) {
      log.warn("No planets received from UEX API. Aborting planet synchronization.");
      return;
    }
    for (UexPlanetDto dto : dtos) {
      if (dto.id() == null) {
        continue;
      }
      Planet entity =
          planetRepository
              .findByIdPlanet(dto.id())
              .orElseGet(
                  () ->
                      planetRepository
                          .findByName(dto.name())
                          .map(
                              e -> {
                                e.setIdPlanet(dto.id());
                                return planetRepository.save(e);
                              })
                          .orElseGet(
                              () -> {
                                Planet n = new Planet();
                                n.setIdPlanet(dto.id());
                                n.setName(dto.name());
                                return planetRepository.save(n);
                              }));
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
    log.info("Finished sync for Planets.");
  }

  /**
   * Syncs UEX points of interest (derelicts, anomalies, lagrange POIs). Parent varies by POI type —
   * star system, planet or moon.
   */
  @Transactional
  public void syncPois() {
    log.info("Starting sync for Pois...");
    UexClient.FetchResult<UexPoiDto> fetched = uexClient.getPoi();
    if (fetched.notModified()) {
      log.info(
          "UEX point-of-interest catalogue unchanged since the last sync (304) — nothing to"
              + " import.");
      return;
    }
    List<UexPoiDto> dtos = fetched.data();
    if (dtos.isEmpty()) {
      log.warn("No points of interest received from UEX API. Aborting POI synchronization.");
      return;
    }
    for (UexPoiDto dto : dtos) {
      if (dto.id() == null) {
        continue;
      }
      Poi entity =
          poiRepository
              .findByIdPoi(dto.id())
              .orElseGet(
                  () ->
                      poiRepository
                          .findByName(dto.name())
                          .map(
                              e -> {
                                e.setIdPoi(dto.id());
                                return poiRepository.save(e);
                              })
                          .orElseGet(
                              () -> {
                                Poi n = new Poi();
                                n.setIdPoi(dto.id());
                                n.setName(dto.name());
                                return poiRepository.save(n);
                              }));
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
    log.info("Finished sync for Pois.");
  }

  /**
   * Syncs UEX space stations. Parent is star system or orbit; carries the loading-dock / jump-point
   * / auto-load flags used by the profit-calculation page.
   */
  @Transactional
  public void syncSpaceStations() {
    log.info("Starting sync for SpaceStations...");
    UexClient.FetchResult<UexSpaceStationDto> fetched = uexClient.getSpaceStations();
    if (fetched.notModified()) {
      log.info(
          "UEX space station catalogue unchanged since the last sync (304) — nothing to import.");
      return;
    }
    List<UexSpaceStationDto> dtos = fetched.data();
    if (dtos.isEmpty()) {
      log.warn("No space stations received from UEX API. Aborting space station synchronization.");
      return;
    }
    for (UexSpaceStationDto dto : dtos) {
      if (dto.id() == null) {
        continue;
      }
      SpaceStation entity =
          spacestationRepository
              .findByIdSpaceStation(dto.id())
              .orElseGet(
                  () ->
                      spacestationRepository
                          .findByName(dto.name())
                          .map(
                              e -> {
                                e.setIdSpaceStation(dto.id());
                                return spacestationRepository.save(e);
                              })
                          .orElseGet(
                              () -> {
                                SpaceStation n = new SpaceStation();
                                n.setIdSpaceStation(dto.id());
                                n.setName(dto.name());
                                return spacestationRepository.save(n);
                              }));
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

      if (Boolean.TRUE.equals(entity.getIsAvailableLive())) {
        Location location =
            locationRepository
                .findBySpaceStationId(entity.getId())
                .orElseGet(
                    () ->
                        locationRepository
                            .findByName(entity.getName())
                            .map(
                                l -> {
                                  l.setSpaceStation(entity);
                                  return l;
                                })
                            .orElseGet(
                                () -> {
                                  Location l = new Location();
                                  l.setName(entity.getName());
                                  l.setSpaceStation(entity);
                                  return l;
                                }));
        location.setName(entity.getName());
        locationRepository.save(location);
      }
    }
    log.info("Finished sync for SpaceStations.");
  }

  /**
   * Syncs UEX terminals (trade kiosks at any parent location type). Last in the universe sweep
   * because every terminal references a parent (city, space station or outpost) that the earlier
   * sync methods produce. Unknown-parent terminals are upserted with the parent reference cleared —
   * the next sweep usually fixes the row.
   */
  @Transactional
  public void syncTerminals() {
    log.info("Starting sync for Terminals...");
    UexClient.FetchResult<UexTerminalDto> fetched = uexClient.getTerminals();
    if (fetched.notModified()) {
      log.info("UEX terminal catalogue unchanged since the last sync (304) — nothing to import.");
      return;
    }
    List<UexTerminalDto> dtos = fetched.data();
    if (dtos.isEmpty()) {
      log.warn("No terminals received from UEX API. Aborting terminal synchronization.");
      return;
    }
    Instant syncedAt = Instant.now();
    for (UexTerminalDto dto : dtos) {
      if (dto.id() == null) {
        continue;
      }
      Terminal entity =
          terminalRepository
              .findByIdTerminal(dto.id())
              .orElseGet(
                  () ->
                      terminalRepository
                          .findByName(dto.name())
                          .map(
                              e -> {
                                e.setIdTerminal(dto.id());
                                return terminalRepository.save(e);
                              })
                          .orElseGet(
                              () -> {
                                Terminal n = new Terminal();
                                n.setIdTerminal(dto.id());
                                n.setName(dto.name());
                                return terminalRepository.save(n);
                              }));
      entity.setName(dto.name());
      entity.setCode(dto.code());
      // The terminal kind is what actually proves a refinery exists at the parent location; the
      // parent's own has_refinery flag is unreliable upstream (REQ-REFINERY-020).
      entity.setType(dto.type());
      entity.setIsAvailableLive(dto.checkIsAvailableLive());
      entity.setIsAvailable(UexValues.asBooleanOrFalse(dto.isAvailable()));
      entity.setIsVisible(UexValues.asBooleanOrFalse(dto.isVisible()));
      entity.setIsJumpPoint(UexValues.asBooleanOrFalse(dto.isJumpPoint()));
      // The raw UEX state is recorded on every sweep, regardless of the override flags,
      // so the admin UI can show what UEX currently claims even while a pin is active.
      Boolean uexLoadingDock = dto.hasLoadingDock() == null ? null : dto.hasLoadingDock() == 1;
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
    log.info("Finished sync for Terminals.");
  }

  /**
   * Recomputes {@code city.has_refinery_terminal} / {@code space_station.has_refinery_terminal}
   * from the terminals just synced: a parent is flagged iff it hosts at least one live {@code type
   * = 'refinery'} terminal (REQ-REFINERY-020).
   *
   * <p>This is the correction step for an upstream inconsistency. UEX's parent-level {@code
   * has_refinery} claim disagrees with its own terminal list in both directions — it misses MIC-L5,
   * ARC-L4 and Patch City, and claims four People's Service Stations that host no refinery. The raw
   * claim stays in {@code has_refinery} for diagnostics; the derived flag is what the
   * refinery-order picker and its create/update gate read.
   *
   * <p>Called once per sweep from {@code UexScheduler}'s {@code finally}, after every sync step has
   * had its turn — deliberately NOT at the end of {@link #syncTerminals()}, where it used to sit.
   * Two reasons, both load-bearing:
   *
   * <ul>
   *   <li>It must see BOTH tables current. It matches terminals against {@code city} / {@code
   *       space_station} rows by name, so running it inside {@code syncTerminals()} — which now
   *       leads the sweep — would reconcile against parents that this tick has not synced yet.
   *   <li>Running it in the {@code finally} means a later step aborting the sweep no longer costs
   *       the refinery feature its flags: terminals are already committed by then, so the derived
   *       truth still lands. This mirrors the master-data cache eviction that shares that block.
   * </ul>
   *
   * <p>It issues no network call — a pure local derivation over already-committed rows — so it is
   * safe in a {@code finally} even when the sweep aborted because UEX was unreachable.
   *
   * <p>Deriving the flag here (rather than resolving it per read) is what keeps the order write
   * path free of an extra query — see the V226 migration note.
   *
   * <p>Matching is by the terminal's denormalised parent-name columns, mirroring {@code
   * RefineryYieldRepository.findAllForLocation}: the city branch additionally requires {@code
   * spaceStationName IS NULL} so a refinery at a station <em>within</em> a city does not promote
   * the city itself.
   *
   * <p><strong>{@code @Transactional} here is load-bearing — do not drop it.</strong> The class
   * default is {@code @Transactional(readOnly = true)}. While this method was private and called
   * from inside {@link #syncTerminals()} it simply ran in that method's read-write transaction; now
   * that the scheduler calls it from outside, the Spring proxy applies the class default and starts
   * a genuinely read-only transaction, in which Hibernate switches to {@code FlushMode.MANUAL} and
   * <em>silently discards</em> the {@code save} calls below. No test would catch that: {@code
   * UexUniverseSyncRefineryFlagTest} is {@code @Transactional} itself, so this method would merely
   * join the test's read-write transaction and pass while production wrote nothing.
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
