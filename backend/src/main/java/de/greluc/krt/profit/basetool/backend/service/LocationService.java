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

import de.greluc.krt.profit.basetool.backend.config.CacheConfig;
import de.greluc.krt.profit.basetool.backend.exception.DuplicateEntityException;
import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.exception.EntityInUseException;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.dto.LocationDto;
import de.greluc.krt.profit.basetool.backend.model.dto.LocationReferenceDto;
import de.greluc.krt.profit.basetool.backend.repository.LocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.RefineryOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipRepository;
import de.greluc.krt.profit.basetool.backend.support.CachedEntityGraphs;
import de.greluc.krt.profit.basetool.backend.support.LikePatterns;
import de.greluc.krt.profit.basetool.backend.support.OptimisticLock;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cached CRUD service for the admin-curated {@code location} reference table.
 *
 * <p>{@code hidden} removes a row from normal dropdowns; {@code delete} is rejected with {@link
 * EntityInUseException} while any ship or refinery order references the location.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LocationService {

  private final LocationRepository locationRepository;
  private final ShipRepository shipRepository;
  private final RefineryOrderRepository refineryOrderRepository;

  /**
   * Returns cached page of locations.
   *
   * @param pageable page request
   * @param includeHidden true to include hidden entries (admin view)
   * @return cached page of locations
   */
  @Cacheable(cacheNames = CacheConfig.LOCATIONS_CACHE)
  public Page<Location> getAllLocations(@NotNull Pageable pageable, boolean includeHidden) {
    return CachedEntityGraphs.locations(
        includeHidden
            ? locationRepository.findAll(pageable)
            : locationRepository.findByHiddenFalse(pageable));
  }

  /**
   * Returns all non-hidden locations as id + name reference DTOs, unbounded; payload-bounded
   * pickers use {@link #searchReference(String, Pageable)}.
   *
   * @return all non-hidden locations as reference DTOs
   */
  public List<LocationReferenceDto> findAllReference() {
    return locationRepository.findAllReference();
  }

  /**
   * Uncached live search for the location pickers (REQ-FE-016): pages non-hidden locations whose
   * name contains {@code search}, case-insensitive and literal.
   *
   * @param search the raw name fragment, or {@code null}/blank for the unfiltered first page
   * @param pageable page request from the whitelisted picker sort
   * @return one page of matching non-hidden locations as reference DTOs
   */
  public Page<LocationReferenceDto> searchReference(
      @Nullable String search, @NotNull Pageable pageable) {
    return locationRepository.searchReference(LikePatterns.escapeNullable(search), pageable);
  }

  /**
   * Returns the location.
   *
   * @param id location primary key
   * @return the location
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when no match
   */
  @Cacheable(cacheNames = CacheConfig.LOCATIONS_CACHE)
  public Location getLocation(@NotNull UUID id) {
    return CachedEntityGraphs.location(
        Entities.require(locationRepository.findById(id), "Location not found"));
  }

  /**
   * Lists only the locations that host a refinery (used by the refinery-order create form). Single
   * fixed-key cache entry shared by every caller.
   *
   * @return cached list
   */
  @Cacheable(cacheNames = CacheConfig.LOCATIONS_CACHE, key = "'refineries'")
  public List<Location> getRefineryLocations() {
    return CachedEntityGraphs.locations(locationRepository.findLocationsWithRefinery());
  }

  /**
   * Lists the admin-curated home locations (non-hidden), name descending — the source for the
   * hangar bulk "set home location" picker. Single fixed-key cache entry shared by every caller;
   * evicted whenever a location is created/updated/deleted.
   *
   * @return curated home locations, name descending
   */
  @Cacheable(cacheNames = CacheConfig.LOCATIONS_CACHE, key = "'homeLocations'")
  public List<Location> getHomeLocations() {
    return CachedEntityGraphs.locations(
        locationRepository.findByHomeLocationTrueAndHiddenFalseOrderByNameDesc());
  }

  /**
   * Persists a new location. Case-insensitive duplicate name throws {@link
   * DuplicateEntityException} → 409 before the DB unique-constraint would.
   *
   * @param location transient entity
   * @return the persisted location
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.LOCATIONS_CACHE, allEntries = true)
  public Location createLocation(@NotNull Location location) {
    if (locationRepository.existsByNameIgnoreCase(location.getName())) {
      throw new DuplicateEntityException(
          "A Location with the name '" + location.getName() + "' already exists.");
    }
    return locationRepository.save(location);
  }

  /**
   * Updates an existing location. Carries the optimistic-lock version through the DTO.
   *
   * @param id location primary key
   * @param locationDto update payload
   * @return the persisted location
   * @throws DuplicateEntityException when the new name collides with a different row
   * @throws ObjectOptimisticLockingFailureException when the supplied version is stale
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.LOCATIONS_CACHE, allEntries = true)
  public Location updateLocation(@NotNull UUID id, @NotNull LocationDto locationDto) {
    if (locationRepository.existsByNameIgnoreCaseAndIdNot(locationDto.name(), id)) {
      throw new DuplicateEntityException(
          "A Location with the name '" + locationDto.name() + "' already exists.");
    }
    Location location = getLocation(id);

    OptimisticLock.check(location.getVersion(), locationDto.version(), Location.class, id);

    location.setName(locationDto.name());
    location.setDescription(locationDto.description());
    location.setHidden(locationDto.hidden());
    location.setHomeLocation(locationDto.homeLocation());

    return locationRepository.save(location);
  }

  /**
   * Hard-deletes a location after checking it is unreferenced.
   *
   * @param id location primary key
   * @throws EntityInUseException when at least one ship or refinery order still references the
   *     location
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.LOCATIONS_CACHE, allEntries = true)
  public void deleteLocation(@NotNull UUID id) {
    if (shipRepository.existsByLocationId(id)) {
      throw new EntityInUseException(
          "Cannot delete location: It is currently used by one or more ships.");
    }
    if (refineryOrderRepository.existsByLocationId(id)) {
      throw new EntityInUseException(
          "Cannot delete location: It is currently used by one or more refinery orders.");
    }
    Location location = getLocation(id);
    locationRepository.delete(location);
  }
}
