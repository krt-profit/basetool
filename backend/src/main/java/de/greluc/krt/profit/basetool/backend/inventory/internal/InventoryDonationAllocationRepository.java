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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for {@link InventoryDonationAllocation}, the per-entry donation slices
 * (REQ-INV-057).
 */
@Repository
public interface InventoryDonationAllocationRepository
    extends JpaRepository<InventoryDonationAllocation, UUID> {

  /**
   * Loads a donation's slices only on its already prepared physical rows.
   *
   * @param donationId the allocation target
   * @param rowIds the complete nonempty prepared carrier set
   * @return the current slices in deterministic physical row order
   */
  @Query(
      """
      SELECT a FROM InventoryDonationAllocation a JOIN FETCH a.inventoryItem i
      WHERE a.donationId = :donationId AND i.id IN :rowIds ORDER BY i.id
      """)
  List<InventoryDonationAllocation> findSlicesByDonationIdAndRowIds(
      @Param("donationId") UUID donationId, @Param("rowIds") Collection<UUID> rowIds);
}
