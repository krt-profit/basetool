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

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.LikePatterns;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.platform.api.CacheConfig;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The admin side of the starmap (REQ-P4K-034…038, ADR-0345): lists the objects with their
 * visibility, sets or clears an object's visibility override, lists the UEX location rows with
 * their links, and sets, clears or releases a link by hand. Every mutation records one {@code
 * CATALOGUE} audit event (REQ-P4K-046) and pokes the admin room's {@code starmap} section.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StarmapAdminService {

  private final StarmapObjectRepository objectRepository;
  private final StarmapPlaceRows placeRows;
  private final StarmapVisibility visibility;
  private final LocationRepository locationRepository;
  private final AuditRecorder auditRecorder;
  private final CatalogueAdminLiveSync adminLiveSync;

  /**
   * Pages the starmap objects, each with its visibility and storage place.
   *
   * @param starSystem the star system folder to show, or {@code null} for all
   * @param type the type to show, or {@code null} for all
   * @param query a fragment of the class name or either name, or {@code null}
   * @param pageable the page and its sort
   * @return the page of objects
   */
  @NotNull
  public Page<StarmapObjectAdminDto> objects(
      @Nullable String starSystem,
      @Nullable String type,
      @Nullable String query,
      @NotNull Pageable pageable) {
    Page<StarmapObject> page =
        objectRepository.search(
            lower(starSystem),
            lower(type),
            query == null ? null : LikePatterns.contains(query.toLowerCase(Locale.ROOT)),
            pageable);
    Set<String> liveSystems = visibility.liveSystems();
    Map<UUID, UUID> places = storagePlaces(page.getContent());
    return page.map(o -> toDto(o, liveSystems, places.get(o.getId())));
  }

  /**
   * Sets or clears an object's visibility override (REQ-P4K-038); the override survives every
   * APPLY. An unchanged override records nothing.
   *
   * @param id the object
   * @param override the override, or {@code null} to let the rule decide
   * @param version the object version the admin saw
   * @return the object after the change
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when no object has the
   *     id
   * @throws ObjectOptimisticLockingFailureException when the version is stale
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.LOCATIONS_CACHE, allEntries = true)
  @NotNull
  public StarmapObjectAdminDto overrideVisibility(
      @NotNull UUID id, @Nullable StarmapVisibilityOverride override, long version) {
    StarmapObject object = requireObject(id);
    OptimisticLock.checkRequired(object.getVersion(), version, StarmapObject.class, id);
    if (object.getVisibilityOverride() != override) {
      object.setVisibilityOverride(override);
      objectRepository.saveAndFlush(object);
      auditRecorder.record(
          AuditEventType.CATALOGUE_STARMAP_VISIBILITY_OVERRIDDEN,
          object.getId(),
          object.getClassName(),
          null,
          AuditDetails.of("visibility", override == null ? "RULE" : override.name()));
      adminLiveSync.starmapChanged();
    }
    List<StarmapObject> one = List.of(object);
    return toDto(object, visibility.liveSystems(), storagePlaces(one).get(object.getId()));
  }

  /**
   * Pages the rows of one UEX location table with their links, ordered by name.
   *
   * @param kind the table
   * @param query a fragment of the row's name, or {@code null}
   * @param unlinkedOnly whether only unlinked rows are listed
   * @param pageable the page
   * @return the page of rows
   */
  @NotNull
  public Page<StarmapPlaceLinkDto> places(
      @NotNull StarmapPlaceKind kind,
      @Nullable String query,
      boolean unlinkedOnly,
      @NotNull Pageable pageable) {
    String fragment = lower(query);
    List<StarmapPlaceRow> rows =
        placeRows.rows(kind).stream()
            .filter(r -> !unlinkedOnly || r.starmapObjectId() == null)
            .filter(r ->
                fragment == null
                    || (r.name() != null && r.name().toLowerCase(Locale.ROOT).contains(fragment)))
            .sorted(
                Comparator.comparing(
                    StarmapPlaceRow::name, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
            .toList();
    int from = (int) Math.min(pageable.getOffset(), rows.size());
    int to = Math.min(from + pageable.getPageSize(), rows.size());
    List<StarmapPlaceRow> slice = rows.subList(from, to);
    Map<UUID, StarmapObject> objects = linkedObjects(slice);
    return new PageImpl<>(
        slice.stream()
            .map(r ->
                toDto(r, r.starmapObjectId() == null ? null : objects.get(r.starmapObjectId())))
            .toList(),
        pageable,
        rows.size());
  }

  /**
   * Sets, clears or releases a UEX location row's starmap link by hand (REQ-P4K-035), when the row
   * still holds the link the admin saw.
   *
   * @param kind the table
   * @param id the row
   * @param action what the admin does
   * @param starmapObjectId the object for {@link StarmapLinkAction#SET}, else ignored
   * @param expectedStarmapObjectId the link the admin saw
   * @param expectedManual the manual flag the admin saw
   * @return the row after the change
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the row or the
   *     object does not exist
   * @throws BadRequestException when {@code SET} names no object
   * @throws ObjectOptimisticLockingFailureException when the row's link changed meanwhile
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.LOCATIONS_CACHE, allEntries = true)
  @NotNull
  public StarmapPlaceLinkDto changeLink(
      @NotNull StarmapPlaceKind kind,
      @NotNull UUID id,
      @NotNull StarmapLinkAction action,
      @Nullable UUID starmapObjectId,
      @Nullable UUID expectedStarmapObjectId,
      boolean expectedManual) {
    StarmapPlaceRow row = requireRow(kind, id);
    StarmapObject target =
        switch (action) {
          case SET -> {
            if (starmapObjectId == null) {
              throw new BadRequestException("A link names the starmap object.");
            }
            yield requireObject(starmapObjectId);
          }
          case CLEAR -> null;
          case RELEASE ->
              row.starmapObjectId() == null ? null : requireObject(row.starmapObjectId());
        };
    UUID targetId = target == null ? null : target.getId();
    boolean written =
        placeRows.manualLink(
            kind,
            id,
            targetId,
            action != StarmapLinkAction.RELEASE,
            expectedStarmapObjectId,
            expectedManual);
    if (!written) {
      throw new ObjectOptimisticLockingFailureException(kind.getTable(), id);
    }
    auditRecorder.record(
        switch (action) {
          case SET -> AuditEventType.CATALOGUE_STARMAP_LINK_SET;
          case CLEAR -> AuditEventType.CATALOGUE_STARMAP_LINK_CLEARED;
          case RELEASE -> AuditEventType.CATALOGUE_STARMAP_LINK_RELEASED;
        },
        id,
        row.name(),
        null,
        AuditDetails.of("kind", kind.name())
            .with("starmapObjectId", targetId)
            .with("previousStarmapObjectId", row.starmapObjectId()));
    adminLiveSync.starmapChanged();
    StarmapPlaceRow after = requireRow(kind, id);
    return toDto(after, target);
  }

  /**
   * Loads an object.
   *
   * @param id the object
   * @return the object
   */
  @NotNull
  private StarmapObject requireObject(@NotNull UUID id) {
    return Entities.require(objectRepository.findById(id), "Starmap object not found");
  }

  /**
   * Loads a UEX location row.
   *
   * @param kind the table
   * @param id the row
   * @return the row
   */
  @NotNull
  private StarmapPlaceRow requireRow(@NotNull StarmapPlaceKind kind, @NotNull UUID id) {
    return Entities.require(placeRows.row(kind, id), "UEX location not found");
  }

  /**
   * Reads the storage places on a set of objects.
   *
   * @param objects the objects
   * @return the storage place id by object id
   */
  @NotNull
  private Map<UUID, UUID> storagePlaces(@NotNull List<StarmapObject> objects) {
    if (objects.isEmpty()) {
      return Map.of();
    }
    return locationRepository
        .findByStarmapObjectIdIn(objects.stream().map(StarmapObject::getId).toList())
        .stream()
        .collect(Collectors.toMap(Location::getStarmapObjectId, Location::getId));
  }

  /**
   * Reads the objects a page of rows links to.
   *
   * @param rows the rows
   * @return the objects by id
   */
  @NotNull
  private Map<UUID, StarmapObject> linkedObjects(@NotNull List<StarmapPlaceRow> rows) {
    List<UUID> ids =
        rows.stream().map(StarmapPlaceRow::starmapObjectId).filter(Objects::nonNull).toList();
    return ids.isEmpty()
        ? Map.of()
        : objectRepository.findByIdIn(ids).stream()
            .collect(Collectors.toMap(StarmapObject::getId, Function.identity()));
  }

  /**
   * Maps an object.
   *
   * @param o the object
   * @param liveSystems the live star systems
   * @param storagePlaceId the storage place on it, or {@code null}
   * @return its DTO
   */
  @NotNull
  private static StarmapObjectAdminDto toDto(
      @NotNull StarmapObject o, @NotNull Set<String> liveSystems, @Nullable UUID storagePlaceId) {
    return new StarmapObjectAdminDto(
        o.getId(),
        Objects.requireNonNullElse(o.getVersion(), 0L),
        o.getP4kUuid(),
        o.getClassName(),
        o.getType(),
        o.getStarSystem(),
        o.getParentId(),
        o.getName(),
        o.getNameDe(),
        o.getHideInStarmap(),
        o.hasPosition(),
        o.getVisibilityOverride(),
        StarmapVisibility.isVisible(o, liveSystems),
        o.isP4kRetired(),
        storagePlaceId);
  }

  /**
   * Maps a UEX location row.
   *
   * @param row the row
   * @param object its linked object, or {@code null}
   * @return its DTO
   */
  @NotNull
  private static StarmapPlaceLinkDto toDto(
      @NotNull StarmapPlaceRow row, @Nullable StarmapObject object) {
    return new StarmapPlaceLinkDto(
        row.kind(),
        row.id(),
        row.name(),
        row.starSystem(),
        row.starmapObjectId(),
        object == null ? null : object.getP4kUuid(),
        object == null ? null : object.getClassName(),
        row.manual());
  }

  /**
   * Lower-cases and strips a filter.
   *
   * @param value the filter, or {@code null}
   * @return the lower-case filter, or {@code null} when blank
   */
  @Nullable
  private static String lower(@Nullable String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.strip().toLowerCase(Locale.ROOT);
  }
}
