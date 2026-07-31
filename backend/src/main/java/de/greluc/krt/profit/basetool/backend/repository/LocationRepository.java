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
   * Returns every non-hidden location whose city or space station hosts a live refinery terminal;
   * the picker source when the user creates a refinery order, and the candidate set the screenshot
   * import matches its location read against.
   *
   * <p>Keyed on the derived {@code hasRefineryTerminal} flag, NOT on UEX's parent-level {@code
   * hasRefinery} claim this query used to read (REQ-REFINERY-020). The upstream claim disagrees
   * with UEX's own terminal list in both directions — it misses MIC-L5, ARC-L4 and Patch City, and
   * invents four People's Service Stations. {@code
   * UexUniverseSyncService.reconcileRefineryTerminalFlags()} recomputes the derived flag from the
   * live {@code type = 'refinery'} terminals at the end of every sweep.
   *
   * <p>The {@code hidden = false} predicate is as load bearing as the flag itself: an admin who
   * hides a location expects it gone from every picker, and this query was the sole Location lookup
   * that ignored the flag. Without it a hidden refinery stayed selectable here while vanishing from
   * the storage pickers built on {@link #findAllReference()} / {@link #findByHiddenFalse(Pageable)}
   * — the user could open a refinery order at a location they could then not book the yield into.
   * The parentheses around the two terminal branches are equally load bearing: {@code AND} binds
   * tighter than {@code OR}, so an unparenthesised predicate would filter the city branch only and
   * leak every hidden station-backed refinery.
   *
   * <p>The create/update gate {@code RefineryOrderService.validateLocationHasRefinery} deliberately
   * does <strong>not</strong> mirror this predicate. It stays keyed on the refinery flag alone, so
   * it remains looser than the picker and an order created before its location was hidden can still
   * be edited and saved (REQ-REFINERY-020 forbids a gate that is *stricter* than the picker, not
   * one that is more permissive).
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
