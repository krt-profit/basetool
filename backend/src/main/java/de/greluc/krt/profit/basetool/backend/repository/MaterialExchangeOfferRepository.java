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

import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeOffer;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeOfferStatus;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Spring Data repository for {@link MaterialExchangeOffer}. */
@Repository
public interface MaterialExchangeOfferRepository
    extends JpaRepository<MaterialExchangeOffer, UUID> {

  /**
   * The Materialbörse board query: every {@code ACTIVE} offer of either kind (REQ-MARKET-012/014),
   * org-wide, optionally narrowed to the caller's own offers and by the toolbar filters.
   *
   * <p>The effective amount is the offered amount clamped to current stock (ADR-0086, ADR-0108), or
   * a free-stated item offer's stated quantity. A non-zero {@code minQuality} excludes item offers.
   * The location is never exposed (REQ-MARKET-004). The sort is embedded, so pass an unsorted page
   * request.
   *
   * @param viewerId the caller's user id; used only when {@code onlyMine} is {@code true}.
   * @param onlyMine {@code true} for "Meine Angebote", {@code false} for "Alle Angebote".
   * @param query a pre-lowercased {@code %fragment%} matched against the material/item name and the
   *     owner's username/display name, or {@code null} for no text filter.
   * @param minQuality the inclusive minimum quality; 0 disables the filter.
   * @param minAmount the inclusive minimum quantity (SCU or pieces), or {@code null} for none.
   * @param sortKey the whitelisted sort key, {@code menge} / {@code mat} / {@code neu}, else
   *     quality; must be non-null.
   * @param pageable the unsorted page request.
   * @return the matching page of active offers, never {@code null}.
   */
  @Query(
      value =
          """
          SELECT o FROM MaterialExchangeOffer o
          LEFT JOIN FETCH o.inventoryItem ii
          LEFT JOIN FETCH ii.material m
          LEFT JOIN FETCH o.owner ow
          LEFT JOIN FETCH o.owningOrgUnit
          WHERE o.status = de.greluc.krt.profit.basetool.backend.model.MaterialExchangeOfferStatus.ACTIVE
            AND (:onlyMine = false OR ow.id = :viewerId)
            AND (:query IS NULL
                 OR LOWER(m.name) LIKE :query
                 OR LOWER(o.itemName) LIKE :query
                 OR LOWER(ow.username) LIKE :query
                 OR LOWER(ow.displayName) LIKE :query)
            AND (:minQuality = 0 OR ii.quality >= :minQuality)
            AND (:minAmount IS NULL
                 OR CASE WHEN o.offeredAmount IS NOT NULL THEN LEAST(o.offeredAmount, ii.amount) WHEN ii.id IS NOT NULL THEN LEAST(o.itemQuantity, ii.amount) ELSE o.itemQuantity END >= :minAmount)
          ORDER BY
            CASE WHEN :sortKey = 'menge' THEN CASE WHEN o.offeredAmount IS NOT NULL THEN LEAST(o.offeredAmount, ii.amount) WHEN ii.id IS NOT NULL THEN LEAST(o.itemQuantity, ii.amount) ELSE o.itemQuantity END END DESC,
            CASE WHEN :sortKey = 'mat' THEN LOWER(COALESCE(m.name, o.itemName)) END ASC,
            CASE WHEN :sortKey = 'neu' THEN o.releasedAt END DESC,
            CASE WHEN :sortKey NOT IN ('menge', 'mat', 'neu') THEN COALESCE(ii.quality, -1) END DESC,
            o.releasedAt DESC,
            o.id DESC
          """,
      countQuery =
          """
          SELECT COUNT(o) FROM MaterialExchangeOffer o
          LEFT JOIN o.inventoryItem ii
          LEFT JOIN ii.material m
          LEFT JOIN o.owner ow
          WHERE o.status = de.greluc.krt.profit.basetool.backend.model.MaterialExchangeOfferStatus.ACTIVE
            AND (:onlyMine = false OR ow.id = :viewerId)
            AND (:query IS NULL
                 OR LOWER(m.name) LIKE :query
                 OR LOWER(o.itemName) LIKE :query
                 OR LOWER(ow.username) LIKE :query
                 OR LOWER(ow.displayName) LIKE :query)
            AND (:minQuality = 0 OR ii.quality >= :minQuality)
            AND (:minAmount IS NULL
                 OR CASE WHEN o.offeredAmount IS NOT NULL THEN LEAST(o.offeredAmount, ii.amount) WHEN ii.id IS NOT NULL THEN LEAST(o.itemQuantity, ii.amount) ELSE o.itemQuantity END >= :minAmount)
          """)
  Page<MaterialExchangeOffer> findBoard(
      @Param("viewerId") UUID viewerId,
      @Param("onlyMine") boolean onlyMine,
      @Param("query") String query,
      @Param("minQuality") int minQuality,
      @Param("minAmount") Double minAmount,
      @Param("sortKey") String sortKey,
      Pageable pageable);

  /**
   * Loads one offer with all board associations eager-fetched for the detail pane, regardless of
   * status (an owner can still open a just-deactivated offer via a stale link — the service maps
   * the status into the DTO).
   *
   * @param id the offer id.
   * @return the offer with its item / material / owner / org unit initialised, or empty.
   */
  @EntityGraph(
      attributePaths = {"inventoryItem", "inventoryItem.material", "owner", "owningOrgUnit"})
  Optional<MaterialExchangeOffer> findWithDetailById(UUID id);

  /**
   * Returns the single active offer for a Lager row, if any — the upsert lookup that keeps the
   * one-active-offer-per-item invariant (re-releasing re-activates this row instead of inserting a
   * duplicate) and drives the Lager "Auf Börse" status chip for a single row.
   *
   * @param inventoryItemId the source Lager row.
   * @param status the status to match (always {@link MaterialExchangeOfferStatus#ACTIVE} for the
   *     upsert lookup).
   * @return the active offer for that item, or empty.
   */
  Optional<MaterialExchangeOffer> findByInventoryItemIdAndStatus(
      UUID inventoryItemId, MaterialExchangeOfferStatus status);

  /**
   * Returns the ids of the given Lager rows that currently carry an offer in the given status —
   * used to render the "Auf Börse" / "privat" status chip across a lazily-loaded batch of leaf
   * entries in one query (no N+1).
   *
   * @param status the status to match (typically {@link MaterialExchangeOfferStatus#ACTIVE}).
   * @param inventoryItemIds the Lager rows being rendered; an empty collection yields an empty set.
   * @return the subset of {@code inventoryItemIds} that have a matching offer, never {@code null}.
   */
  @Query(
      "SELECT o.inventoryItem.id FROM MaterialExchangeOffer o "
          + "WHERE o.status = :status AND o.inventoryItem.id IN :inventoryItemIds")
  Set<UUID> findInventoryItemIdsWithStatus(
      @Param("status") MaterialExchangeOfferStatus status,
      @Param("inventoryItemIds") Collection<UUID> inventoryItemIds);

  /**
   * Checks whether the given Lager row backs any Materialbörse offer, regardless of status. The
   * stock merge (REQ-INV-026) leaves such rows out, since deleting one would cascade to its offer.
   *
   * @param inventoryItemId the Lager row to check.
   * @return {@code true} iff at least one offer references that row.
   */
  boolean existsByInventoryItemId(UUID inventoryItemId);

  /**
   * Lowers the stored {@code offeredAmount} of the {@code ACTIVE} material offer on a Lager row to
   * the row's new stock when the stock falls below it (REQ-MARKET-013); a stock increase is a
   * no-op. Leaves the offer's {@code @Version} untouched and must run in the stock-decrement
   * transaction.
   *
   * @param itemId the backing Lager row whose active offer to clamp.
   * @param stock the row's new (reduced) stock the offer must not exceed.
   * @return the number of offers clamped (0 or 1).
   */
  @Modifying
  @Query(
      """
      UPDATE MaterialExchangeOffer o SET o.offeredAmount = :stock
      WHERE o.inventoryItem.id = :itemId
        AND o.status = de.greluc.krt.profit.basetool.backend.model.MaterialExchangeOfferStatus.ACTIVE
        AND o.offeredAmount IS NOT NULL
        AND o.offeredAmount > :stock
      """)
  int clampOfferedAmountToStock(@Param("itemId") UUID itemId, @Param("stock") double stock);

  /**
   * Lowers the stored {@code itemQuantity} of the {@code ACTIVE} stock-backed item offer on a
   * game-item Lager row to the row's new whole-unit stock when the stock falls below it
   * (REQ-MARKET-013/014); the item-offer counterpart of {@link #clampOfferedAmountToStock(UUID,
   * double)}. Leaves the offer's {@code @Version} untouched and must run in the stock-decrement
   * transaction.
   *
   * @param itemId the backing game-item Lager row whose active item offer to clamp.
   * @param stock the row's new (reduced) whole-unit stock the offered quantity must not exceed.
   * @return the number of offers clamped (0 or 1).
   */
  @Modifying
  @Query(
      """
      UPDATE MaterialExchangeOffer o SET o.itemQuantity = :stock
      WHERE o.inventoryItem.id = :itemId
        AND o.kind = de.greluc.krt.profit.basetool.backend.model.MaterialExchangeOfferKind.ITEM
        AND o.status = de.greluc.krt.profit.basetool.backend.model.MaterialExchangeOfferStatus.ACTIVE
        AND o.itemQuantity IS NOT NULL
        AND o.itemQuantity > :stock
      """)
  int clampItemQuantityToStock(@Param("itemId") UUID itemId, @Param("stock") int stock);

  /**
   * Counts offers in the given status across the whole board — the "Alle Angebote" tab count and
   * the {@code basetool_material_exchange_active_count} business gauge.
   *
   * @param status the status to count.
   * @return the number of offers in that status.
   */
  long countByStatus(MaterialExchangeOfferStatus status);

  /**
   * Counts a single owner's offers in the given status — the "Meine Angebote" tab count.
   *
   * @param status the status to count.
   * @param ownerId the owner whose offers to count.
   * @return the number of the owner's offers in that status.
   */
  long countByStatusAndOwnerId(MaterialExchangeOfferStatus status, UUID ownerId);
}
