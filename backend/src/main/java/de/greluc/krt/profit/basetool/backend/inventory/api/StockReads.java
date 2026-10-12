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

package de.greluc.krt.profit.basetool.backend.inventory.api;

import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemDto;
import de.greluc.krt.profit.basetool.backend.model.projection.JobOrderGameItemStockRow;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The reads another module may ask of the Lager: the exchange's lots, the stock earmarked to a job
 * order and the rows the Materialbörse offers. One method per real use; none exposes a repository.
 * Every read joins the caller's transaction or runs in a read-only one of its own.
 */
public interface StockReads {

  /**
   * Returns one snapshot page of a member's lots, ordered by their lowest row id (REQ-XCH-016).
   *
   * @param member the member
   * @param after the lowest row id of the last lot delivered, or the zero id for the first page
   * @param limit the most lots to return
   * @return the lots
   */
  @NotNull
  List<InventoryItemRepository.ExchangeStockLotRow> exchangeLots(
      @NotNull UUID member, @NotNull UUID after, int limit);

  /**
   * Returns the member's lots with the given keys, as they are now.
   *
   * @param member the member
   * @param keys the lot keys
   * @return the lots that still hold rows
   */
  @NotNull
  List<InventoryItemRepository.ExchangeStockLotRow> exchangeLotsByKeys(
      @NotNull UUID member, @NotNull Collection<String> keys);

  /**
   * Counts a member's lots, personal and shared, as the exchange keys them.
   *
   * @param member the member
   * @return the number of lots
   */
  long countExchangeLots(@NotNull UUID member);

  /**
   * Returns every game-item earmark of the given job orders as one row each, in a single query
   * (REQ-INV-039).
   *
   * @param jobOrderIds the orders; an empty collection yields an empty list
   * @return one row per game-item slice
   */
  @NotNull
  List<JobOrderGameItemStockRow> gameItemStockOfOrders(@NotNull Collection<UUID> jobOrderIds);

  /**
   * Returns the material rows earmarked to a job order, sorted by owner, location, material name,
   * quality descending and amount descending (REQ-ORDERS-028).
   *
   * @param jobOrderId the order
   * @return the order's material rows
   */
  @NotNull
  List<LinkedStockRow> materialRowsOfOrder(@NotNull UUID jobOrderId);

  /**
   * Returns the game-item rows earmarked to a job order, sorted by owner, location, game-item name
   * and amount descending (REQ-ORDERS-028).
   *
   * @param jobOrderId the order
   * @return the order's game-item rows
   */
  @NotNull
  List<LinkedStockRow> gameItemRowsOfOrder(@NotNull UUID jobOrderId);

  /**
   * Returns the rows of one material earmarked to a job order as Lager DTOs, in the finder's order.
   *
   * @param jobOrderId the order
   * @param materialId the material
   * @return the linked rows
   */
  @NotNull
  List<InventoryItemDto> rowsOfOrderForMaterial(@NotNull UUID jobOrderId, @NotNull UUID materialId);

  /**
   * Returns the rows earmarked to a job order whose material or game item the order does not
   * require (REQ-ORDERS-019), material rows first.
   *
   * @param jobOrderId the order
   * @param requiredMaterialIds the materials the order requires
   * @param requiredGameItemIds the game items the order requires
   * @return the orphaned rows as Lager DTOs
   */
  @NotNull
  List<InventoryItemDto> orphanedRowsOfOrder(
      @NotNull UUID jobOrderId,
      @NotNull Set<UUID> requiredMaterialIds,
      @NotNull Set<UUID> requiredGameItemIds);

  /**
   * Loads one Lager row.
   *
   * @param rowId the row
   * @return the row, or empty when unknown
   */
  @NotNull
  Optional<InventoryItem> findRow(@NotNull UUID rowId);

  /**
   * Loads the given Lager rows with their material in one read.
   *
   * @param rowIds the rows
   * @return the existing rows
   */
  @NotNull
  List<InventoryItem> findRowsWithMaterial(@NotNull Collection<UUID> rowIds);

  /**
   * Returns the member's own material and game-item rows for the Materialbörse release picker,
   * ordered by catalogue name and capped before the page is cut (REQ-MARKET-014).
   *
   * @param userId the member
   * @param query a pre-lowercased {@code %fragment%} matched against the material or game-item
   *     name, or {@code null} for no filter
   * @param includeMaterial whether material rows are included
   * @param includeItem whether game-item rows are included
   * @param limit the cap on the number of rows
   * @return the matching rows
   */
  @NotNull
  List<InventoryItem> releasableRows(
      @NotNull UUID userId,
      @Nullable String query,
      boolean includeMaterial,
      boolean includeItem,
      int limit);
}
