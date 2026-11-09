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

import de.greluc.krt.profit.basetool.backend.inventory.api.JobOrderGameItemStockRow;
import de.greluc.krt.profit.basetool.backend.inventory.api.JobOrderMaterialStockRow;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Reads inventory allocation identities and stock projections for job orders. */
@NoRepositoryBean
interface InventoryOrderAllocationQueries extends Repository<InventoryItem, UUID> {
  /**
   * Resolves order slices before a stock-row write.
   *
   * @param rowIds the affected entries
   * @return distinct affected order ids
   */
  @Query(
      "SELECT DISTINCT a.jobOrderId FROM InventoryJobOrderAllocation a WHERE a.inventoryItem.id IN"
          + " :rowIds")
  List<UUID> findAllocatedOrderIds(@Param("rowIds") Collection<UUID> rowIds);

  /**
   * Resolves the order slices removed by a scoped nonpersonal wipe.
   *
   * @param adminAll whether all rows are in scope
   * @param activeUnit the pinned unit
   * @param memberUnits the scoped units
   * @return distinct affected order ids
   */
  @Query(
      "SELECT DISTINCT a.jobOrderId FROM InventoryJobOrderAllocation a JOIN a.inventoryItem i WHERE"
          + " i.personal = false AND (:adminAll = true OR (:activeUnit IS NOT NULL AND"
          + " i.owningOrgUnit.id = :activeUnit) OR (:activeUnit IS NULL AND i.owningOrgUnit.id IN"
          + " :memberUnits))")
  List<UUID> findAllocatedOrdersInWipeScope(
      @Param("adminAll") boolean adminAll,
      @Param("activeUnit") @Nullable UUID activeUnit,
      @Param("memberUnits") Collection<UUID> memberUnits);

  /**
   * Returns every material allocation of the given job orders as one row each (row, material,
   * quality, amount), in a single query (REQ-DATA-003).
   *
   * @param jobOrderIds the orders whose linked stock to project; an empty collection yields an
   *     empty list.
   * @return one {@link JobOrderMaterialStockRow} per material job-order allocation, never {@code
   *     null}.
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.inventory.api.JobOrderMaterialStockRow(a.jobOrderId, a.inventoryItem.id, a.inventoryItem.material.id, a.inventoryItem.quality, a.amount)
      FROM InventoryJobOrderAllocation a WHERE a.jobOrderId IN :jobOrderIds
      AND a.inventoryItem.material IS NOT NULL
      """)
  List<JobOrderMaterialStockRow> findMaterialStockRowsByJobOrderIds(
      @Param("jobOrderIds") Collection<UUID> jobOrderIds);

  /**
   * Returns every game-item allocation of the given job orders as one row each, in a single query
   * (REQ-INV-039).
   *
   * @param jobOrderIds the orders whose earmarked item stock to project; an empty collection yields
   *     an empty list.
   * @return one {@link
   *     de.greluc.krt.profit.basetool.backend.inventory.api.JobOrderGameItemStockRow} per game-item
   *     job-order allocation, never {@code null}.
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.inventory.api.JobOrderGameItemStockRow(a.jobOrderId, a.inventoryItem.gameItem.id, a.amount)
      FROM InventoryJobOrderAllocation a WHERE a.jobOrderId IN :jobOrderIds
      AND a.inventoryItem.gameItem IS NOT NULL
      """)
  List<JobOrderGameItemStockRow> findGameItemStockRowsByJobOrderIds(
      @Param("jobOrderIds") Collection<UUID> jobOrderIds);
}
