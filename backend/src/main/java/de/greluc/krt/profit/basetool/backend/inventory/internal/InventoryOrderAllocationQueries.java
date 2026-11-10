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
import de.greluc.krt.profit.basetool.backend.inventory.api.OrderMaterialScope;
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
   * Reads the exact eligible merge destinations before any stock lock.
   *
   * @param userId the destination holder
   * @param materialId the material, or null for game-item stock
   * @param gameItemId the game item, or null for material stock
   * @param locationId the storage location
   * @param quality the material quality, or null for game-item stock
   * @param personal the destination personal marker
   * @param stolen the destination stolen marker
   * @param owningOrgUnitId the owning pool, or null for unassigned stock
   * @return matching existing rows excluding offer-backed stock
   */
  @Query(
      """
      SELECT i.id FROM InventoryItem i WHERE i.user.id = :userId AND
      ((:materialId IS NULL AND i.material IS NULL) OR i.material.id = :materialId) AND
      ((:gameItemId IS NULL AND i.gameItem IS NULL) OR i.gameItem.id = :gameItemId) AND
      i.location.id = :locationId AND
      ((:quality IS NULL AND i.quality IS NULL) OR i.quality = :quality) AND
      i.personal = :personal AND i.stolen = :stolen AND
      ((:owningOrgUnitId IS NULL AND i.owningOrgUnit IS NULL) OR i.owningOrgUnit.id =
      :owningOrgUnitId) AND NOT EXISTS (SELECT 1 FROM MaterialExchangeOffer o WHERE
      o.inventoryItemId = i.id) ORDER BY i.id ASC
      """)
  List<UUID> findMergeCandidateIds(
      @Param("userId") UUID userId,
      @Param("materialId") @Nullable UUID materialId,
      @Param("gameItemId") @Nullable UUID gameItemId,
      @Param("locationId") UUID locationId,
      @Param("quality") @Nullable Integer quality,
      @Param("personal") boolean personal,
      @Param("stolen") boolean stolen,
      @Param("owningOrgUnitId") @Nullable UUID owningOrgUnitId);

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
   * Resolves the cross-quality capacity scopes of the affected stock entries in one query.
   *
   * @param rowIds the entries about to change
   * @return distinct order and material scopes
   */
  @Query(
      """
      SELECT DISTINCT new de.greluc.krt.profit.basetool.backend.inventory.api.OrderMaterialScope(
          a.jobOrderId, a.inventoryItem.material.id)
      FROM InventoryJobOrderAllocation a WHERE a.inventoryItem.id IN :rowIds
        AND a.inventoryItem.material IS NOT NULL
      """)
  List<OrderMaterialScope> findAllocatedMaterialScopes(@Param("rowIds") Collection<UUID> rowIds);

  /**
   * Reads all material scopes on rows also earmarked to any selected order.
   *
   * @param orderIds orders whose linked physical rows may change
   * @return all current neighboring capacity scopes
   */
  @Query(
      """
      SELECT DISTINCT new de.greluc.krt.profit.basetool.backend.inventory.api.OrderMaterialScope(
          a.jobOrderId, i.material.id)
      FROM InventoryJobOrderAllocation a JOIN a.inventoryItem i
      WHERE i.material IS NOT NULL AND EXISTS (
          SELECT b.id FROM InventoryJobOrderAllocation b
          WHERE b.inventoryItem = i AND b.jobOrderId IN :orderIds)
      """)
  List<OrderMaterialScope> findMaterialScopesSharingRowsWithOrders(
      @Param("orderIds") Collection<UUID> orderIds);

  /** One current job-order slice used to validate a stock write plan. */
  interface OrderAllocationFact {
    /**
     * Returns the physical stock row.
     *
     * @return the row identity
     */
    UUID getRowId();

    /**
     * Returns the allocated job order.
     *
     * @return the target order identity
     */
    UUID getOrderId();

    /**
     * Returns the current allocation quantity.
     *
     * @return the earmarked amount
     */
    Double getAmount();
  }

  /**
   * Reads current allocation quantities independently of the managed persistence context.
   *
   * @param rowIds the physical rows
   * @return their current job-order slices
   */
  @Query(
      """
      SELECT a.inventoryItem.id AS rowId, a.jobOrderId AS orderId, a.amount AS amount
      FROM InventoryJobOrderAllocation a WHERE a.inventoryItem.id IN :rowIds
      """)
  List<OrderAllocationFact> findOrderAllocationFacts(@Param("rowIds") Collection<UUID> rowIds);

  /**
   * Reads the stock rows of holder lots before acquiring their advisory locks.
   *
   * @param holder the stock holder
   * @param lotKeys the affected lot keys
   * @return the current row identities
   */
  @Query(
      value =
          """
          SELECT i.id FROM inventory_item i WHERE i.user_id = :holder
            AND exchange_stock_lot_key(i.material_id, i.game_item_id, i.location_id,
                                       i.quality, i.stolen) IN (:lotKeys)
          ORDER BY i.id
          """,
      nativeQuery = true)
  List<UUID> findRowsInHolderLots(
      @Param("holder") UUID holder, @Param("lotKeys") Collection<String> lotKeys);

  /**
   * Resolves material scopes removed by a scoped nonpersonal wipe before any stock row lock.
   *
   * @param adminAll whether all shared rows are in scope
   * @param activeUnit the pinned unit
   * @param memberUnits the caller's scoped units
   * @return the affected cross-quality scopes
   */
  @Query(
      """
      SELECT DISTINCT new de.greluc.krt.profit.basetool.backend.inventory.api.OrderMaterialScope(
          a.jobOrderId, i.material.id)
      FROM InventoryJobOrderAllocation a JOIN a.inventoryItem i
      WHERE i.material IS NOT NULL AND i.personal = false
        AND (:adminAll = true OR (:activeUnit IS NOT NULL AND i.owningOrgUnit.id = :activeUnit)
          OR (:activeUnit IS NULL AND i.owningOrgUnit.id IN :memberUnits))
      """)
  List<OrderMaterialScope> findAllocatedMaterialScopesInWipeScope(
      @Param("adminAll") boolean adminAll,
      @Param("activeUnit") @Nullable UUID activeUnit,
      @Param("memberUnits") Collection<UUID> memberUnits);

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
