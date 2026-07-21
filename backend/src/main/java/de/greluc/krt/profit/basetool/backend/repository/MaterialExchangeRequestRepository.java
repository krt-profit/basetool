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

import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeRequest;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeRequestStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Spring Data repository for {@link MaterialExchangeRequest}. */
@Repository
public interface MaterialExchangeRequestRepository
    extends JpaRepository<MaterialExchangeRequest, UUID> {

  /**
   * The Materialbörse Gesuche board query — every {@code ACTIVE} request, optionally narrowed to
   * the caller's own requests (the "Meine Gesuche" tab) and by the toolbar filters. The board is
   * org-wide (no OrgUnit scope filter, mirroring the offer board): every active request is visible
   * to every member.
   *
   * <p>The board carries both request kinds (REQ-MARKET-015). Because an item request has a {@code
   * NULL} {@code requested_material_id}, the material / owner / org-unit associations are joined
   * with an explicit {@code LEFT JOIN FETCH} (an implicit path join would be an inner join and
   * would silently drop item requests) — this both eager-loads them so the list renders without an
   * N+1 and exposes the aliases the filters and the sort need. The desired quantity spans both
   * branches via a {@code CASE}: {@code CASE WHEN r.requestedAmount IS NOT NULL THEN
   * r.requestedAmount ELSE r.itemQuantity END} — a material request uses its SCU amount, an item
   * request its whole-piece quantity. Unlike an offer there is <b>no stock to clamp against</b> (a
   * request has no backing Lager row), so the expression is a plain branch, not a {@code LEAST}. It
   * is duplicated <b>byte-for-byte</b> at three sites — the main-query {@code WHERE} min-amount
   * filter, the {@code menge} {@code ORDER BY}, and the {@code countQuery} {@code WHERE} — and the
   * three must stay in sync. The material/item name is {@code COALESCE}-d likewise.
   *
   * <p>The min-quality filter matches requests whose stated {@code minQuality} is at least the
   * floor; a non-zero floor therefore excludes requests that state no minimum quality (mirroring
   * how the offer board's non-zero quality filter excludes item offers, which have no quality). All
   * fetched associations are single-valued {@code @ManyToOne}, so pagination stays a DB {@code
   * LIMIT}. The sort is embedded (driven by {@code sortKey}) rather than carried on the {@link
   * Pageable}, so the caller passes an unsorted page request.
   *
   * @param viewerId the caller's user id — used only when {@code onlyMine} is {@code true}.
   * @param onlyMine {@code true} for the "Meine Gesuche" tab, {@code false} for "Alle Gesuche".
   * @param query a pre-lowercased {@code %fragment%} matched against the material/item name and the
   *     owner's username/display name, or {@code null} for no text filter.
   * @param minQuality the inclusive minimum quality floor (0 disables the filter; a non-zero value
   *     excludes requests that state no minimum quality).
   * @param minAmount the inclusive minimum desired quantity (SCU for a material request, pieces for
   *     an item request), or {@code null} for no amount filter.
   * @param sortKey the whitelisted sort key — {@code menge} / {@code mat} / {@code neu}, else
   *     quality (the default); must be non-null.
   * @param pageable the (unsorted) page request — the ORDER BY is embedded in the query.
   * @return the matching page of active requests, never {@code null}.
   */
  @Query(
      value =
          """
          SELECT r FROM MaterialExchangeRequest r
          LEFT JOIN FETCH r.requestedMaterial m
          LEFT JOIN FETCH r.owner ow
          LEFT JOIN FETCH r.owningOrgUnit
          WHERE r.status = de.greluc.krt.profit.basetool.backend.model.MaterialExchangeRequestStatus.ACTIVE
            AND (:onlyMine = false OR ow.id = :viewerId)
            AND (:query IS NULL
                 OR LOWER(m.name) LIKE :query
                 OR LOWER(r.itemName) LIKE :query
                 OR LOWER(ow.username) LIKE :query
                 OR LOWER(ow.displayName) LIKE :query)
            AND (:minQuality = 0 OR (r.minQuality IS NOT NULL AND r.minQuality >= :minQuality))
            AND (:minAmount IS NULL
                 OR CASE WHEN r.requestedAmount IS NOT NULL THEN r.requestedAmount ELSE r.itemQuantity END >= :minAmount)
          ORDER BY
            CASE WHEN :sortKey = 'menge' THEN CASE WHEN r.requestedAmount IS NOT NULL THEN r.requestedAmount ELSE r.itemQuantity END END DESC,
            CASE WHEN :sortKey = 'mat' THEN LOWER(COALESCE(m.name, r.itemName)) END ASC,
            CASE WHEN :sortKey = 'neu' THEN r.postedAt END DESC,
            CASE WHEN :sortKey NOT IN ('menge', 'mat', 'neu') THEN COALESCE(r.minQuality, -1) END DESC,
            r.postedAt DESC,
            r.id DESC
          """,
      countQuery =
          """
          SELECT COUNT(r) FROM MaterialExchangeRequest r
          LEFT JOIN r.requestedMaterial m
          LEFT JOIN r.owner ow
          WHERE r.status = de.greluc.krt.profit.basetool.backend.model.MaterialExchangeRequestStatus.ACTIVE
            AND (:onlyMine = false OR ow.id = :viewerId)
            AND (:query IS NULL
                 OR LOWER(m.name) LIKE :query
                 OR LOWER(r.itemName) LIKE :query
                 OR LOWER(ow.username) LIKE :query
                 OR LOWER(ow.displayName) LIKE :query)
            AND (:minQuality = 0 OR (r.minQuality IS NOT NULL AND r.minQuality >= :minQuality))
            AND (:minAmount IS NULL
                 OR CASE WHEN r.requestedAmount IS NOT NULL THEN r.requestedAmount ELSE r.itemQuantity END >= :minAmount)
          """)
  Page<MaterialExchangeRequest> findBoard(
      @Param("viewerId") UUID viewerId,
      @Param("onlyMine") boolean onlyMine,
      @Param("query") String query,
      @Param("minQuality") int minQuality,
      @Param("minAmount") Double minAmount,
      @Param("sortKey") String sortKey,
      Pageable pageable);

  /**
   * Loads one request with all board associations eager-fetched for the detail pane, regardless of
   * status (an owner can still open a just-deactivated request via a stale link — the service maps
   * the status into the DTO).
   *
   * @param id the request id.
   * @return the request with its material / owner / org unit initialised, or empty.
   */
  @EntityGraph(attributePaths = {"requestedMaterial", "owner", "owningOrgUnit"})
  Optional<MaterialExchangeRequest> findWithDetailById(UUID id);

  /**
   * Counts requests in the given status across the whole board — the "Alle Gesuche" tab count and
   * the {@code basetool_material_request_open_count} business gauge.
   *
   * @param status the status to count.
   * @return the number of requests in that status.
   */
  long countByStatus(MaterialExchangeRequestStatus status);

  /**
   * Counts a single owner's requests in the given status — the "Meine Gesuche" tab count.
   *
   * @param status the status to count.
   * @param ownerId the owner whose requests to count.
   * @return the number of the owner's requests in that status.
   */
  long countByStatusAndOwnerId(MaterialExchangeRequestStatus status, UUID ownerId);
}
