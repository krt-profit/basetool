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

import de.greluc.krt.profit.basetool.backend.model.GameItem;
import de.greluc.krt.profit.basetool.backend.model.GameItemSourceSystem;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for {@link GameItem}, including the sync lookups and soft-delete sweeps.
 */
@Repository
public interface GameItemRepository extends JpaRepository<GameItem, UUID> {

  /**
   * Finds a game item by its unique in-game asset UUID, the sync's primary match key.
   *
   * @param externalUuid in-game asset UUID
   * @return matching {@link GameItem} if present
   */
  Optional<GameItem> findByExternalUuid(UUID externalUuid);

  /**
   * Finds a game item by UEX's integer item id, the match key for UEX rows without a UUID.
   *
   * @param uexItemId UEX's integer item id
   * @return matching {@link GameItem} if present
   */
  Optional<GameItem> findByUexItemId(Integer uexItemId);

  /**
   * Counts the live UEX-sourced game items; reported as the sync's item count when UEX answers
   * {@code 304 Not Modified}.
   *
   * @return the number of non-soft-deleted UEX-sourced {@code game_item} rows
   */
  @Query("SELECT COUNT(g) FROM GameItem g WHERE g.uexItemId IS NOT NULL AND g.uexDeletedAt IS NULL")
  long countLiveUexItems();

  /**
   * Finds game items by {@code class_name}, ignoring case; the column is not unique, so callers
   * treat more than one match as ambiguous.
   *
   * @param className the RSI engine class name to match ignoring case
   * @return every item whose {@code class_name} equals {@code className} ignoring case (possibly
   *     empty)
   */
  List<GameItem> findByClassNameIgnoreCase(String className);

  /**
   * Finds game items by {@code name}, ignoring case; the column is not unique, so callers treat
   * more than one match as ambiguous.
   *
   * @param name the display name to match ignoring case
   * @return every item whose {@code name} equals {@code name} ignoring case (possibly empty)
   */
  List<GameItem> findByNameIgnoreCase(String name);

  /**
   * Returns every distinct non-null {@code external_uuid}, the item set the Wiki item sync fetches
   * details for.
   *
   * @return all distinct non-null external UUIDs
   */
  @Query("SELECT DISTINCT g.externalUuid FROM GameItem g WHERE g.externalUuid IS NOT NULL")
  List<UUID> findAllExternalUuids();

  /**
   * Loads every row without an {@code external_uuid} owned by the given source system, for the Wiki
   * backfill's name/slug reconciliation.
   *
   * @param sourceSystems the owning source system to filter on
   * @return the uuid-less rows owned solely by {@code sourceSystems}
   */
  List<GameItem> findByExternalUuidIsNullAndSourceSystems(GameItemSourceSystem sourceSystems);

  /**
   * Soft-deletes UEX ownership of every unmarked row whose {@code uex_item_id} is not in {@code
   * seenIds}. Callers must pass a non-empty seen set.
   *
   * @param seenIds UEX item ids successfully processed in the current run
   * @param now timestamp to stamp on the soft-deleted rows
   * @return number of rows marked deleted
   */
  @Modifying
  @Query(
      """
      UPDATE GameItem g SET g.uexDeletedAt = :now
      WHERE g.uexItemId IS NOT NULL
      AND g.uexItemId NOT IN :seenIds
      AND g.uexDeletedAt IS NULL
      """)
  int markUexDeletedExcept(
      @Param("seenIds") Collection<Integer> seenIds, @Param("now") Instant now);

  /**
   * Soft-deletes SC Wiki ownership of every unmarked Wiki-written row ({@code scwiki_synced_at IS
   * NOT NULL}) whose {@code external_uuid} is not in {@code seenExternalUuids}.
   *
   * <p>Callers must pass a non-empty seen set and only after every kind endpoint fetched
   * successfully.
   *
   * @param seenExternalUuids the external UUIDs the Wiki item backfill touched this run
   * @param now timestamp to stamp on the soft-deleted rows
   * @return number of rows marked deleted
   */
  @Modifying
  @Query(
      """
      UPDATE GameItem g SET g.scwikiDeletedAt = :now
      WHERE g.scwikiSyncedAt IS NOT NULL
      AND g.externalUuid NOT IN :seenExternalUuids
      AND g.scwikiDeletedAt IS NULL
      """)
  int markScwikiDeletedExcept(
      @Param("seenExternalUuids") Collection<UUID> seenExternalUuids, @Param("now") Instant now);

  /**
   * Counts the live Wiki-written game items; reported as the backfill's item count when every kind
   * endpoint answers {@code 304 Not Modified}.
   *
   * @return the number of non-tombstoned game items the SC Wiki sync has written
   */
  @Query(
      """
      SELECT COUNT(g) FROM GameItem g
      WHERE g.scwikiSyncedAt IS NOT NULL
      AND g.scwikiDeletedAt IS NULL
      """)
  long countLiveScwikiItems();

  /**
   * The UEX item id and local id of every game item. One query for the whole table; the UEX syncs
   * resolve every row of a run against the resulting map instead of looking the parent up per row
   * (BE-PERF-09).
   *
   * @return one (UEX id, local id) row per GameItem that carries a UEX id
   */
  @Query("SELECT e.uexItemId AS uexId, e.id AS id FROM GameItem e WHERE e.uexItemId IS NOT NULL")
  List<UexKeyRef> findUexItemRefs();
}
