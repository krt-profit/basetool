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

import de.greluc.krt.profit.basetool.backend.audit.api.AuditChangedFields;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationMapper;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationReferenceDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationUsage;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.DuplicateEntityException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.EntityInUseException;
import de.greluc.krt.profit.basetool.backend.kernel.LikePatterns;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.platform.api.CacheConfig;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cached CRUD service for the admin-curated {@code location} reference table.
 *
 * <p>{@code hidden} removes a row from normal dropdowns; a reference-only row (REQ-P4K-039) appears
 * in no list at all; {@code delete} is rejected with {@link EntityInUseException} while any
 * registered {@link LocationUsage} reports the location in use.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LocationService {

  private final LocationRepository locationRepository;
  private final LocationMapper locationMapper;
  private final List<LocationUsage> locationUsages;
  private final AuditRecorder auditRecorder;
  private final StarmapObjectRepository starmapObjectRepository;
  private final StarmapVisibility starmapVisibility;
  private final StarmapPlaceRows starmapPlaceRows;

  /**
   * Returns cached page of locations.
   *
   * @param pageable page request
   * @param includeHidden true to include hidden entries (admin view)
   * @return cached page of location read models
   */
  @Cacheable(cacheNames = CacheConfig.LOCATIONS_CACHE)
  public Page<LocationDto> getAllLocations(@NotNull Pageable pageable, boolean includeHidden) {
    return withP4kNames(
        includeHidden
            ? locationRepository.findByReferenceOnlyFalse(pageable)
            : locationRepository.findByHiddenFalseAndReferenceOnlyFalse(pageable));
  }

  /**
   * Pages the locations whose name contains {@code query} case-insensitively; not cached, because
   * the search term would make the cache key unbounded.
   *
   * @param query the search fragment, already stripped and non-blank
   * @param includeHidden true to include hidden entries (admin view)
   * @param pageable page request
   * @return paged matching location read models
   */
  public Page<LocationDto> searchLocations(
      @NotNull String query, boolean includeHidden, @NotNull Pageable pageable) {
    return withP4kNames(
        includeHidden
            ? locationRepository.findByReferenceOnlyFalseAndNameContainingIgnoreCase(
                query, pageable)
            : locationRepository.findByHiddenFalseAndReferenceOnlyFalseAndNameContainingIgnoreCase(
                query, pageable));
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
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when no match
   */
  @Cacheable(cacheNames = CacheConfig.LOCATIONS_CACHE)
  public LocationDto getLocation(@NotNull UUID id) {
    return withP4kNames(List.of(locationMapper.toDto(requireLocation(id)))).getFirst();
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
    return withP4kNames(
        locationRepository.findLocationsWithRefinery().stream()
            .map(locationMapper::toDto)
            .toList());
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
    return withP4kNames(
        locationRepository
            .findByHomeLocationTrueAndHiddenFalseAndReferenceOnlyFalseOrderByNameDesc()
            .stream()
            .map(locationMapper::toDto)
            .toList());
  }

  /**
   * Creates a storage place on a visible starmap object an admin picked (REQ-P4K-036); cities and
   * space stations get theirs from the UEX sync. Case-insensitive duplicate name throws {@link
   * DuplicateEntityException} → 409 before the DB unique-constraint would.
   *
   * @param location transient entity naming its starmap object
   * @return the persisted location
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.LOCATIONS_CACHE, allEntries = true)
  public Location createLocation(@NotNull Location location) {
    if (locationRepository.existsByNameIgnoreCase(location.getName())) {
      throw new DuplicateEntityException(
          "A Location with the name '" + location.getName() + "' already exists.");
    }
    UUID objectId = requirePlaceableNode(location.getStarmapObjectId());
    Location saved = locationRepository.save(location);
    auditRecorder.record(
        AuditEventType.CATALOGUE_STORAGE_PLACE_CREATED,
        saved.getId(),
        saved.getName(),
        null,
        AuditDetails.of("starmapObjectId", objectId).with("hidden", saved.getHidden()));
    return saved;
  }

  /**
   * Checks that a new storage place may sit on a starmap object (REQ-P4K-036): the object exists,
   * is visible (REQ-P4K-038), carries no storage place yet and is not linked by a city or space
   * station, whose places the UEX sync creates.
   *
   * @param objectId the starmap object the admin picked, or {@code null}
   * @return the object's id
   * @throws BadRequestException when none is given or the object is not visible
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when it does not exist
   * @throws DuplicateEntityException when it already carries a storage place
   */
  @NotNull
  private UUID requirePlaceableNode(@Nullable UUID objectId) {
    if (objectId == null) {
      throw new BadRequestException("A storage place is created on a starmap object.");
    }
    StarmapObject object =
        Entities.require(starmapObjectRepository.findById(objectId), "Starmap object not found");
    if (!StarmapVisibility.isVisible(object, starmapVisibility.liveSystems())) {
      throw new BadRequestException("Only a visible starmap object can carry a storage place.");
    }
    if (locationRepository.existsByStarmapObjectId(objectId)
        || starmapPlaceRows.isLinkedByCityOrSpaceStation(objectId)) {
      throw new DuplicateEntityException("The starmap object already carries a storage place.");
    }
    return objectId;
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

    AuditChangedFields changed =
        AuditChangedFields.create()
            .compare("name", location.getName(), locationDto.name())
            .compare("description", location.getDescription(), locationDto.description())
            .compare("hidden", location.getHidden(), locationDto.hidden())
            .compare("homeLocation", location.getHomeLocation(), locationDto.homeLocation());
    if (!changed.isEmpty()) {
      auditRecorder.record(
          AuditEventType.CATALOGUE_LOCATION_UPDATED,
          id,
          locationDto.name(),
          null,
          changed.toDetails());
    }
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
    auditRecorder.record(
        AuditEventType.CATALOGUE_LOCATION_DELETED, id, location.getName(), null, null);
  }

  /**
   * Adds the P4K names of the linked starmap objects to a page of locations (REQ-P4K-037).
   *
   * @param page the page of entities
   * @return the page of DTOs
   */
  @NotNull
  private Page<LocationDto> withP4kNames(@NotNull Page<Location> page) {
    List<LocationDto> dtos = page.getContent().stream().map(locationMapper::toDto).toList();
    return new PageImpl<>(withP4kNames(dtos), page.getPageable(), page.getTotalElements());
  }

  /**
   * Adds the P4K names of the linked starmap objects to locations, in one query.
   *
   * @param dtos the locations
   * @return the locations, each linked one with its P4K names
   */
  @NotNull
  private List<LocationDto> withP4kNames(@NotNull List<LocationDto> dtos) {
    if (dtos.isEmpty()) {
      return dtos;
    }
    Map<UUID, P4kNames> names = new HashMap<>();
    for (Object[] tuple :
        starmapObjectRepository.findP4kNamesOfLocations(
            dtos.stream().map(LocationDto::id).toList())) {
      names.put((UUID) tuple[0], new P4kNames((String) tuple[1], (String) tuple[2]));
    }
    return dtos.stream()
        .map(dto -> {
          P4kNames linked = names.get(dto.id());
          return linked == null ? dto : dto.withP4kNames(linked.name(), linked.nameDe());
        })
        .toList();
  }

  /**
   * The P4K names of a location's starmap object.
   *
   * @param name the English name
   * @param nameDe the German name
   */
  private record P4kNames(@Nullable String name, @Nullable String nameDe) {}
}
