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

import de.greluc.krt.profit.basetool.backend.model.MaterialClaim;
import de.greluc.krt.profit.basetool.backend.model.QualityRequirement;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
  @EntityGraph(attributePaths = {"material", "claimingOrgUnit", "claimedByUser"})
  List<MaterialClaim> findByJobOrderIdOrderByCreatedAtDesc(UUID jobOrderId);

  /**
   * Loads every claim of the given orders in one query, newest first, with material, claiming org
   * unit and audit user fetched; the batched form of {@link
   * #findByJobOrderIdOrderByCreatedAtDesc(UUID)}.
   *
   * @param jobOrderIds the orders whose claims to load; an empty collection yields an empty list.
   * @return claims across the given orders, never {@code null}.
   */
  @EntityGraph(attributePaths = {"material", "claimingOrgUnit", "claimedByUser"})
  List<MaterialClaim> findByJobOrderIdInOrderByCreatedAtDesc(Collection<UUID> jobOrderIds);

  /**
   * Returns the single claim a squadron holds on one bucket, if any — the upsert lookup that keeps
   * the one-claim-per-{@code (bucket, squadron)} invariant (the caller updates the returned row's
   * amount instead of inserting a duplicate).
   *
   * @param jobOrderId the order.
   * @param materialId the material.
   * @param qualityRequirement the quality bucket.
   * @param claimingOrgUnitId the claiming squadron.
   * @return the existing claim, or empty.
   */
  Optional<MaterialClaim> findByJobOrderIdAndMaterialIdAndQualityRequirementAndClaimingOrgUnitId(
      UUID jobOrderId,
      UUID materialId,
      QualityRequirement qualityRequirement,
      UUID claimingOrgUnitId);

  /**
   * Returns every claim on one bucket across all squadrons — used to sum the already-claimed amount
   * for the open-remaining computation and the no-overclaim guard.
   *
   * @param jobOrderId the order.
   * @param materialId the material.
   * @param qualityRequirement the quality bucket.
   * @return claims on the bucket, never {@code null}.
   */
  List<MaterialClaim> findByJobOrderIdAndMaterialIdAndQualityRequirement(
      UUID jobOrderId, UUID materialId, QualityRequirement qualityRequirement);

  /**
   * Clears the {@code claimedByUser} audit reference on every claim stamped by the given user, so
   * the user can be deleted without an FK violation; the claims themselves remain.
   *
   * @param userId the user whose audit stamp is cleared from every claim
   */
  @Modifying
  @Query("UPDATE MaterialClaim mc SET mc.claimedByUser = null WHERE mc.claimedByUser.id = :userId")
  void unlinkClaimedByUser(@Param("userId") UUID userId);
}
