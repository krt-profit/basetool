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

package de.greluc.krt.profit.basetool.backend.supply.internal;

import de.greluc.krt.profit.basetool.backend.supply.api.NeedStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Spring Data repository for {@link SupplyNeed}. */
@Repository
public interface SupplyNeedRepository
    extends JpaRepository<SupplyNeed, UUID>, JpaSpecificationExecutor<SupplyNeed> {

  /**
   * Loads a need under a pessimistic write lock, for a credit that must not race another.
   *
   * @param id the need
   * @return the locked need, or empty when unknown
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT n FROM SupplyNeed n WHERE n.id = :id")
  Optional<SupplyNeed> findByIdForUpdate(@Param("id") UUID id);

  /**
   * Loads the open material needs linked to a job order whose unit was not yet told that the order
   * covers them, with their material and tier, for the covered-order notice (REQ-SUP-024).
   *
   * @param open the open state
   * @return the candidates, oldest first
   */
  @Query(
      """
      SELECT n FROM SupplyNeed n JOIN FETCH n.material JOIN FETCH n.qualityTier
      WHERE n.status = :open AND n.jobOrderId IS NOT NULL AND n.orderCoveredNotifiedAt IS NULL
      ORDER BY n.createdAt, n.id
      """)
  List<SupplyNeed> findOrderCoverageCandidates(@Param("open") NeedStatus open);

  /**
   * Marks a need as announced, atomically and without touching its version: the one caller whose
   * update changes the row is the one that announces it.
   *
   * @param id the need
   * @param now the announcement time
   * @return {@code 1} when this call marked the need, {@code 0} when it was marked already
   */
  @Modifying
  @Query(
      "UPDATE SupplyNeed n SET n.orderCoveredNotifiedAt = :now"
          + " WHERE n.id = :id AND n.orderCoveredNotifiedAt IS NULL")
  int markOrderCoveredNotified(@Param("id") UUID id, @Param("now") Instant now);

  /**
   * Adds a covered amount to an open or paused need in one statement, without touching the need's
   * version, so a concurrent edit of the need does not conflict (REQ-SUP-019).
   *
   * @param id the need
   * @param amount the amount to add
   * @param open the open state
   * @param paused the paused state
   * @return {@code 1} when the need took the amount, {@code 0} when it is not open or paused
   */
  @Modifying(flushAutomatically = true)
  @Query(
      "UPDATE SupplyNeed n SET n.fulfilledAmount = n.fulfilledAmount + :amount"
          + " WHERE n.id = :id AND n.status IN (:open, :paused)")
  int addFulfilled(
      @Param("id") UUID id,
      @Param("amount") double amount,
      @Param("open") NeedStatus open,
      @Param("paused") NeedStatus paused);

  /**
   * Adds an accepted purchase delivery to an open or paused need in one statement, without touching
   * the need's version.
   *
   * @param id the need
   * @param amount the amount to add
   * @param open the open state
   * @param paused the paused state
   * @return {@code 1} when the need took the amount, {@code 0} when it is not open or paused
   */
  @Modifying(flushAutomatically = true)
  @Query(
      "UPDATE SupplyNeed n SET n.fulfilledAmount = n.fulfilledAmount + :amount,"
          + " n.purchasedAmount = n.purchasedAmount + :amount"
          + " WHERE n.id = :id AND n.status IN (:open, :paused)")
  int addPurchased(
      @Param("id") UUID id,
      @Param("amount") double amount,
      @Param("open") NeedStatus open,
      @Param("paused") NeedStatus paused);

  /**
   * Marks an open or paused need fulfilled once what is covered reaches its amount.
   *
   * @param id the need
   * @param tolerance the rounding tolerance
   * @param fulfilled the fulfilled state
   * @param open the open state
   * @param paused the paused state
   * @return {@code 1} when the need was marked, {@code 0} otherwise
   */
  @Modifying(flushAutomatically = true)
  @Query(
      "UPDATE SupplyNeed n SET n.status = :fulfilled"
          + " WHERE n.id = :id AND n.status IN (:open, :paused)"
          + " AND n.fulfilledAmount >= n.amount - :tolerance")
  int markFulfilledIfCovered(
      @Param("id") UUID id,
      @Param("tolerance") double tolerance,
      @Param("fulfilled") NeedStatus fulfilled,
      @Param("open") NeedStatus open,
      @Param("paused") NeedStatus paused);

  /**
   * Counts the needs in a state, for the business gauge.
   *
   * @param status the state
   * @return the number of needs in it
   */
  long countByStatus(NeedStatus status);

  /**
   * Reads one need with its catalogue entry and tier in the same statement.
   *
   * @param id the need
   * @return the need, or empty
   */
  @EntityGraph(attributePaths = {"material", "gameItem", "qualityTier"})
  Optional<SupplyNeed> findWithCatalogueById(UUID id);

  /**
   * Reads several needs with their catalogue entries and tiers in one statement.
   *
   * @param ids the needs
   * @return the existing needs
   */
  @EntityGraph(attributePaths = {"material", "gameItem", "qualityTier"})
  List<SupplyNeed> findWithCatalogueByIdIn(Collection<UUID> ids);
}
