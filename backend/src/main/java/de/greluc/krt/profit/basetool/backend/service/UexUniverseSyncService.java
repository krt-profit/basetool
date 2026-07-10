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
import java.time.Instant;
import java.util.List;
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
 * moons/orbits/POI (parented by planet), then cities (parented by planet/moon), then outposts /
 * space stations / terminals (parented by city/station). Calling them out of order means a child
 * row references a parent that does not yet exist in the local mirror, and the child is silently
 * dropped (the scheduler retries the full sweep on the next tick so missed children eventually
 * land).
 *
 * <p>Every sync method follows the same pattern: pull the full UEX catalog for that entity, upsert
 * by UEX id (with name-based fallback for legacy rows missing the id), per-field dirty checking to
 * minimize write traffic. Empty UEX responses short-circuit without wiping local data.
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
    List<UexCityDto> dtos = uexClient.getCities();
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
      entity.setIsAvailable(dto.isAvailable() != null && dto.isAvailable() == 1);
      entity.setIsVisible(dto.isVisible() != null && dto.isVisible() == 1);
      entity.setIsDefault(dto.isDefault() != null && dto.isDefault() == 1);
      entity.setIsMonitored(dto.isMonitored() != null && dto.isMonitored() == 1);
      entity.setIsArmistice(dto.isArmistice() != null && dto.isArmistice() == 1);
      entity.setIsLandable(dto.isLandable() != null && dto.isLandable() == 1);
      entity.setIsDecommissioned(dto.isDecommissioned() != null && dto.isDecommissioned() == 1);
      entity.setHasQuantumMarker(dto.hasQuantumMarker() != null && dto.hasQuantumMarker() == 1);
      entity.setHasTradeTerminal(dto.hasTradeTerminal() != null && dto.hasTradeTerminal() == 1);
      entity.setHasHabitation(dto.hasHabitation() != null && dto.hasHabitation() == 1);
      entity.setHasRefinery(dto.hasRefinery() != null && dto.hasRefinery() == 1);
      entity.setHasCargoCenter(dto.hasCargoCenter() != null && dto.hasCargoCenter() == 1);
      entity.setHasClinic(dto.hasClinic() != null && dto.hasClinic() == 1);
      entity.setHasFood(dto.hasFood() != null && dto.hasFood() == 1);
      entity.setHasShops(dto.hasShops() != null && dto.hasShops() == 1);
      entity.setHasRefuel(dto.hasRefuel() != null && dto.hasRefuel() == 1);
      entity.setHasRepair(dto.hasRepair() != null && dto.hasRepair() == 1);
      entity.setHasGravity(dto.hasGravity() != null && dto.hasGravity() == 1);
      if (!Boolean.TRUE.equals(entity.getHasLoadingDockOverridden())) {
        entity.setHasLoadingDock(dto.hasLoadingDock() != null && dto.hasLoadingDock() == 1);
      }
      entity.setHasDockingPort(dto.hasDockingPort() != null && dto.hasDockingPort() == 1);
      entity.setHasFreightElevator(
          dto.hasFreightElevator() != null && dto.hasFreightElevator() == 1);
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
    List<UexFactionDto> dtos = uexClient.getFactions();
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
    List<UexJurisdictionDto> dtos = uexClient.getJurisdictions();
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
    List<UexMoonDto> dtos = uexClient.getMoons();
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
      entity.setIsAvailable(dto.isAvailable() != null && dto.isAvailable() == 1);
      entity.setIsVisible(dto.isVisible() != null && dto.isVisible() == 1);
      entity.setIsDefault(dto.isDefault() != null && dto.isDefault() == 1);
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
    List<UexOrbitDto> dtos = uexClient.getOrbits();
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
    List<UexOutpostDto> dtos = uexClient.getOutposts();
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
      entity.setIsAvailable(dto.isAvailable() != null && dto.isAvailable() == 1);
      entity.setIsVisible(dto.isVisible() != null && dto.isVisible() == 1);
      entity.setIsDefault(dto.isDefault() != null && dto.isDefault() == 1);
      entity.setIsMonitored(dto.isMonitored() != null && dto.isMonitored() == 1);
      entity.setIsArmistice(dto.isArmistice() != null && dto.isArmistice() == 1);
      entity.setIsLandable(dto.isLandable() != null && dto.isLandable() == 1);
      entity.setIsDecommissioned(dto.isDecommissioned() != null && dto.isDecommissioned() == 1);
      entity.setHasQuantumMarker(dto.hasQuantumMarker() != null && dto.hasQuantumMarker() == 1);
      entity.setHasTradeTerminal(dto.hasTradeTerminal() != null && dto.hasTradeTerminal() == 1);
      entity.setHasHabitation(dto.hasHabitation() != null && dto.hasHabitation() == 1);
      entity.setHasRefinery(dto.hasRefinery() != null && dto.hasRefinery() == 1);
      entity.setHasCargoCenter(dto.hasCargoCenter() != null && dto.hasCargoCenter() == 1);
      entity.setHasClinic(dto.hasClinic() != null && dto.hasClinic() == 1);
      entity.setHasFood(dto.hasFood() != null && dto.hasFood() == 1);
      entity.setHasShops(dto.hasShops() != null && dto.hasShops() == 1);
      entity.setHasRefuel(dto.hasRefuel() != null && dto.hasRefuel() == 1);
      entity.setHasRepair(dto.hasRepair() != null && dto.hasRepair() == 1);
      entity.setHasGravity(dto.hasGravity() != null && dto.hasGravity() == 1);
      if (!Boolean.TRUE.equals(entity.getHasLoadingDockOverridden())) {
        entity.setHasLoadingDock(dto.hasLoadingDock() != null && dto.hasLoadingDock() == 1);
      }
      entity.setHasDockingPort(dto.hasDockingPort() != null && dto.hasDockingPort() == 1);
      entity.setHasFreightElevator(
          dto.hasFreightElevator() != null && dto.hasFreightElevator() == 1);
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
    List<UexPlanetDto> dtos = uexClient.getPlanets();
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
      entity.setIsAvailable(dto.isAvailable() != null && dto.isAvailable() == 1);
      entity.setIsVisible(dto.isVisible() != null && dto.isVisible() == 1);
      entity.setIsDefault(dto.isDefault() != null && dto.isDefault() == 1);
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
    List<UexPoiDto> dtos = uexClient.getPoi();
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
      entity.setIsAvailable(dto.isAvailable() != null && dto.isAvailable() == 1);
      entity.setIsVisible(dto.isVisible() != null && dto.isVisible() == 1);
      entity.setIsDefault(dto.isDefault() != null && dto.isDefault() == 1);
      entity.setIsMonitored(dto.isMonitored() != null && dto.isMonitored() == 1);
      entity.setIsArmistice(dto.isArmistice() != null && dto.isArmistice() == 1);
      entity.setIsLandable(dto.isLandable() != null && dto.isLandable() == 1);
      entity.setIsDecommissioned(dto.isDecommissioned() != null && dto.isDecommissioned() == 1);
      entity.setHasQuantumMarker(dto.hasQuantumMarker() != null && dto.hasQuantumMarker() == 1);
      entity.setHasTradeTerminal(dto.hasTradeTerminal() != null && dto.hasTradeTerminal() == 1);
      entity.setHasHabitation(dto.hasHabitation() != null && dto.hasHabitation() == 1);
      entity.setHasRefinery(dto.hasRefinery() != null && dto.hasRefinery() == 1);
      entity.setHasCargoCenter(dto.hasCargoCenter() != null && dto.hasCargoCenter() == 1);
      entity.setHasClinic(dto.hasClinic() != null && dto.hasClinic() == 1);
      entity.setHasFood(dto.hasFood() != null && dto.hasFood() == 1);
      entity.setHasShops(dto.hasShops() != null && dto.hasShops() == 1);
      entity.setHasRefuel(dto.hasRefuel() != null && dto.hasRefuel() == 1);
      entity.setHasRepair(dto.hasRepair() != null && dto.hasRepair() == 1);
      entity.setHasGravity(dto.hasGravity() != null && dto.hasGravity() == 1);
      if (!Boolean.TRUE.equals(entity.getHasLoadingDockOverridden())) {
        entity.setHasLoadingDock(dto.hasLoadingDock() != null && dto.hasLoadingDock() == 1);
      }
      entity.setHasDockingPort(dto.hasDockingPort() != null && dto.hasDockingPort() == 1);
      entity.setHasFreightElevator(
          dto.hasFreightElevator() != null && dto.hasFreightElevator() == 1);
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
    List<UexSpaceStationDto> dtos = uexClient.getSpaceStations();
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
      entity.setIsAvailable(dto.isAvailable() != null && dto.isAvailable() == 1);
      entity.setIsVisible(dto.isVisible() != null && dto.isVisible() == 1);
      entity.setIsDefault(dto.isDefault() != null && dto.isDefault() == 1);
      entity.setIsMonitored(dto.isMonitored() != null && dto.isMonitored() == 1);
      entity.setIsArmistice(dto.isArmistice() != null && dto.isArmistice() == 1);
      entity.setIsLandable(dto.isLandable() != null && dto.isLandable() == 1);
      entity.setIsDecommissioned(dto.isDecommissioned() != null && dto.isDecommissioned() == 1);
      entity.setIsLagrange(dto.isLagrange() != null && dto.isLagrange() == 1);
      entity.setIsJumpPoint(dto.isJumpPoint() != null && dto.isJumpPoint() == 1);
      entity.setHasQuantumMarker(dto.hasQuantumMarker() != null && dto.hasQuantumMarker() == 1);
      entity.setHasTradeTerminal(dto.hasTradeTerminal() != null && dto.hasTradeTerminal() == 1);
      entity.setHasHabitation(dto.hasHabitation() != null && dto.hasHabitation() == 1);
      entity.setHasRefinery(dto.hasRefinery() != null && dto.hasRefinery() == 1);
      entity.setHasCargoCenter(dto.hasCargoCenter() != null && dto.hasCargoCenter() == 1);
      entity.setHasClinic(dto.hasClinic() != null && dto.hasClinic() == 1);
      entity.setHasFood(dto.hasFood() != null && dto.hasFood() == 1);
      entity.setHasShops(dto.hasShops() != null && dto.hasShops() == 1);
      entity.setHasRefuel(dto.hasRefuel() != null && dto.hasRefuel() == 1);
      entity.setHasRepair(dto.hasRepair() != null && dto.hasRepair() == 1);
      entity.setHasGravity(dto.hasGravity() != null && dto.hasGravity() == 1);
      if (!Boolean.TRUE.equals(entity.getHasLoadingDockOverridden())) {
        entity.setHasLoadingDock(dto.hasLoadingDock() != null && dto.hasLoadingDock() == 1);
      }
      entity.setHasDockingPort(dto.hasDockingPort() != null && dto.hasDockingPort() == 1);
      entity.setHasFreightElevator(
          dto.hasFreightElevator() != null && dto.hasFreightElevator() == 1);
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
    List<UexTerminalDto> dtos = uexClient.getTerminals();
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
      entity.setIsAvailableLive(dto.checkIsAvailableLive());
      entity.setIsAvailable(dto.isAvailable() != null && dto.isAvailable() == 1);
      entity.setIsVisible(dto.isVisible() != null && dto.isVisible() == 1);
      entity.setIsJumpPoint(dto.isJumpPoint() != null && dto.isJumpPoint() == 1);
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
      entity.setHasDockingPort(dto.hasDockingPort() != null && dto.hasDockingPort() == 1);
      entity.setHasFreightElevator(
          dto.hasFreightElevator() != null && dto.hasFreightElevator() == 1);
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
}
