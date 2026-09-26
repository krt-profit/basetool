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
   * Materialbörse Gesuche board query: every {@code ACTIVE} material or item request org-wide,
   * optionally narrowed to the caller's own requests and by the toolbar filters (REQ-MARKET-015).
   *
   * <p>The desired quantity is the SCU amount for a material request and the piece count for an
   * item request; the {@code CASE} computing it appears three times in the query and must stay
   * identical. The sort is embedded, so {@code pageable} must be unsorted.
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
   * @param pageable the unsorted page request.
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
