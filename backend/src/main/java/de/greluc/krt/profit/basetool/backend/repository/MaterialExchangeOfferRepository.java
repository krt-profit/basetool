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
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Spring Data repository for {@link MaterialExchangeOffer}. */
@Repository
public interface MaterialExchangeOfferRepository
    extends JpaRepository<MaterialExchangeOffer, UUID> {

  /**
   * The Materialbörse board query — every {@code ACTIVE} offer, optionally narrowed to the caller's
   * own offers (the "Meine Angebote" tab) and by the toolbar filters. The board is org-wide (no
   * OrgUnit scope filter, decision D3): every active offer is visible to every member.
   *
   * <p>The board carries both offer kinds (REQ-MARKET-012). Because an item offer has a {@code
   * NULL} {@code inventory_item_id}, the item / material / owner / org-unit associations are joined
   * with an explicit {@code LEFT JOIN FETCH} (an implicit path join would be an inner join and
   * would silently drop item offers) — this both eager-loads them so the list renders without an
   * N+1 and exposes the aliases the filters and the sort need. The name filter and the sort span
   * both branches via {@code COALESCE}: a material offer's effective amount is {@code
   * LEAST(offeredAmount, item.amount)} — the owner-chosen offered quantity clamped to current stock
   * (ADR-0086) — and an item offer's is its stated {@code itemQuantity} ({@code
   * COALESCE(LEAST(...), itemQuantity)}); the material/item name is COALESCE-d likewise. The
   * location is never referenced, keeping the Standort private (REQ-MARKET-004). The min-quality
   * filter applies only to material offers — an item offer has no quality, so a non-zero
   * min-quality excludes item offers. All fetched associations are single-valued
   * {@code @ManyToOne}, so pagination stays a DB {@code LIMIT}. The sort is embedded (driven by
   * {@code sortKey}) rather than carried on the {@link Pageable}, so the caller passes an unsorted
   * page request.
   *
   * @param viewerId the caller's user id — used only when {@code onlyMine} is {@code true}.
   * @param onlyMine {@code true} for the "Meine Angebote" tab, {@code false} for "Alle Angebote".
   * @param query a pre-lowercased {@code %fragment%} matched against the material/item name and the
   *     owner's username/display name, or {@code null} for no text filter.
   * @param minQuality the inclusive minimum quality (0 disables the filter; a non-zero value
   *     excludes item offers, which have no quality).
   * @param minAmount the inclusive minimum quantity (SCU for a material offer, pieces for an item
   *     offer), or {@code null} for no amount filter.
   * @param sortKey the whitelisted sort key — {@code menge} / {@code mat} / {@code neu}, else
   *     quality (the default); must be non-null.
   * @param pageable the (unsorted) page request — the ORDER BY is embedded in the query.
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
                 OR (ii.id IS NOT NULL AND LEAST(o.offeredAmount, ii.amount) >= :minAmount)
                 OR (ii.id IS NULL AND o.itemQuantity >= :minAmount))
          ORDER BY
            CASE WHEN :sortKey = 'menge' THEN COALESCE(LEAST(o.offeredAmount, ii.amount), o.itemQuantity) END DESC,
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
                 OR (ii.id IS NOT NULL AND LEAST(o.offeredAmount, ii.amount) >= :minAmount)
                 OR (ii.id IS NULL AND o.itemQuantity >= :minAmount))
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
