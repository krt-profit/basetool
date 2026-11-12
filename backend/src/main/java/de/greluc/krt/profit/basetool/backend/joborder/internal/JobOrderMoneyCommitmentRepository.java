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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Loads current and historical unit commitments within their order. */
interface JobOrderMoneyCommitmentRepository extends JpaRepository<JobOrderMoneyCommitment, UUID> {
  /**
   * Finds the unit's single current commitment occurrence.
   *
   * @param orderId the order that receives the contribution
   * @param unitId the unit responsible for the promised amount
   * @return the current occurrence when one exists
   */
  Optional<JobOrderMoneyCommitment> findByOrderIdAndUnitIdAndEndedAtIsNull(
      UUID orderId, UUID unitId);

  /**
   * Loads all current and retained occurrences for an order.
   *
   * @param orderId the readable order
   * @return occurrences and their units in opening order
   */
  @EntityGraph(attributePaths = "unit")
  List<JobOrderMoneyCommitment> findByOrderIdOrderByOpenedAtAscIdAsc(UUID orderId);

  /**
   * Reads the owning unit without loading the mutable occurrence.
   *
   * @param orderId the order receiving the contribution
   * @param commitmentId the selected occurrence
   * @return the unit that owns the occurrence
   */
  @Query(
      "SELECT c.unit.id FROM JobOrderMoneyCommitment c WHERE c.order.id = :orderId AND c.id ="
          + " :commitmentId")
  Optional<UUID> unitIdForOccurrence(
      @Param("orderId") UUID orderId, @Param("commitmentId") UUID commitmentId);

  /**
   * Finds an occurrence in the concrete order, including when it has ended.
   *
   * @param id the explicitly selected occurrence
   * @param orderId the order receiving the contribution
   * @return the occurrence if it belongs to the order
   */
  Optional<JobOrderMoneyCommitment> findByIdAndOrderId(UUID id, UUID orderId);

  /**
   * Loads the order's current commitments in stable order.
   *
   * @param orderId the locked order
   * @return open occurrences to end together
   */
  @EntityGraph(attributePaths = "unit")
  List<JobOrderMoneyCommitment> findByOrderIdAndEndedAtIsNullOrderById(UUID orderId);

  /**
   * Loads a bounded batch of current commitments owned by affected units.
   *
   * @param unitIds the ineligible unit identities
   * @param pageable the batch limit
   * @return current occurrences with their audit and notice facts
   */
  @EntityGraph(attributePaths = {"order", "unit"})
  List<JobOrderMoneyCommitment> findByUnitIdInAndEndedAtIsNullOrderById(
      Set<UUID> unitIds, Pageable pageable);
}
