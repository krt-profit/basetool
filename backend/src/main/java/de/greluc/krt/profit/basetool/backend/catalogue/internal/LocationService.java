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

import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationMapper;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationReferenceDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationUsage;
import de.greluc.krt.profit.basetool.backend.config.CacheConfig;
import de.greluc.krt.profit.basetool.backend.exception.DuplicateEntityException;
import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.exception.EntityInUseException;
import de.greluc.krt.profit.basetool.backend.kernel.LikePatterns;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import java.util.Comparator;
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
 * EntityInUseException} while any registered {@link LocationUsage} reports the location in use.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LocationService {

  private final LocationRepository locationRepository;
  private final LocationMapper locationMapper;
  private final List<LocationUsage> locationUsages;

  /**
   * Returns cached page of locations.
   *
   * @param pageable page request
   * @param includeHidden true to include hidden entries (admin view)
   * @return cached page of location read models
   */
  @Cacheable(cacheNames = CacheConfig.LOCATIONS_CACHE)
  public Page<LocationDto> getAllLocations(@NotNull Pageable pageable, boolean includeHidden) {
    return (includeHidden
            ? locationRepository.findAll(pageable)
            : locationRepository.findByHiddenFalse(pageable))
        .map(locationMapper::toDto);
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
   * @return the location read model
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when no match
   */
  @Cacheable(cacheNames = CacheConfig.LOCATIONS_CACHE)
  public LocationDto getLocation(@NotNull UUID id) {
    return locationMapper.toDto(requireLocation(id));
  }

  /**
   * Loads the managed location entity a mutator edits; it never comes from the cache.
   *
   * @param id location primary key
   * @return the managed entity
   */
  private Location requireLocation(UUID id) {
    return Entities.require(locationRepository.findById(id), "Location not found");
  }

  /**
   * Lists only the locations that host a refinery (used by the refinery-order create form). Single
   * fixed-key cache entry shared by every caller.
   *
   * @return cached list of location read models
   */
  @Cacheable(cacheNames = CacheConfig.LOCATIONS_CACHE, key = "'refineries'")
  public List<LocationDto> getRefineryLocations() {
    return locationRepository.findLocationsWithRefinery().stream()
        .map(locationMapper::toDto)
        .toList();
  }

  /**
   * Lists the admin-curated home locations (non-hidden), name descending — the source for the
   * hangar bulk "set home location" picker. Single fixed-key cache entry shared by every caller;
   * evicted whenever a location is created/updated/deleted.
   *
   * @return curated home location read models, name descending
   */
  @Cacheable(cacheNames = CacheConfig.LOCATIONS_CACHE, key = "'homeLocations'")
  public List<LocationDto> getHomeLocations() {
    return locationRepository.findByHomeLocationTrueAndHiddenFalseOrderByNameDesc().stream()
        .map(locationMapper::toDto)
        .toList();
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
    Location location = requireLocation(id);

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
   * @throws EntityInUseException when a registered usage still references the location; the first
   *     one by position names it
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.LOCATIONS_CACHE, allEntries = true)
  public void deleteLocation(@NotNull UUID id) {
    List<LocationUsage> inAskingOrder =
        locationUsages.stream().sorted(Comparator.comparingInt(LocationUsage::position)).toList();
    for (LocationUsage usage : inAskingOrder) {
      if (usage.isUsed(id)) {
        throw new EntityInUseException(
            "Cannot delete location: It is currently used by one or more " + usage.users() + ".");
      }
    }
    Location location = requireLocation(id);
    locationRepository.delete(location);
  }
}
