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
import de.greluc.krt.profit.basetool.backend.model.dto.LocationReferenceDto;
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
   * by name, unbounded. Payload-bounded pickers use {@link #searchReference(String, Pageable)}
   * instead.
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.model.dto.LocationReferenceDto(l.id,
      l.name) FROM Location l WHERE l.hidden = false ORDER BY l.name
      """)
  List<LocationReferenceDto> findAllReference();

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
          SELECT new de.greluc.krt.profit.basetool.backend.model.dto.LocationReferenceDto(l.id,
          l.name) FROM Location l WHERE l.hidden = false AND (cast(:q as string) IS NULL
          OR LOWER(l.name) LIKE LOWER(CONCAT('%', cast(:q as string), '%')))
          """,
      countQuery =
          """
          SELECT COUNT(l) FROM Location l WHERE l.hidden = false AND (cast(:q as string) IS NULL
          OR LOWER(l.name) LIKE LOWER(CONCAT('%', cast(:q as string), '%')))
          """)
  Page<LocationReferenceDto> searchReference(@Param("q") String q, Pageable pageable);

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
   * Returns every non-hidden location whose city or space station hosts a live refinery terminal,
   * keyed on the derived {@code hasRefineryTerminal} flag (REQ-REFINERY-020). Source for the
   * refinery-order location picker and the screenshot import's location match.
   *
   * @return non-hidden locations hosting a live refinery terminal, never {@code null}
   */
  @Query(
      """
      SELECT l FROM Location l LEFT JOIN l.city c LEFT JOIN l.spaceStation s
      WHERE l.hidden = false
      AND (c.hasRefineryTerminal = true OR s.hasRefineryTerminal = true)
      """)
  List<Location> findLocationsWithRefinery();
}
