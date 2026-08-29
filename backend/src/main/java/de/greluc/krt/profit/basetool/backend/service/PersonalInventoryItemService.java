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

import de.greluc.krt.profit.basetool.backend.mapper.PersonalInventoryItemMapper;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.City;
import de.greluc.krt.profit.basetool.backend.model.PersonalInventoryItem;
import de.greluc.krt.profit.basetool.backend.model.PersonalInventoryLocationType;
import de.greluc.krt.profit.basetool.backend.model.SpaceStation;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalInventoryItemCreateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalInventoryItemResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalInventoryItemUpdateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.UexLocationDto;
import de.greluc.krt.profit.basetool.backend.repository.CityRepository;
import de.greluc.krt.profit.basetool.backend.repository.PersonalInventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.SpaceStationRepository;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import de.greluc.krt.profit.basetool.backend.support.OptimisticLock;
import jakarta.persistence.EntityNotFoundException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Domain service for the Personal Inventory feature.
 *
 * <p>Two parallel APIs:
 *
 * <ul>
 *   <li>{@code *Own*} – owner-scoped, used by the user-facing controller. Every read and write goes
 *       through {@code findByIdAndOwnerUserId} to enforce data isolation (see AGENTS.md "MULTI-USER
 *       DATA ISOLATION").
 *   <li>{@code *ForUser*} – admin-scoped, used by the admin controller. The admin can load and
 *       modify items belonging to any user; method security on the controller (and a separate URL
 *       prefix) restricts this to {@code ROLE_ADMIN}.
 * </ul>
 *
 * <p>Optimistic locking follows the project convention established in {@code AnnouncementService} /
 * {@code HangarService}: the inbound DTO carries the last seen {@code version}; on mismatch, an
 * {@link ObjectOptimisticLockingFailureException} is raised explicitly so the global handler maps
 * it to HTTP 409.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class PersonalInventoryItemService {

  /**
   * Whitelist of sort properties accepted on list endpoints. Restricting this prevents unstable
   * sorting and information disclosure via arbitrary sort keys (see AGENTS.md "Pagination &
   * Sorting").
   */
  public static final Set<String> SORTABLE_FIELDS =
      Set.of("id", "name", "quantity", "locationNameSnapshot", "createdAt", "updatedAt");

  public static final String DEFAULT_SORT_FIELD = "updatedAt";

  private final PersonalInventoryItemRepository repository;
  private final PersonalInventoryItemMapper mapper;
  private final CityRepository cityRepository;
  private final SpaceStationRepository spaceStationRepository;
  private final AuditService auditService;

  // ---------------------------------------------------------------------------------
  // Owner-scoped API
  // ---------------------------------------------------------------------------------

  /**
   * Owner-scoped paged list. Every row is filtered by {@code ownerUserId} so a caller can never see
   * another user's items even if they craft the query parameters.
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
   * Owner-scoped lookup of a single item. Returns 404 if the id is unknown OR if the item belongs
   * to a different user (the two cases are deliberately indistinguishable in the response so a
   * caller cannot probe for other users' item ids).
   *
   * @param ownerUserId {@code app_user.id} of the caller
   * @param id item primary key
   * @return response DTO
   * @throws EntityNotFoundException when the item is missing or owned by someone else
   */
  public PersonalInventoryItemResponse getOwn(@NotNull UUID ownerUserId, @NotNull UUID id) {
    return mapper.toResponse(loadOwn(ownerUserId, id));
  }

  /**
   * Creates an item owned by the caller. The location name is resolved against the local UEX mirror
   * and stored as a snapshot so a future UEX rename of the city/station does not silently change
   * the displayed location.
   *
   * @param ownerUserId {@code app_user.id} of the caller
   * @param request create payload
   * @return the persisted DTO
   */
  @Transactional
  public PersonalInventoryItemResponse createOwn(
      @NotNull UUID ownerUserId, @NotNull PersonalInventoryItemCreateRequest request) {
    String snapshot = resolveLocationName(request.locationType(), request.locationUexId());
    PersonalInventoryItem entity = mapper.toEntity(request);
    entity.setOwnerUserId(ownerUserId);
    entity.setLocationNameSnapshot(snapshot);
    PersonalInventoryItem saved = repository.save(entity);
    log.info(
        "Created personal inventory item id={} for ownerUserId={}", saved.getId(), ownerUserId);
    // Covers both createOwn and the admin createForUser (which delegates here): the actor (self vs
    // admin) is resolved by AuditService; target_user_id is the owner. Do not also record in
    // createForUser or the event would be doubled.
    auditService.record(
        AuditEventType.PERSONAL_INVENTORY_CREATED,
        saved.getId(),
        personalLabel(saved),
        ownerUserId,
        AuditDetails.of("qty", saved.getQuantity()).with("loc", saved.getLocationNameSnapshot()));
    return mapper.toResponse(saved);
  }

  /**
   * Updates an owner-scoped item with explicit optimistic-lock check. Re-resolves the location
   * snapshot only when the location reference actually changed.
   *
   * @param ownerUserId {@code app_user.id} of the caller
   * @param id item primary key
   * @param request update payload (carries the expected version)
   * @return the persisted DTO
   * @throws EntityNotFoundException when the item is missing or owned by someone else
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
   * @throws EntityNotFoundException when the item is missing or owned by someone else
   */
  @Transactional
  public void deleteOwn(@NotNull UUID ownerUserId, @NotNull UUID id) {
    PersonalInventoryItem entity = loadOwn(ownerUserId, id);
    String label = personalLabel(entity);
    repository.delete(entity);
    log.info("Deleted personal inventory item id={} for ownerUserId={}", id, ownerUserId);
    auditService.record(
        AuditEventType.PERSONAL_INVENTORY_DELETED, id, label, ownerUserId, "scope=own");
  }

  // ---------------------------------------------------------------------------------
  // Admin-scoped API – callers MUST be guarded by @PreAuthorize("hasRole('ADMIN')")
  // on the controller layer. The service does NOT re-check the role; it trusts its
  // controller binding to keep this method out of reach of regular users.
  // ---------------------------------------------------------------------------------

  /**
   * Admin-scoped list. Identical implementation to {@link #listOwn} — the {@code targetSub}
   * supplies the filter — but exposed under a separate name so the controller boundary is
   * unambiguous: admins call this, regular users never can.
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
   * Admin-scoped update. Unlike {@link #updateOwn} this lookups the row by id alone — admins are
   * trusted to know which item they're editing. Optimistic-lock check still applies.
   *
   * @param id item primary key
   * @param request update payload (carries the expected version)
   * @return the persisted DTO
   * @throws EntityNotFoundException when the item id is unknown
   * @throws ObjectOptimisticLockingFailureException when the supplied version is stale
   */
  @Transactional
  public PersonalInventoryItemResponse updateForUser(
      @NotNull UUID id, @NotNull PersonalInventoryItemUpdateRequest request) {
    PersonalInventoryItem entity =
        repository
            .findById(id)
            .orElseThrow(
                () -> new EntityNotFoundException("PersonalInventoryItem not found: " + id));
    return applyUpdate(entity, request);
  }

  /**
   * Admin-scoped delete. Resolves by id alone; logs the owner sub at INFO so the audit trail shows
   * which user's data was removed by which admin call.
   *
   * @param id item primary key
   * @throws EntityNotFoundException when the item id is unknown
   */
  @Transactional
  public void deleteForUser(@NotNull UUID id) {
    PersonalInventoryItem entity =
        repository
            .findById(id)
            .orElseThrow(
                () -> new EntityNotFoundException("PersonalInventoryItem not found: " + id));
    String label = personalLabel(entity);
    UUID ownerUserId = entity.getOwnerUserId();
    repository.delete(entity);
    log.info(
        "Admin deleted personal inventory item id={} ownerUserId={}", id, entity.getOwnerUserId());
    auditService.record(
        AuditEventType.PERSONAL_INVENTORY_DELETED, id, label, ownerUserId, "scope=admin");
  }

  // ---------------------------------------------------------------------------------
  // UEX location lookup – uses the locally synced UEX mirror (City / SpaceStation)
  // ---------------------------------------------------------------------------------

  /**
   * Combined search across the locally synced UEX cities and space stations. Returns at most {@code
   * limit} entries, alphabetically sorted by name.
   */
  public List<UexLocationDto> searchLocations(@Nullable String query, int limit) {
    String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
    final int cap = Math.max(1, Math.min(limit, 2000));

    List<UexLocationDto> hits = new java.util.ArrayList<>();
    for (City c : cityRepository.findAll()) {
      if (c.getIdCity() == null || c.getName() == null) {
        continue;
      }
      if (!needle.isEmpty() && !c.getName().toLowerCase(Locale.ROOT).contains(needle)) {
        continue;
      }
      hits.add(
          new UexLocationDto(
              c.getIdCity(),
              PersonalInventoryLocationType.CITY,
              c.getName(),
              c.getStarSystemName(),
              c.getPlanetName()));
    }
    for (SpaceStation s : spaceStationRepository.findAll()) {
      if (s.getIdSpaceStation() == null || s.getName() == null) {
        continue;
      }
      if (!needle.isEmpty() && !s.getName().toLowerCase(Locale.ROOT).contains(needle)) {
        continue;
      }
      hits.add(
          new UexLocationDto(
              s.getIdSpaceStation(),
              PersonalInventoryLocationType.SPACE_STATION,
              s.getName(),
              s.getStarSystemName(),
              null));
    }
    hits.sort(
        Comparator.comparing(
            UexLocationDto::name, Comparator.nullsLast(String::compareToIgnoreCase)));
    return hits.size() > cap ? hits.subList(0, cap) : hits;
  }

  // ---------------------------------------------------------------------------------
  // Internal helpers
  // ---------------------------------------------------------------------------------

  @NotNull
  private PersonalInventoryItem loadOwn(@NotNull UUID ownerUserId, @NotNull UUID id) {
    return repository
        .findByIdAndOwnerUserId(id, ownerUserId)
        .orElseThrow(
            () -> {
              log.warn(
                  "Access denied or not found: ownerUserId={} requested id={}", ownerUserId, id);
              return new EntityNotFoundException("PersonalInventoryItem not found: " + id);
            });
  }

  @NotNull
  private PersonalInventoryItemResponse applyUpdate(
      @NotNull PersonalInventoryItem entity, @NotNull PersonalInventoryItemUpdateRequest request) {
    // Manual optimistic-lock check; mirrored from AnnouncementService convention.
    OptimisticLock.check(
        entity.getVersion(), request.version(), PersonalInventoryItem.class, entity.getId());
    // Re-resolve the location snapshot if the location reference changed.
    boolean locationChanged =
        !Objects.equals(entity.getLocationUexId(), request.locationUexId())
            || !Objects.equals(entity.getLocationType(), request.locationType());
    String snapshot =
        locationChanged
            ? resolveLocationName(request.locationType(), request.locationUexId())
            : entity.getLocationNameSnapshot();

    mapper.updateEntity(entity, request);
    entity.setLocationNameSnapshot(snapshot);
    PersonalInventoryItem saved = repository.save(entity);
    log.info(
        "Updated personal inventory item id={} ownerUserId={}",
        saved.getId(),
        saved.getOwnerUserId());
    // Shared by updateOwn and the admin updateForUser; the actor distinguishes them. Recorded here
    // (not in the two public methods) so the event fires exactly once per update.
    auditService.record(
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
  private static String personalLabel(PersonalInventoryItem item) {
    return item.getName() + " @ " + item.getLocationNameSnapshot();
  }

  /**
   * Resolves the human-readable name of a UEX location from the local mirror. Throws {@link
   * EntityNotFoundException} (→ HTTP 404 via the global handler) if the referenced location does
   * not exist – this prevents creating dangling references.
   */
  @NotNull
  private String resolveLocationName(
      @NotNull PersonalInventoryLocationType type, @NotNull Integer uexId) {
    return switch (type) {
      case CITY ->
          cityRepository
              .findByIdCity(uexId)
              .map(City::getName)
              .orElseThrow(() -> new EntityNotFoundException("UEX city not found: id=" + uexId));
      case SPACE_STATION ->
          spaceStationRepository
              .findByIdSpaceStation(uexId)
              .map(SpaceStation::getName)
              .orElseThrow(
                  () -> new EntityNotFoundException("UEX space station not found: id=" + uexId));
    };
  }
}
