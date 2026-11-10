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

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderUnitReadHistory;
import java.util.Collection;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Stores observed unit relationships without reconstructing them from audit text. */
public interface JobOrderUnitReadHistoryRepository
    extends JpaRepository<JobOrderUnitReadHistory, UUID> {

  /**
   * Checks a concrete order against the caller's current unit reach.
   *
   * @param jobOrderId the order
   * @param orgUnitIds current membership, leadership or contextual read units
   * @return whether an observed relationship matches
   */
  boolean existsByJobOrderIdAndOrgUnitIdIn(UUID jobOrderId, Collection<UUID> orgUnitIds);

  /**
   * Retains the first observation of a unit as an order party atomically.
   *
   * @param jobOrderId the existing order
   * @param orgUnitId the observed requesting or responsible unit
   */
  @Modifying
  @Query(
      value =
          """
          INSERT INTO job_order_unit_read_history (job_order_id, org_unit_id)
          VALUES (:jobOrderId, :orgUnitId)
          ON CONFLICT (job_order_id, org_unit_id) DO NOTHING
          """,
      nativeQuery = true)
  void retainObservedUnit(@Param("jobOrderId") UUID jobOrderId, @Param("orgUnitId") UUID orgUnitId);
}
