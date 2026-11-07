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

package de.greluc.krt.profit.basetool.backend.catalogue.api;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for Location.
 *
 * <p>Every list, picker and exchange read skips reference-only rows ({@link
 * Location#getReferenceOnly()}, REQ-P4K-039); only a lookup by id or by city / space station
 * reaches them.
 */
@Repository
public interface LocationRepository extends LookupTableRepository<Location, UUID> {

  /**
   * A native SQL scalar subquery for the P4K starmap GUID of the {@code location} row aliased
   * {@code l}: its own starmap object's, else its city's, else its space station's, else {@code
   * NULL}; an object the sweep stamped or an admin hid carries none (REQ-P4K-037, REQ-XCH-018).
   */
  String EXCHANGE_GUID =
      """
      (SELECT COALESCE(lso.p4k_uuid, cso.p4k_uuid, sso.p4k_uuid)
       FROM location gl
       LEFT JOIN starmap_object lso ON lso.id = gl.starmap_object_id
           AND lso.p4k_deleted_at IS NULL AND lso.p4k_hidden_at IS NULL
       LEFT JOIN city gc ON gc.id = gl.city_id
       LEFT JOIN starmap_object cso ON cso.id = gc.starmap_object_id
           AND cso.p4k_deleted_at IS NULL AND cso.p4k_hidden_at IS NULL
       LEFT JOIN space_station gs ON gs.id = gl.space_station_id
       LEFT JOIN starmap_object sso ON sso.id = gs.starmap_object_id
           AND sso.p4k_deleted_at IS NULL AND sso.p4k_hidden_at IS NULL
       WHERE gl.id = l.id)
      """;

  /**
   * Returns slim {@code LocationReferenceDto}s (id + name) for every non-hidden location, ordered
   * by name, unbounded. Payload-bounded pickers use {@link #searchReference(String, Pageable)}
   * instead.
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.catalogue.api.LocationReferenceDto(l.id,
      l.name) FROM Location l WHERE l.hidden = false AND l.referenceOnly = false ORDER BY l.name
      """)
  List<LocationReferenceDto> findAllReference();

  /**
   * Lists the non-hidden locations with the UEX ids of their city or space station and their
   * starmap GUID, ordered by name, for the exchange catalogue (REQ-XCH-018).
   *
   * @return one row per non-hidden location
   */
  @Query(
      value =
          "SELECT l.name AS name, c.id_city AS uexCityId,"
              + " s.id_space_station AS uexSpaceStationId, "
              + EXCHANGE_GUID
              + " AS guid FROM location l LEFT JOIN city c ON c.id = l.city_id"
              + " LEFT JOIN space_station s ON s.id = l.space_station_id"
              + " WHERE l.hidden = false AND l.reference_only = false ORDER BY l.name",
      nativeQuery = true)
  List<ExchangeLocationRow> findExchangeLocations();

  /**
   * Finds the non-hidden storage place whose starmap GUID is {@code guid}; a place linked to the
   * starmap object itself wins over one linked through its city or space station, then the name
   * decides (REQ-P4K-037, REQ-XCH-016).
   *
   * @param guid the GUID of the P4K starmap object
   * @return the location, or empty when no non-hidden storage place carries the GUID
   */
  @Query(
      value =
          "SELECT l.* FROM location l WHERE l.hidden = false AND l.reference_only = false AND "
              + EXCHANGE_GUID
              + " = :guid ORDER BY (l.starmap_object_id IS NULL), l.name, l.id LIMIT 1",
      nativeQuery = true)
  Optional<Location> findExchangeByGuid(@Param("guid") UUID guid);

  /**
   * Reads the starmap GUIDs of the given locations; a location without one has no row
   * (REQ-XCH-018).
   *
   * @param ids the location ids; never empty
   * @return one row per given location that carries a GUID
   */
  @Query(
      value =
          "SELECT gx.location_id AS locationId, gx.guid AS guid FROM (SELECT l.id AS location_id, "
              + EXCHANGE_GUID
              + " AS guid FROM location l WHERE l.id IN (:ids)) gx WHERE gx.guid IS NOT NULL",
      nativeQuery = true)
  List<ExchangeLocationGuid> findExchangeGuids(@Param("ids") Collection<UUID> ids);

  /**
   * Paged live search for the location pickers (REQ-FE-016): non-hidden locations whose name
   * contains the LIKE-escaped fragment, case-insensitively.
   *
   * @param q the LIKE-escaped name fragment, or {@code null} for no filter
   * @param pageable page request (sorted by the whitelisted picker sort, typically name ascending)
   * @return one page of matching non-hidden locations as reference DTOs
   */
  @Query(
      value =
          """
          SELECT new de.greluc.krt.profit.basetool.backend.catalogue.api.LocationReferenceDto(l.id,
          l.name) FROM Location l WHERE l.hidden = false AND l.referenceOnly = false
          AND (cast(:q as string) IS NULL
          OR LOWER(l.name) LIKE LOWER(CONCAT('%', cast(:q as string), '%')) ESCAPE '\\')
          """,
      countQuery =
          """
          SELECT COUNT(l) FROM Location l WHERE l.hidden = false AND l.referenceOnly = false
          AND (cast(:q as string) IS NULL
          OR LOWER(l.name) LIKE LOWER(CONCAT('%', cast(:q as string), '%')) ESCAPE '\\')
          """)
  Page<LocationReferenceDto> searchReference(@Param("q") @Nullable String q, Pageable pageable);

  /** Derived Spring-Data query - returns entities matching {@code Name}. */
  Optional<Location> findByName(String name);

  /** Derived Spring-Data query - returns entities matching {@code CityId}. */
  Optional<Location> findByCityId(UUID cityId);

  /**
   * Whether a storage place sits on a starmap object (REQ-P4K-036).
   *
   * @param starmapObjectId the starmap object
   * @return {@code true} when a location names it
   */
  boolean existsByStarmapObjectId(UUID starmapObjectId);

  /**
   * Finds the storage places on a set of starmap objects.
   *
   * @param starmapObjectIds the starmap objects
   * @return the locations on them
   */
  List<Location> findByStarmapObjectIdIn(Collection<UUID> starmapObjectIds);

  /** Derived Spring-Data query - returns entities matching {@code SpaceStationId}. */
  Optional<Location> findBySpaceStationId(UUID spaceStationId);

  /**
   * Pages every storage place, hidden ones included, for the admin list.
   *
   * @param pageable page request
   * @return paged locations without the reference-only rows
   */
  Page<Location> findByReferenceOnlyFalse(Pageable pageable);

  /**
   * Pages the non-hidden storage places.
   *
   * @param pageable page request
   * @return paged non-hidden locations without the reference-only rows
   */
  Page<Location> findByHiddenFalseAndReferenceOnlyFalse(Pageable pageable);

  /**
   * Pages every storage place, hidden ones included, whose name contains {@code name}
   * case-insensitively; LIKE wildcards in {@code name} match literally.
   *
   * @param name the search fragment
   * @param pageable page request
   * @return paged matching locations without the reference-only rows
   */
  Page<Location> findByReferenceOnlyFalseAndNameContainingIgnoreCase(
      String name, Pageable pageable);

  /**
   * Pages the non-hidden storage places whose name contains {@code name} case-insensitively; LIKE
   * wildcards in {@code name} match literally.
   *
   * @param name the search fragment
   * @param pageable page request
   * @return paged matching non-hidden locations without the reference-only rows
   */
  Page<Location> findByHiddenFalseAndReferenceOnlyFalseAndNameContainingIgnoreCase(
      String name, Pageable pageable);

  /**
   * Returns the curated, non-hidden home locations ordered by name descending (Z-&gt;A). Backs the
   * hangar bulk "set home location" picker via {@code GET /api/v1/locations/home-locations}; the
   * descending order is part of the contract (the picker preserves it).
   *
   * @return curated home locations, name descending
   */
  List<Location> findByHomeLocationTrueAndHiddenFalseAndReferenceOnlyFalseOrderByNameDesc();

  /**
   * Returns every non-hidden location whose city or space station hosts a live refinery terminal,
   * keyed on the derived {@code hasRefineryTerminal} flag (REQ-REFINERY-020). Source for the
   * refinery-order location picker and the screenshot import's location match.
   *
   * @return non-hidden locations hosting a live refinery terminal, never {@code null}
   */
  @Query(
      """
      SELECT l FROM Location l LEFT JOIN l.city c LEFT JOIN l.spaceStation s
      WHERE l.hidden = false AND l.referenceOnly = false
      AND (c.hasRefineryTerminal = true OR s.hasRefineryTerminal = true)
      """)
  List<Location> findLocationsWithRefinery();

  /**
   * Lists the non-hidden locations linked to a UEX city, by name.
   *
   * @param idCity the UEX city id
   * @return the locations
   */
  @Query(
      """
      SELECT l FROM Location l JOIN l.city c WHERE c.idCity = :idCity AND l.hidden = false
      AND l.referenceOnly = false ORDER BY l.name ASC
      """)
  List<Location> findExchangeByCity(@Param("idCity") Integer idCity);

  /**
   * Lists the non-hidden locations linked to a UEX space station, by name.
   *
   * @param idSpaceStation the UEX space station id
   * @return the locations
   */
  @Query(
      """
      SELECT l FROM Location l JOIN l.spaceStation s WHERE s.idSpaceStation = :idSpaceStation
      AND l.hidden = false AND l.referenceOnly = false ORDER BY l.name ASC
      """)
  List<Location> findExchangeBySpaceStation(@Param("idSpaceStation") Integer idSpaceStation);

  /**
   * Finds the non-hidden storage place with an exact name.
   *
   * @param name the name
   * @return the location, or empty
   */
  Optional<Location> findFirstByNameAndHiddenFalseAndReferenceOnlyFalseOrderByIdAsc(String name);

  /** One non-hidden location as the exchange catalogue reads it from the database. */
  interface ExchangeLocationRow {

    /**
     * The location's curated name.
     *
     * @return the name
     */
    String getName();

    /**
     * The UEX id of the linked city.
     *
     * @return the id, or {@code null}
     */
    @Nullable
    Integer getUexCityId();

    /**
     * The UEX id of the linked space station.
     *
     * @return the id, or {@code null}
     */
    @Nullable
    Integer getUexSpaceStationId();

    /**
     * The GUID of the P4K starmap object the location stands for.
     *
     * @return the GUID, or {@code null} when the location links none
     */
    @Nullable
    UUID getGuid();
  }

  /** The starmap GUID of one location, as {@link #findExchangeGuids(Collection)} reads it. */
  interface ExchangeLocationGuid {

    /**
     * The location.
     *
     * @return the location id
     */
    UUID getLocationId();

    /**
     * The GUID of the P4K starmap object the location stands for.
     *
     * @return the GUID
     */
    UUID getGuid();
  }
}
