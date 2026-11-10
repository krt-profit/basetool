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

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderParticipation;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Loads the single participation of a Staffel on an order. */
public interface JobOrderParticipationRepository
    extends JpaRepository<JobOrderParticipation, UUID> {
  /**
   * Finds the durable participation, including one whose active support ended.
   *
   * @param jobOrderId the order
   * @param claimingOrgUnitId the Staffel
   * @return the existing participation
   */
  Optional<JobOrderParticipation> findByJobOrderIdAndClaimingOrgUnitId(
      UUID jobOrderId, UUID claimingOrgUnitId);

  /**
   * Tests a concrete observed support relationship, including ended support.
   *
   * @param jobOrderId the order
   * @param claimingOrgUnitIds currently authorized units
   * @return whether a current or historical participation exists
   */
  boolean existsByJobOrderIdAndClaimingOrgUnitIdIn(
      UUID jobOrderId, Collection<UUID> claimingOrgUnitIds);

  /**
   * Tests whether current units support any order independently of open promised quantities.
   *
   * @param claimingOrgUnitIds currently authorized units
   * @return whether an active support context exists
   */
  boolean existsByClaimingOrgUnitIdInAndActiveTrue(Collection<UUID> claimingOrgUnitIds);

  /**
   * Loads a bounded batch of current participations in ineligible units.
   *
   * @param unitIds units whose current eligibility was removed
   * @param pageable the batch bound
   * @return active participations with their order and unit
   */
  @EntityGraph(attributePaths = {"jobOrder", "claimingOrgUnit"})
  List<JobOrderParticipation> findByActiveTrueAndClaimingOrgUnitIdInOrderById(
      Collection<UUID> unitIds, Pageable pageable);

  /**
   * Loads one order's active participations outside the current eligible unit set.
   *
   * @param orderId the order inspected during maintenance
   * @param eligibleIds the current eligible units
   * @param pageable the batch bound
   * @return ineligible active participations with their order and unit
   */
  @EntityGraph(attributePaths = {"jobOrder", "claimingOrgUnit"})
  @Query(
      "SELECT p FROM JobOrderParticipation p WHERE p.active = true AND p.jobOrder.id = :orderId"
          + " AND p.claimingOrgUnit.id NOT IN :eligibleIds ORDER BY p.id")
  List<JobOrderParticipation> findIneligibleActiveByOrder(
      @Param("orderId") UUID orderId,
      @Param("eligibleIds") Collection<UUID> eligibleIds,
      Pageable pageable);

  /**
   * Loads a bounded stable batch of managed dependents for order deletion.
   *
   * @param jobOrderId the order
   * @param pageable the batch bound
   * @return current and ended participations in id order
   */
  List<JobOrderParticipation> findByJobOrderIdOrderById(UUID jobOrderId, Pageable pageable);
}
