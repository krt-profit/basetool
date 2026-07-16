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

package de.greluc.krt.profit.basetool.backend.repository;

import de.greluc.krt.profit.basetool.backend.model.Location;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Spring Data repository for Location. */
@Repository
public interface LocationRepository extends LookupTableRepository<Location, UUID> {

  /**
   * Returns slim {@code LocationReferenceDto}s (id + name) for every non-hidden location, ordered
   * by name. Used to populate location pickers without pulling the full Location aggregate. The
   * list is deliberately complete — never silently bounded — because the catalogue is curated
   * (admin-maintained plus UEX universe sync); pickers that must stay payload-bounded use {@link
   * #searchReference(String, Pageable)} instead.
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.model.dto.LocationReferenceDto(l.id,
      l.name) FROM Location l WHERE l.hidden = false ORDER BY l.name
      """)
  List<de.greluc.krt.profit.basetool.backend.model.dto.LocationReferenceDto> findAllReference();

  /**
   * Live-search projection for the location pickers (REQ-FE-016): non-hidden locations whose name
   * contains the (already LIKE-escaped) fragment, case-insensitively; a {@code null} fragment
   * matches everything. Paged so the picker's server-side search stays payload-bounded while every
   * location remains reachable by typing a narrower term — the deliberate alternative to a silent
   * cap on the complete {@link #findAllReference()} list.
   *
   * @param q the LIKE-escaped name fragment, or {@code null} for no filter
   * @param pageable page request (sorted by the whitelisted picker sort, typically name ascending)
   * @return one page of matching non-hidden locations as reference DTOs
   */
  @Query(
      value =
          """
          SELECT new de.greluc.krt.profit.basetool.backend.model.dto.LocationReferenceDto(l.id,
          l.name) FROM Location l WHERE l.hidden = false AND (cast(:q as string) IS NULL
          OR LOWER(l.name) LIKE LOWER(CONCAT('%', cast(:q as string), '%')))
          """,
      countQuery =
          """
          SELECT COUNT(l) FROM Location l WHERE l.hidden = false AND (cast(:q as string) IS NULL
          OR LOWER(l.name) LIKE LOWER(CONCAT('%', cast(:q as string), '%')))
          """)
  Page<de.greluc.krt.profit.basetool.backend.model.dto.LocationReferenceDto> searchReference(
      @Param("q") String q, Pageable pageable);

  /** Derived Spring-Data query - returns entities matching {@code Name}. */
  Optional<Location> findByName(String name);

  /** Derived Spring-Data query - returns entities matching {@code CityId}. */
  Optional<Location> findByCityId(UUID cityId);

  /** Derived Spring-Data query - returns entities matching {@code SpaceStationId}. */
  Optional<Location> findBySpaceStationId(UUID spaceStationId);

  /** Derived Spring-Data query - returns entities matching {@code HiddenFalse}. */
  Page<Location> findByHiddenFalse(Pageable pageable);

  /**
   * Returns the curated, non-hidden home locations ordered by name descending (Z-&gt;A). Backs the
   * hangar bulk "set home location" picker via {@code GET /api/v1/locations/home-locations}; the
   * descending order is part of the contract (the picker preserves it).
   *
   * @return curated home locations, name descending
   */
  List<Location> findByHomeLocationTrueAndHiddenFalseOrderByNameDesc();

  /**
   * Returns every location attached to a city or space-station that has a refinery; used as the
   * picker source when the user creates a refinery order.
   */
  @Query(
      """
      SELECT l FROM Location l LEFT JOIN l.city c LEFT JOIN l.spaceStation s WHERE c.hasRefinery =
      true OR s.hasRefinery = true
      """)
  List<Location> findLocationsWithRefinery();
}
