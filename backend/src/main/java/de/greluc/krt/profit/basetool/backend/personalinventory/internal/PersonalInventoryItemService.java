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

package de.greluc.krt.profit.basetool.backend.personalinventory.internal;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.catalogue.api.City;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.SpaceStation;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.LikePatterns;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Domain service for the personal inventory.
 *
 * <p>{@code *Own*} methods are owner-scoped via {@code findByIdAndOwnerUserId}; {@code *ForUser*}
 * methods act on any user and are restricted to ADMIN by the controller. A stale {@code version}
 * raises {@link ObjectOptimisticLockingFailureException} (409). A write accepts only a location
 * that is selectable for the item's owner (REQ-P4K-039, {@link PersonalInventoryItemRepository}).
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class PersonalInventoryItemService {

  /**
   * Whitelist of sort properties accepted on list endpoints. Restricting this prevents unstable
   * sorting and information disclosure via arbitrary sort keys.
   */
  public static final Set<String> SORTABLE_FIELDS =
      Set.of("id", "name", "quantity", "locationNameSnapshot", "createdAt", "updatedAt");

  public static final String DEFAULT_SORT_FIELD = "updatedAt";

  /** The location-picker result cap when the caller names none. */
  private static final int DEFAULT_LOCATION_RESULTS = 25;

  /** The largest location-picker result. */
  private static final int MAX_LOCATION_RESULTS = 2000;

  private final PersonalInventoryItemRepository repository;
  private final PersonalInventoryItemMapper mapper;
  private final AuditRecorder auditRecorder;

  /**
   * Owner-scoped paged list of the caller's items.
   *
   * @param ownerUserId {@code app_user.id} of the caller
   * @param query optional case-insensitive substring filter on the item name
   * @param pageable page request (sort fields whitelisted by {@link #SORTABLE_FIELDS})
   * @return paged response DTOs
   */
  public Page<PersonalInventoryItemResponse> listOwn(
      @NotNull UUID ownerUserId, @Nullable String query, @NotNull Pageable pageable) {
    Page<PersonalInventoryItem> page =
        (query == null || query.isBlank())
            ? repository.findAllByOwnerUserId(ownerUserId, pageable)
            : repository.findAllByOwnerUserIdAndNameContainingIgnoreCase(
                ownerUserId, query.trim(), pageable);
    return page.map(mapper::toResponse);
  }

  /**
   * Owner-scoped lookup of a single item; an unknown id and another user's item both give 404.
   *
   * @param ownerUserId {@code app_user.id} of the caller
   * @param id item primary key
   * @return response DTO
   * @throws NotFoundException when the item is missing or owned by someone else
   */
  public PersonalInventoryItemResponse getOwn(@NotNull UUID ownerUserId, @NotNull UUID id) {
    return mapper.toResponse(loadOwn(ownerUserId, id));
  }

  /**
   * Creates an item owned by the caller at a selectable location, storing the location's name as a
   * snapshot.
   *
   * @param ownerUserId {@code app_user.id} of the caller
   * @param request create payload
   * @return the persisted DTO
   * @throws NotFoundException when the location is unknown or not selectable for the owner
   */
  @Transactional
  public PersonalInventoryItemResponse createOwn(
      @NotNull UUID ownerUserId, @NotNull PersonalInventoryItemCreateRequest request) {
    Location location = requireSelectable(ownerUserId, request.locationId());
    PersonalInventoryItem entity = mapper.toEntity(request);
    entity.setOwnerUserId(ownerUserId);
    entity.setLocation(location);
    entity.setLocationNameSnapshot(location.getName());
    PersonalInventoryItem saved = repository.save(entity);
    log.info(
        "Created personal inventory item id={} for ownerUserId={}", saved.getId(), ownerUserId);
    auditRecorder.record(
        AuditEventType.PERSONAL_INVENTORY_CREATED,
        saved.getId(),
        personalLabel(saved),
        ownerUserId,
        AuditDetails.of("qty", saved.getQuantity()).with("loc", saved.getLocationNameSnapshot()));
    return mapper.toResponse(saved);
  }

  /**
   * Updates an owner-scoped item with explicit optimistic-lock check. Re-takes the location
   * snapshot only when the location actually changed.
   *
   * @param ownerUserId {@code app_user.id} of the caller
   * @param id item primary key
   * @param request update payload (carries the expected version)
   * @return the persisted DTO
   * @throws NotFoundException when the item is missing, owned by someone else, or the new location
   *     is not selectable
   * @throws BadRequestException when the request drops the location of an item that has one
   * @throws ObjectOptimisticLockingFailureException when the supplied version is stale
   */
  @Transactional
  public PersonalInventoryItemResponse updateOwn(
      @NotNull UUID ownerUserId,
      @NotNull UUID id,
      @NotNull PersonalInventoryItemUpdateRequest request) {
    PersonalInventoryItem entity = loadOwn(ownerUserId, id);
    return applyUpdate(entity, request);
  }

  /**
   * Deletes an owner-scoped item. 404 for unknown id / cross-owner attempts.
   *
   * @param ownerUserId {@code app_user.id} of the caller
   * @param id item primary key
   * @throws NotFoundException when the item is missing or owned by someone else
   */
  @Transactional
  public void deleteOwn(@NotNull UUID ownerUserId, @NotNull UUID id) {
    PersonalInventoryItem entity = loadOwn(ownerUserId, id);
    String label = personalLabel(entity);
    repository.delete(entity);
    log.info("Deleted personal inventory item id={} for ownerUserId={}", id, ownerUserId);
    auditRecorder.record(
        AuditEventType.PERSONAL_INVENTORY_DELETED, id, label, ownerUserId, "scope=own");
  }

  /**
   * Admin-scoped list of a target user's items; same behavior as {@link #listOwn}.
   *
   * @param targetSub {@code app_user.id} of the user being inspected
   * @param query optional name filter
   * @param pageable page request
   * @return paged response DTOs
   */
  public Page<PersonalInventoryItemResponse> listForUser(
      @NotNull UUID targetSub, @Nullable String query, @NotNull Pageable pageable) {
    return listOwn(targetSub, query, pageable);
  }

  /**
   * Admin-scoped create. Delegates to {@link #createOwn}; the controller layer is responsible for
   * enforcing the ADMIN role.
   *
   * @param targetSub {@code app_user.id} of the user to create the item for
   * @param request create payload
   * @return the persisted DTO
   */
  @Transactional
  public PersonalInventoryItemResponse createForUser(
      @NotNull UUID targetSub, @NotNull PersonalInventoryItemCreateRequest request) {
    return createOwn(targetSub, request);
  }

  /**
   * Admin-scoped update by item id alone, with the optimistic-lock check; the selectable locations
   * are the item owner's.
   *
   * @param id item primary key
   * @param request update payload (carries the expected version)
   * @return the persisted DTO
   * @throws NotFoundException when the item id is unknown or the new location is not selectable
   * @throws BadRequestException when the request drops the location of an item that has one
   * @throws ObjectOptimisticLockingFailureException when the supplied version is stale
   */
  @Transactional
  public PersonalInventoryItemResponse updateForUser(
      @NotNull UUID id, @NotNull PersonalInventoryItemUpdateRequest request) {
    PersonalInventoryItem entity =
        Entities.require(repository.findById(id), () -> "PersonalInventoryItem not found: " + id);
    return applyUpdate(entity, request);
  }

  /**
   * Admin-scoped delete by item id alone; logs the owner at INFO.
   *
   * @param id item primary key
   * @throws NotFoundException when the item id is unknown
   */
  @Transactional
  public void deleteForUser(@NotNull UUID id) {
    PersonalInventoryItem entity =
        Entities.require(repository.findById(id), () -> "PersonalInventoryItem not found: " + id);
    String label = personalLabel(entity);
    UUID ownerUserId = entity.getOwnerUserId();
    repository.delete(entity);
    log.info(
        "Admin deleted personal inventory item id={} ownerUserId={}", id, entity.getOwnerUserId());
    auditRecorder.record(
        AuditEventType.PERSONAL_INVENTORY_DELETED, id, label, ownerUserId, "scope=admin");
  }

  /**
   * Searches the locations selectable for the owner by name (REQ-P4K-039): every non-hidden storage
   * place plus every location one of the owner's items references, case-insensitive and literal, by
   * name.
   *
   * @param ownerUserId {@code app_user.id} of the inventory's owner
   * @param query the name fragment, or {@code null}/blank for no filter
   * @param limit the result cap, clamped to {@code [1, 2000]}; {@code null} means 25
   * @return the matching picker entries, alphabetically
   */
  public List<PersonalInventoryLocationOption> searchLocations(
      @NotNull UUID ownerUserId, @Nullable String query, @Nullable Integer limit) {
    String needle = query == null || query.isBlank() ? null : query.trim();
    int cap = limit == null ? DEFAULT_LOCATION_RESULTS : Math.clamp(limit, 1, MAX_LOCATION_RESULTS);
    return repository
        .findSelectableLocations(
            ownerUserId, LikePatterns.escapeNullable(needle), PageRequest.of(0, cap))
        .stream()
        .map(PersonalInventoryItemService::toOption)
        .toList();
  }

  @NotNull
  private PersonalInventoryItem loadOwn(@NotNull UUID ownerUserId, @NotNull UUID id) {
    return repository
        .findByIdAndOwnerUserId(id, ownerUserId)
        .orElseThrow(() -> {
          log.warn("Access denied or not found: ownerUserId={} requested id={}", ownerUserId, id);
          return new NotFoundException("PersonalInventoryItem not found: " + id);
        });
  }

  @NotNull
  private PersonalInventoryItemResponse applyUpdate(
      @NotNull PersonalInventoryItem entity, @NotNull PersonalInventoryItemUpdateRequest request) {
    OptimisticLock.check(
        entity.getVersion(), request.version(), PersonalInventoryItem.class, entity.getId());
    Location current = entity.getLocation();
    UUID requested = request.locationId();
    if (!Objects.equals(current == null ? null : current.getId(), requested)) {
      if (requested == null) {
        throw new BadRequestException("A personal inventory item keeps its location.");
      }
      Location location = requireSelectable(entity.getOwnerUserId(), requested);
      entity.setLocation(location);
      entity.setLocationNameSnapshot(location.getName());
    }

    mapper.updateEntity(entity, request);
    PersonalInventoryItem saved = repository.save(entity);
    log.info(
        "Updated personal inventory item id={} ownerUserId={}",
        saved.getId(),
        saved.getOwnerUserId());
    auditRecorder.record(
        AuditEventType.PERSONAL_INVENTORY_UPDATED,
        saved.getId(),
        personalLabel(saved),
        saved.getOwnerUserId(),
        AuditDetails.of("qty", saved.getQuantity()).with("loc", saved.getLocationNameSnapshot()));
    return mapper.toResponse(saved);
  }

  /**
   * Composes the audit subject label for a personal inventory item — {@code name @ location}.
   *
   * @param item the personal inventory item
   * @return the {@code name @ location} label
   */
  @NotNull
  private static String personalLabel(@NotNull PersonalInventoryItem item) {
    return item.getName() + " @ " + item.getLocationNameSnapshot();
  }

  /**
   * Loads a location the owner may pick; anything else is a 404, so a write cannot reference a
   * place the pickers do not offer.
   */
  @NotNull
  private Location requireSelectable(@NotNull UUID ownerUserId, @NotNull UUID locationId) {
    return Entities.require(
        repository.findSelectableLocation(ownerUserId, locationId),
        () -> "Location not selectable: id=" + locationId);
  }

  /**
   * Builds the picker entry of a location from its city or space station.
   *
   * @param location a location with its city and station loaded
   * @return the picker entry
   */
  @NotNull
  private static PersonalInventoryLocationOption toOption(@NotNull Location location) {
    City city = location.getCity();
    SpaceStation station = location.getSpaceStation();
    String system =
        city != null
            ? city.getStarSystemName()
            : station != null ? station.getStarSystemName() : null;
    return new PersonalInventoryLocationOption(
        location.getId(),
        PersonalInventoryLocationType.of(location),
        location.getName(),
        system,
        city != null ? city.getPlanetName() : null);
  }
}
