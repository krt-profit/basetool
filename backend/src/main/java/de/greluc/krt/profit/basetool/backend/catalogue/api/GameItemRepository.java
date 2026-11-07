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

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for {@link GameItem}, including the sync lookups and soft-delete sweeps.
 */
@Repository
public interface GameItemRepository
    extends JpaRepository<GameItem, UUID>, GameItemIdentityReferences {

  /**
   * Loads the surviving item for a live or retained local id.
   *
   * @param id caller-supplied local item id
   * @return the surviving item when the id is known
   */
  @Override
  @NotNull
  Optional<GameItem> findById(@NotNull UUID id);

  /**
   * Loads distinct surviving items for a batch of live or retained local ids.
   *
   * @param ids caller-supplied local item ids
   * @return the distinct surviving items
   */
  @Override
  @NotNull
  List<GameItem> findAllById(@NotNull Iterable<UUID> ids);

  /**
   * Returns the surviving item reference for a live or retained local id.
   *
   * @param id caller-supplied local item id
   * @return the alias-resolved JPA reference
   */
  @Override
  @NotNull
  GameItem getReferenceById(@NotNull UUID id);

  /**
   * Finds a game item by its unique in-game asset UUID, the sync's primary match key.
   *
   * @param externalUuid in-game asset UUID
   * @return matching {@link GameItem} if present
   */
  Optional<GameItem> findByExternalUuid(UUID externalUuid);

  /**
   * Finds the GameItem row the P4K GUID identifies; {@code p4k_uuid} is unique per kind
   * (REQ-P4K-007).
   *
   * @param p4kUuid the P4K GUID
   * @return the row, if one holds the GUID
   */
  Optional<GameItem> findByP4kUuid(UUID p4kUuid);

  /**
   * Finds a game item by UEX's integer item id, the match key for UEX rows without a UUID.
   *
   * @param uexItemId UEX's integer item id
   * @return matching {@link GameItem} if present
   */
  Optional<GameItem> findByUexItemId(Integer uexItemId);

  /**
   * Locks actual catalogue rows in id order before references or source identities move.
   *
   * @param ids non-empty set of actual row ids
   * @return rows protected against concurrent foreign-key inserts and catalogue edits
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT g FROM GameItem g WHERE g.id IN :ids ORDER BY g.id")
  List<GameItem> lockReconciliationRows(@Param("ids") Collection<UUID> ids);

  /**
   * Lists all UEX-owned identities for both-side name and slug ambiguity checks.
   *
   * @return every item carrying a UEX source id
   */
  List<GameItem> findByUexItemIdIsNotNull();

  /**
   * Lists every stored {@code external_uuid} with the row's UEX item id, for the item sync's
   * detection of UEX ids that moved to another item.
   *
   * @return one ref per {@code game_item} row with an {@code external_uuid}
   */
  @Query(
      "SELECT g.uexItemId AS uexItemId, g.externalUuid AS externalUuid FROM GameItem g"
          + " WHERE g.externalUuid IS NOT NULL")
  List<ExternalUuidRef> findExternalUuidRefs();

  /** A {@code game_item} row's {@code external_uuid} and, when it has one, its UEX item id. */
  interface ExternalUuidRef {

    /**
     * The row's UEX item id.
     *
     * @return the UEX item id, or {@code null} for a row not bound to UEX
     */
    @Nullable
    Integer getUexItemId();

    /**
     * The row's cross-source UUID.
     *
     * @return the external UUID; never {@code null}, the query filters on it
     */
    @NotNull
    UUID getExternalUuid();
  }

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
   * Finds every live Wiki identity carrying an exact source name, including UEX-claimed variants.
   *
   * @param name the UEX display name to compare ignoring case and surrounding whitespace
   * @return all Wiki-side candidates, so callers can reject ambiguity before adoption
   */
  @Query(
      "SELECT g FROM GameItem g WHERE g.scwikiSyncedAt IS NOT NULL"
          + " AND g.scwikiDeletedAt IS NULL"
          + " AND LOWER(TRIM(COALESCE(g.scwikiName, g.name))) = LOWER(TRIM(:name))")
  List<GameItem> findWikiNameCandidates(@Param("name") String name);

  /**
   * Returns every distinct non-null {@code external_uuid}, the item set the Wiki item sync fetches
   * details for.
   *
   * @return all distinct non-null external UUIDs
   */
  @Query("SELECT DISTINCT g.externalUuid FROM GameItem g WHERE g.externalUuid IS NOT NULL")
  List<UUID> findAllExternalUuids();

  /**
   * Lists known Wiki identities whose omission from the player-relevant pool needs full-scope
   * proof.
   *
   * @return external UUIDs of every Wiki-written item, including tombstones
   */
  @Query(
      "SELECT g.externalUuid FROM GameItem g WHERE g.scwikiSyncedAt IS NOT NULL"
          + " AND g.externalUuid IS NOT NULL")
  List<UUID> findKnownWikiExternalUuids();

  /**
   * Restores known Wiki rows whose existence was verified without rewriting their metadata.
   *
   * @param uuids bounded non-empty set of positively verified game UUIDs
   * @param now time of the presence correction
   * @return the number of tombstones cleared
   */
  @Modifying(flushAutomatically = true)
  @Query(
      "UPDATE GameItem g SET g.scwikiDeletedAt = NULL, g.version = COALESCE(g.version, 0) + 1,"
          + " g.updatedAt = :now WHERE g.scwikiSyncedAt IS NOT NULL AND g.scwikiDeletedAt IS NOT"
          + " NULL AND g.externalUuid IN :uuids")
  int restoreKnownWikiItems(@Param("uuids") Collection<UUID> uuids, @Param("now") Instant now);

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
      UPDATE GameItem g SET g.uexDeletedAt = :now,
        g.version = COALESCE(g.version, 0) + 1, g.updatedAt = :now
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
      UPDATE GameItem g SET g.scwikiDeletedAt = :now,
        g.version = COALESCE(g.version, 0) + 1, g.updatedAt = :now
      WHERE g.scwikiSyncedAt IS NOT NULL
      AND g.externalUuid NOT IN :seenExternalUuids
      AND g.scwikiDeletedAt IS NULL
      """)
  int markScwikiDeletedExcept(
      @Param("seenExternalUuids") Collection<UUID> seenExternalUuids, @Param("now") Instant now);

  /**
   * The UEX item id and local id of every game item. One query for the whole table; the UEX syncs
   * resolve every row of a run against the resulting map instead of looking the parent up per row
   * (BE-PERF-09).
   *
   * @return one (UEX id, local id) row per GameItem that carries a UEX id
   */
  @Query("SELECT e.uexItemId AS uexId, e.id AS id FROM GameItem e WHERE e.uexItemId IS NOT NULL")
  List<UexKeyRef> findUexItemRefs();

  /**
   * Projects the items matching any of the given keys, for the exchange's catalogue resolution
   * (REQ-XCH-012); every collection must be non-empty.
   *
   * @param ids item ids
   * @param classNames lower-cased class names
   * @param guids Wiki or game-file UUIDs
   * @param uexIds UEX item ids
   * @param names lower-cased display names
   * @param nameKeys lower-cased name keys
   * @return one row per matching item
   */
  @Query(
      """
      SELECT new
      de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository$ExchangeItemKeyRow(
      gi.id, gi.name, gi.className, gi.externalUuid, gi.p4kUuid, gi.uexItemId, gi.nameKey)
      FROM GameItem gi
      WHERE gi.id IN :ids OR LOWER(gi.className) IN :classNames OR gi.externalUuid IN :guids
      OR gi.p4kUuid IN :guids OR gi.uexItemId IN :uexIds OR LOWER(gi.name) IN :names
      OR LOWER(gi.nameKey) IN :nameKeys
      """)
  List<ExchangeItemKeyRow> findExchangeKeyRows(
      @Param("ids") Collection<UUID> ids,
      @Param("classNames") Collection<String> classNames,
      @Param("guids") Collection<UUID> guids,
      @Param("uexIds") Collection<Integer> uexIds,
      @Param("names") Collection<String> names,
      @Param("nameKeys") Collection<String> nameKeys);

  /**
   * Lists the distinct game versions the UEX item sync stamped on the items, for the "P4K import
   * due" signal (REQ-OBS-022).
   *
   * @return the distinct non-null {@code uex_game_version_seen} values, in no particular order
   */
  @Query(
      "SELECT DISTINCT g.uexGameVersionSeen FROM GameItem g WHERE g.uexGameVersionSeen IS NOT NULL")
  List<String> findDistinctUexGameVersions();

  /**
   * The identifiers of one game item, for resolving an item reference (REQ-XCH-012).
   *
   * @param id the item's id
   * @param name the item's display name
   * @param className the item's DataForge class name, or {@code null}
   * @param externalUuid the item's Wiki UUID, or {@code null}
   * @param p4kUuid the item's game-file UUID, or {@code null}
   * @param uexId the item's UEX id, or {@code null}
   * @param nameKey the item's {@code global.ini} name key, or {@code null}
   */
  record ExchangeItemKeyRow(
      UUID id,
      String name,
      String className,
      UUID externalUuid,
      UUID p4kUuid,
      Integer uexId,
      String nameKey) {}
}
