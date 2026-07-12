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
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for {@link GameItem}. The sync services use {@link
 * #findByExternalUuid(UUID)} as the primary fast-path and {@link #findByUexItemId(Integer)} as the
 * secondary path; the soft-delete sweep at the end of a successful sync run uses {@link
 * #markUexDeletedExcept(Collection, Instant)} to flag rows UEX no longer returns.
 */
@Repository
public interface GameItemRepository extends JpaRepository<GameItem, UUID> {

  /**
   * Resolution-chain step 1 (R2): match an inbound DTO row to an existing local row via the shared
   * in-game asset UUID. {@code external_uuid} is the joint key with the {@code UNIQUE} constraint
   * enforcing the §3.6 identity invariant.
   *
   * @param externalUuid in-game asset UUID
   * @return matching {@link GameItem} if present
   */
  Optional<GameItem> findByExternalUuid(UUID externalUuid);

  /**
   * Resolution-chain step 2 (R2 UEX side): match by UEX's integer item id. Used when the UEX
   * payload carries no UUID (~30% of rows) but the row was already created on a previous run.
   *
   * @param uexItemId UEX's integer item id
   * @return matching {@link GameItem} if present
   */
  Optional<GameItem> findByUexItemId(Integer uexItemId);

  /**
   * Counts the live UEX-sourced catalogue rows — every {@code game_item} carrying a {@code
   * uex_item_id} whose UEX ownership is not soft-deleted ({@code uex_deleted_at IS NULL}). {@link
   * de.greluc.krt.profit.basetool.backend.service.UexItemSyncService#syncItems()} reports this as
   * the run's item tally when the entire UEX item catalogue came back {@code 304 Not Modified}
   * (every category served from the conditional-GET cache): such a run upserts nothing, yet the
   * catalogue is healthy and present, so reporting its size keeps {@code
   * basetool_scheduled_job_items_total} non-zero and stops a false {@code SyncZeroItems} firing on
   * an unchanged catalogue — only a genuine empty-200 outage (no {@code 304} at all) then reads as
   * zero.
   *
   * @return the number of non-soft-deleted UEX-sourced {@code game_item} rows
   */
  @Query("SELECT COUNT(g) FROM GameItem g WHERE g.uexItemId IS NOT NULL AND g.uexDeletedAt IS NULL")
  long countLiveUexItems();

  /**
   * Case-insensitive {@code class_name} lookup driving the P4K import's secondary resolution step
   * (when an inbound item carries no matching {@code external_uuid}). Returns a {@code List} on
   * purpose: {@code class_name} is not UNIQUE, so the caller links the P4K row only when exactly
   * one candidate matches and treats a multi-row result as ambiguous (no enrichment).
   *
   * @param className the RSI engine class name to match ignoring case
   * @return every item whose {@code class_name} equals {@code className} ignoring case (possibly
   *     empty)
   */
  java.util.List<GameItem> findByClassNameIgnoreCase(String className);

  /**
   * Case-insensitive {@code name} lookup driving the P4K import's tertiary resolution step (when
   * neither {@code external_uuid} nor a unique {@code class_name} matched). Returns a {@code List}
   * because {@code name} is not UNIQUE on {@code game_item}; the caller enriches only on an
   * unambiguous single match.
   *
   * @param name the display name to match ignoring case
   * @return every item whose {@code name} equals {@code name} ignoring case (possibly empty)
   */
  java.util.List<GameItem> findByNameIgnoreCase(String name);

  /**
   * Returns every non-null {@code external_uuid} in the table. Drives the R4 closure-mode Wiki item
   * sync (§8.4 Mode A): it fetches Wiki detail for exactly the items UEX already placed in {@code
   * game_item} (plus blueprint item references), never enumerating the full ~12 700-row Wiki item
   * pool.
   *
   * @return all distinct non-null external UUIDs
   */
  @Query("SELECT DISTINCT g.externalUuid FROM GameItem g WHERE g.externalUuid IS NOT NULL")
  java.util.List<java.util.UUID> findAllExternalUuids();

  /**
   * Loads every row that still lacks an {@code external_uuid} and is owned by the given source
   * system — in practice the uuid-less {@code UEX_ONLY} rows (the ~30% of UEX items UEX ships with
   * an empty UUID: Avionics, Decorations, Liveries, Armor). Drives the Weg-2 full-backfill
   * name/slug reconciliation: the Wiki item backfill indexes these by name + {@code uex_slug} and
   * merges a Wiki item into the unique match rather than inserting a duplicate {@code WIKI_ONLY}
   * row. Loaded once per backfill run (≤ a few thousand rows), so the full-entity fetch is
   * acceptable.
   *
   * @param sourceSystems the owning source system to filter on (always {@link
   *     GameItemSourceSystem#UEX_ONLY} at the only call site)
   * @return the uuid-less rows owned solely by {@code sourceSystems}
   */
  java.util.List<GameItem> findByExternalUuidIsNullAndSourceSystems(
      GameItemSourceSystem sourceSystems);

  /**
   * Soft-deletes UEX-side ownership of every row whose {@code uex_item_id} is set, NOT included in
   * {@code seenIds}, and whose {@code uex_deleted_at} is currently NULL. Mirrors the {@code
   * MaterialPriceRepository.clearStalePrices} pattern: gated by a non-empty {@code seenIds} so a
   * sync that fails on every row never wipes the entire table.
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
   * Soft-deletes SC Wiki ownership of every <em>Wiki-written</em> row whose {@code external_uuid}
   * is NOT in {@code seenExternalUuids} and that is not already marked. Drives the R5 Mode-B
   * cross-kind orphan sweep (SC_WIKI_SYNC_PLAN.md §8.4 / §8.7). The caller gates this on a
   * non-empty seen set AND on every kind endpoint having fetched successfully, so a transient Wiki
   * outage never wipes the Wiki-side merge state.
   *
   * <p>Unlike {@link de.greluc.krt.profit.basetool.backend.repository.ShipTypeRepository}'s vehicle
   * sweep, the gate is {@code scwiki_synced_at IS NOT NULL} (the Wiki actually wrote this row) and
   * NOT {@code external_uuid IS NOT NULL}: a {@code game_item} row created by the UEX item sync
   * also carries an {@code external_uuid}, so gating on its mere presence would wrongly stamp
   * {@code scwiki_deleted_at} on UEX-only items the Wiki has never described.
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
   * Counts the live SC Wiki-written game items: every row the Wiki has actually written ({@code
   * scwiki_synced_at IS NOT NULL}) and not tombstoned ({@code scwiki_deleted_at IS NULL}) — the
   * same {@code scwiki_synced_at} gate {@link #markScwikiDeletedExcept} uses. The R5 Mode-B item
   * backfill reports this as the representative non-zero count when every kind endpoint comes back
   * {@code 304 Not Modified} (nothing upserted), so a fully-cached healthy run is not read as a
   * zero-item outage by {@code SyncZeroItems} (#1182).
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
}
