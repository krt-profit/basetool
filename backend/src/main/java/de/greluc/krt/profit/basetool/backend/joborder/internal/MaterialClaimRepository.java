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

import de.greluc.krt.profit.basetool.backend.joborder.api.MaterialClaim;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Spring Data repository for {@link MaterialClaim}. */
@Repository
public interface MaterialClaimRepository extends JpaRepository<MaterialClaim, UUID> {

  /**
   * Returns every claim on the given order, eager-loading the material / claiming org unit / audit
   * user so the bucket view renders without an N+1. Ordered newest-first by creation instant.
   *
   * @param jobOrderId the order whose claims to load.
   * @return claims on the order, never {@code null}.
   */
  @EntityGraph(
      attributePaths = {
        "material",
        "qualityTier",
        "claimingOrgUnit",
        "claimedByUser",
        "participation"
      })
  List<MaterialClaim> findByJobOrderIdOrderByCreatedAtDesc(UUID jobOrderId);

  /**
   * Loads every claim of the given orders in one query, newest first, with material, claiming org
   * unit and audit user fetched; the batched form of {@link
   * #findByJobOrderIdOrderByCreatedAtDesc(UUID)}.
   *
   * @param jobOrderIds the orders whose claims to load; an empty collection yields an empty list.
   * @return claims across the given orders, never {@code null}.
   */
  @EntityGraph(
      attributePaths = {
        "material",
        "qualityTier",
        "claimingOrgUnit",
        "claimedByUser",
        "participation"
      })
  List<MaterialClaim> findByJobOrderIdInOrderByCreatedAtDesc(Collection<UUID> jobOrderIds);

  /**
   * Returns the single claim a squadron holds on one bucket, if any — the upsert lookup that keeps
   * the one-claim-per-{@code (bucket, squadron)} invariant (the caller updates the returned row's
   * amount instead of inserting a duplicate).
   *
   * @param jobOrderId the order.
   * @param materialId the material.
   * @param qualityTierId the bucket's quality tier.
   * @param claimingOrgUnitId the claiming squadron.
   * @return the existing claim, or empty.
   */
  Optional<MaterialClaim> findByJobOrderIdAndMaterialIdAndQualityTierIdAndClaimingOrgUnitId(
      UUID jobOrderId, UUID materialId, UUID qualityTierId, UUID claimingOrgUnitId);

  /**
   * Returns every claim on one bucket across all squadrons — used to sum the already-claimed amount
   * for the open-remaining computation and the no-overclaim guard.
   *
   * @param jobOrderId the order.
   * @param materialId the material.
   * @param qualityTierId the bucket's quality tier.
   * @return claims on the bucket, never {@code null}.
   */
  List<MaterialClaim> findByJobOrderIdAndMaterialIdAndQualityTierId(
      UUID jobOrderId, UUID materialId, UUID qualityTierId);

  /**
   * Loads one bounded page of active promises of the affected ineligible units.
   *
   * @param unitIds the ineligible claiming units
   * @param pageable the bounded page
   * @return active promises with notice facts fetched
   */
  @EntityGraph(
      attributePaths = {
        "jobOrder",
        "material",
        "qualityTier",
        "claimingOrgUnit",
        "claimedByUser",
        "participation"
      })
  List<MaterialClaim> findByClaimingOrgUnitIdInOrderById(
      Collection<UUID> unitIds, Pageable pageable);

  /**
   * Lists a bounded keyset page of orders with active promises for initial reconciliation.
   *
   * @param afterOrderId the last order inspected, or null for the first page
   * @param pageable the bounded page
   * @return distinct order identities in identity order
   */
  @Query(
      "SELECT DISTINCT c.jobOrder.id FROM MaterialClaim c WHERE"
          + " (:afterOrderId IS NULL OR c.jobOrder.id > :afterOrderId) ORDER BY c.jobOrder.id")
  List<UUID> findActivePromiseOrderIdsAfter(
      @Param("afterOrderId") @Nullable UUID afterOrderId, Pageable pageable);

  /**
   * Loads a bounded page of one order's active promises outside current eligible units.
   *
   * @param orderId the order being reconciled
   * @param eligibleIds the current eligible unit identities
   * @param pageable the bounded page
   * @return ineligible active promises with audit and notice facts fetched
   */
  @EntityGraph(
      attributePaths = {
        "jobOrder",
        "material",
        "qualityTier",
        "claimingOrgUnit",
        "claimedByUser",
        "participation"
      })
  @Query(
      "SELECT c FROM MaterialClaim c WHERE c.jobOrder.id = :orderId"
          + " AND c.claimingOrgUnit.id NOT IN :eligibleIds ORDER BY c.id")
  List<MaterialClaim> findIneligibleActivePromisesByOrder(
      @Param("orderId") UUID orderId,
      @Param("eligibleIds") Collection<UUID> eligibleIds,
      Pageable pageable);

  /**
   * Loads a bounded page of active and ended promises for explicit whole-order deletion.
   *
   * @param orderId the order being deleted by its owning service
   * @param pageable the bounded page
   * @return managed dependent rows including history
   */
  @Query(
      value = "SELECT c.* FROM material_claim c WHERE c.job_order_id = :orderId ORDER BY c.id",
      nativeQuery = true)
  List<MaterialClaim> findForOrderIncludingHistory(
      @Param("orderId") UUID orderId, Pageable pageable);

  /**
   * Clears the {@code claimedByUser} audit reference on every claim stamped by the given user, so
   * the user can be deleted without an FK violation; the claims themselves remain.
   *
   * @param userId the user whose audit stamp is cleared from every claim
   */
  @Modifying
  @Query(
      value =
          "UPDATE material_claim SET claimed_by_user_id = null WHERE claimed_by_user_id = :userId",
      nativeQuery = true)
  void unlinkClaimedByUser(@Param("userId") UUID userId);
}
