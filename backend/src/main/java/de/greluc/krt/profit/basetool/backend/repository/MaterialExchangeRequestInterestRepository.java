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

import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeRequestInterest;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialExchangeRequestInterestCount;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Spring Data repository for {@link MaterialExchangeRequestInterest}. */
@Repository
public interface MaterialExchangeRequestInterestRepository
    extends JpaRepository<MaterialExchangeRequestInterest, UUID> {

  /**
   * Returns a member's existing fulfilment signal on one request, if any — the upsert lookup that
   * keeps the one-signal-per-{@code (request, user)} invariant (a duplicate "Ich kann liefern" is a
   * no-op).
   *
   * @param requestId the request.
   * @param interestedUserId the member.
   * @return the existing signal, or empty.
   */
  Optional<MaterialExchangeRequestInterest> findByRequestIdAndInterestedUserId(
      UUID requestId, UUID interestedUserId);

  /**
   * Whether a member has already signalled they can supply one request — drives the "du dabei" /
   * toggle-to-withdraw state for that single request.
   *
   * @param requestId the request.
   * @param interestedUserId the member.
   * @return {@code true} if a signal exists.
   */
  boolean existsByRequestIdAndInterestedUserId(UUID requestId, UUID interestedUserId);

  /**
   * Counts the suppliers on one request — the anonymity-safe "N können liefern" figure every
   * non-owner viewer sees (no names).
   *
   * @param requestId the request.
   * @return the number of signals on that request.
   */
  long countByRequestId(UUID requestId);

  /**
   * Loads the suppliers of one request newest-first, eager-loading the {@code interestedUser} so
   * the owner-only name list renders without an N+1. Only ever called for the request's owner (the
   * service enforces the anonymity gate before disclosing names).
   *
   * @param requestId the request.
   * @return the signals on that request, never {@code null}.
   */
  @EntityGraph(attributePaths = {"interestedUser"})
  List<MaterialExchangeRequestInterest> findByRequestIdOrderByCreatedAtDesc(UUID requestId);

  /**
   * Grouped supplier counts across a batch of requests — one row per request with at least one
   * signal — so the board list attaches every count in a single query (no N+1). Requests with zero
   * signals are simply absent from the result (the service defaults them to 0). Returns only ids
   * and counts, never supplier identities (REQ-MARKET-019).
   *
   * @param requestIds the requests being rendered; an empty collection yields an empty list.
   * @return the per-request counts, never {@code null}.
   */
  @Query(
      "SELECT new de.greluc.krt.profit.basetool.backend.model.dto."
          + "MaterialExchangeRequestInterestCount(i.request.id, COUNT(i)) "
          + "FROM MaterialExchangeRequestInterest i WHERE i.request.id IN :requestIds "
          + "GROUP BY i.request.id")
  List<MaterialExchangeRequestInterestCount> countByRequestIdIn(
      @Param("requestIds") Collection<UUID> requestIds);

  /**
   * Returns the subset of the given requests the viewer has personally signalled they can supply —
   * so the board list can mark the viewer's own signals ("du dabei") in one query.
   *
   * @param viewerId the viewing member.
   * @param requestIds the requests being rendered; an empty collection yields an empty set.
   * @return the ids of the requests the viewer has signalled on, never {@code null}.
   */
  @Query(
      "SELECT i.request.id FROM MaterialExchangeRequestInterest i "
          + "WHERE i.interestedUser.id = :viewerId AND i.request.id IN :requestIds")
  Set<UUID> findRequestIdsInterestedByViewer(
      @Param("viewerId") UUID viewerId, @Param("requestIds") Collection<UUID> requestIds);

  /**
   * Withdraws a member's fulfilment signal from one request ("doch nicht liefern"). Idempotent —
   * deleting a non-existent signal removes zero rows.
   *
   * @param requestId the request.
   * @param interestedUserId the member withdrawing.
   * @return the number of rows removed (0 or 1).
   */
  @Modifying
  long deleteByRequestIdAndInterestedUserId(UUID requestId, UUID interestedUserId);
}
