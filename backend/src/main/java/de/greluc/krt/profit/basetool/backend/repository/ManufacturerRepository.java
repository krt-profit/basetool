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

import de.greluc.krt.profit.basetool.backend.model.Manufacturer;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Spring Data repository for Manufacturer. */
@Repository
public interface ManufacturerRepository extends LookupTableRepository<Manufacturer, UUID> {
  /** Derived Spring-Data query - returns entities matching {@code NameIgnoreCase}. */
  Optional<Manufacturer> findByNameIgnoreCase(String name);

  /** Derived Spring-Data query - returns entities matching {@code HiddenFalse}. */
  Page<Manufacturer> findByHiddenFalse(Pageable pageable);

  /**
   * Finds a manufacturer by its SC Wiki UUID, used to attach a manufacturer to a newly created
   * {@code WIKI_ONLY} game item. Never creates a row.
   *
   * @param scwikiUuid Wiki manufacturer UUID (from the item payload's {@code manufacturer.uuid})
   * @return matching manufacturer if present
   */
  Optional<Manufacturer> findByScwikiUuid(UUID scwikiUuid);

  /**
   * Finds a manufacturer by short code against the local {@code abbreviation} (case-insensitive),
   * the fallback when the name match misses. {@code abbreviation} is not unique, so the
   * oldest-created match is returned.
   *
   * @param abbreviation manufacturer short code / abbreviation
   * @return the oldest matching manufacturer if any
   */
  Optional<Manufacturer> findFirstByAbbreviationIgnoreCaseOrderByCreatedAtAsc(String abbreviation);

  /**
   * Marks every Wiki-linked manufacturer whose {@code scwiki_uuid} is not in {@code
   * seenScwikiUuids} as SC Wiki-deleted, unless already marked. Callers must pass a non-empty set;
   * UEX-canonical columns are untouched.
   *
   * @param seenScwikiUuids the Wiki manufacturer UUIDs reconciled this run
   * @param now timestamp to stamp on the soft-deleted rows
   * @return number of rows marked deleted
   */
  @Modifying
  @Query(
      """
      UPDATE Manufacturer m SET m.scwikiDeletedAt = :now
      WHERE m.scwikiSyncedAt IS NOT NULL
      AND m.scwikiUuid NOT IN :seenScwikiUuids
      AND m.scwikiDeletedAt IS NULL
      """)
  int markScwikiDeletedExcept(
      @Param("seenScwikiUuids") Collection<UUID> seenScwikiUuids, @Param("now") Instant now);

  /**
   * Counts the manufacturers linked by the SC Wiki reconciliation and not tombstoned, reported as
   * the run's item count when the Wiki catalogue returns {@code 304 Not Modified}.
   *
   * @return the number of non-tombstoned manufacturers the SC Wiki reconciliation has linked
   */
  @Query(
      """
      SELECT COUNT(m) FROM Manufacturer m
      WHERE m.scwikiSyncedAt IS NOT NULL
      AND m.scwikiDeletedAt IS NULL
      """)
  long countLiveScwikiManufacturers();

  /**
   * The name and id of every manufacturer, in one query — the item sync's name fallback for a row
   * whose company id has no alias, resolved in memory instead of per row (BE-PERF-09).
   *
   * @return one (name, id) row per manufacturer
   */
  @Query("SELECT m.name AS name, m.id AS id FROM Manufacturer m")
  List<NameRef> findNameRefs();

  /** Projection row of {@link #findNameRefs()}. */
  interface NameRef {

    /**
     * The manufacturer's name as stored.
     *
     * @return the name
     */
    String getName();

    /**
     * The manufacturer's id.
     *
     * @return the id
     */
    UUID getId();
  }
}
