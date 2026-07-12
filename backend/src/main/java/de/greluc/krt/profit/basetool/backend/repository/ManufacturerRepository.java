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
   * Resolution-chain step 1 for the R5 Wiki item backfill: match an inbound Wiki item's nested
   * manufacturer by the Wiki manufacturer UUID stored on the local row. Used only to attach a
   * manufacturer to a freshly created {@code WIKI_ONLY} {@code game_item}; existing rows keep their
   * (sticky) UEX manufacturer. Never creates a row — an unmatched manufacturer is left {@code null}
   * for the dedicated R6 reconciliation.
   *
   * @param scwikiUuid Wiki manufacturer UUID (from the item payload's {@code manufacturer.uuid})
   * @return matching manufacturer if present
   */
  Optional<Manufacturer> findByScwikiUuid(UUID scwikiUuid);

  /**
   * Resolution-chain fallback used by the R6 manufacturer reconciliation and the P4K import: match
   * a Wiki/P4K manufacturer by its short {@code code} (e.g. {@code "AEGS"}) against the local
   * {@code abbreviation} when the case-insensitive name match missed (UEX and Wiki occasionally
   * spell the full name differently while sharing the code).
   *
   * <p>{@code abbreviation} is not UNIQUE (see {@code V158} / REQ-DATA-004). The UEX sync now
   * merges duplicate companies of one brand onto a single row (ADR-0023), but a P4K- or hand-seeded
   * row can still share a code with a UEX row, so this deliberately returns the
   * <em>oldest-created</em> match via {@code findFirst … OrderBy createdAt asc} instead of a bare
   * {@code findBy …} that would throw {@code IncorrectResultSizeDataAccessException} the moment two
   * rows share the code.
   *
   * @param abbreviation manufacturer short code / abbreviation
   * @return the oldest matching manufacturer if any
   */
  Optional<Manufacturer> findFirstByAbbreviationIgnoreCaseOrderByCreatedAtAsc(String abbreviation);

  /**
   * Soft-deletes SC Wiki ownership of every <em>Wiki-linked</em> manufacturer ({@code
   * scwiki_synced_at IS NOT NULL}) whose {@code scwiki_uuid} is NOT in {@code seenScwikiUuids} and
   * that is not already marked. Drives the R6 orphan sweep (SC_WIKI_SYNC_PLAN.md §8.7); the caller
   * gates it on a non-empty seen set so an empty / failed Wiki fetch never wipes the reconciliation
   * state. The UEX-canonical columns are untouched — only the soft-delete marker is set.
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
   * Counts the live SC Wiki-reconciled manufacturers: every row the Wiki reconciliation has written
   * ({@code scwiki_synced_at IS NOT NULL}) and not tombstoned ({@code scwiki_deleted_at IS NULL}) —
   * the same {@code scwiki_synced_at} gate {@link #markScwikiDeletedExcept} uses. The R6
   * manufacturer reconciliation reports this as the representative non-zero count when the Wiki
   * manufacturer catalogue comes back {@code 304 Not Modified}, so a fully-cached healthy run is
   * not read as a zero-item outage by {@code SyncZeroItems} (#1182).
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
}
