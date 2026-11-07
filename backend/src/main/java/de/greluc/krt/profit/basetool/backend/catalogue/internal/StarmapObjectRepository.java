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

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Reads and stores the P4K starmap objects (REQ-P4K-034). */
public interface StarmapObjectRepository extends JpaRepository<StarmapObject, UUID> {

  /**
   * Finds an object by its P4K GUID.
   *
   * @param p4kUuid the GUID
   * @return the object, if imported
   */
  Optional<StarmapObject> findByP4kUuid(UUID p4kUuid);

  /**
   * Finds the objects of a set of ids.
   *
   * @param ids the ids
   * @return the objects found
   */
  List<StarmapObject> findByIdIn(Collection<UUID> ids);

  /**
   * Reads the P4K names of the starmap objects a set of locations is linked to: the place's own
   * node, else its city's object, else its space station's (REQ-P4K-037).
   *
   * @param ids the locations
   * @return {@code [locationId, name, nameDe]} tuples for the linked locations
   */
  @Query(
      value =
          """
          SELECT l.id, so.name, so.name_de FROM location l
          LEFT JOIN city c ON c.id = l.city_id
          LEFT JOIN space_station s ON s.id = l.space_station_id
          JOIN starmap_object so
            ON so.id = COALESCE(l.starmap_object_id, c.starmap_object_id, s.starmap_object_id)
          WHERE l.id IN (:ids)
          """,
      nativeQuery = true)
  List<Object[]> findP4kNamesOfLocations(@Param("ids") Collection<UUID> ids);

  /**
   * Pages the objects for the admin list, filtered by star system folder, type and a fragment of
   * the class name or either name, each filter skipped when {@code null}.
   *
   * @param starSystem the star system folder, lower-case, or {@code null}
   * @param type the type, lower-case, or {@code null}
   * @param pattern a lower-case {@code LIKE} pattern with escaped wildcards, or {@code null}
   * @param pageable the page and its sort
   * @return the page of objects
   */
  @Query(
      value =
          """
          SELECT o FROM StarmapObject o
          WHERE (cast(:starSystem as string) IS NULL
                 OR LOWER(o.starSystem) = cast(:starSystem as string))
            AND (cast(:type as string) IS NULL OR LOWER(o.type) = cast(:type as string))
            AND (cast(:pattern as string) IS NULL
                 OR LOWER(o.className) LIKE cast(:pattern as string) ESCAPE '\\'
                 OR LOWER(o.name) LIKE cast(:pattern as string) ESCAPE '\\'
                 OR LOWER(o.nameDe) LIKE cast(:pattern as string) ESCAPE '\\')
          """,
      countQuery =
          """
          SELECT COUNT(o) FROM StarmapObject o
          WHERE (cast(:starSystem as string) IS NULL
                 OR LOWER(o.starSystem) = cast(:starSystem as string))
            AND (cast(:type as string) IS NULL OR LOWER(o.type) = cast(:type as string))
            AND (cast(:pattern as string) IS NULL
                 OR LOWER(o.className) LIKE cast(:pattern as string) ESCAPE '\\'
                 OR LOWER(o.name) LIKE cast(:pattern as string) ESCAPE '\\'
                 OR LOWER(o.nameDe) LIKE cast(:pattern as string) ESCAPE '\\')
          """)
  Page<StarmapObject> search(
      @Param("starSystem") @Nullable String starSystem,
      @Param("type") @Nullable String type,
      @Param("pattern") @Nullable String pattern,
      Pageable pageable);
}
