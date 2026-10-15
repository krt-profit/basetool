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

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.inventory.api.ExchangeOrgStockLotRow;
import de.greluc.krt.profit.basetool.backend.inventory.api.ExchangeStockLotRow;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryGameItemReferenceDto;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryItemDto;
import de.greluc.krt.profit.basetool.backend.inventory.api.JobOrderGameItemStockRow;
import de.greluc.krt.profit.basetool.backend.inventory.api.JobOrderMaterialStockRow;
import de.greluc.krt.profit.basetool.backend.inventory.api.LinkedStockRow;
import de.greluc.krt.profit.basetool.backend.inventory.api.OrderLinkedStockRows;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockReads;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockRow;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Lager's published reads over its repository, for the exchange, the job order and the
 * Materialbörse, and the material earmarks the layer's stock projection reads.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryStockReads implements StockReads, OrderLinkedStockRows {

  private final InventoryItemRepository inventoryRepository;
  private final InventoryItemMapper inventoryItemMapper;

  @Override
  public @NotNull List<ExchangeStockLotRow> exchangeLots(
      @NotNull UUID member, @NotNull UUID after, int limit) {
    return inventoryRepository.findExchangeLots(member, after, limit);
  }

  @Override
  public @NotNull List<ExchangeOrgStockLotRow> exchangeOrgLots(
      @NotNull Collection<UUID> units, @NotNull UUID after, int limit) {
    return units.isEmpty()
        ? List.of()
        : inventoryRepository.findExchangeOrgLots(units, after, limit);
  }

  @Override
  public @NotNull List<ExchangeStockLotRow> exchangeLotsByKeys(
      @NotNull UUID member, @NotNull Collection<String> keys) {
    return inventoryRepository.findExchangeLotsByKeys(member, keys);
  }

  @Override
  public long countExchangeLots(@NotNull UUID member) {
    return inventoryRepository.countExchangeLots(member);
  }

  @Override
  public @NotNull List<JobOrderGameItemStockRow> gameItemStockOfOrders(
      @NotNull Collection<UUID> jobOrderIds) {
    return inventoryRepository.findGameItemStockRowsByJobOrderIds(jobOrderIds);
  }

  @Override
  public @NotNull List<JobOrderMaterialStockRow> materialStockOfOrders(
      @NotNull Collection<UUID> jobOrderIds) {
    return inventoryRepository.findMaterialStockRowsByJobOrderIds(jobOrderIds);
  }

  @Override
  public @NotNull List<LinkedStockRow> materialRowsOfOrder(@NotNull UUID jobOrderId) {
    return inventoryRepository.findByJobOrderIdOrdered(jobOrderId).stream()
        .map(item -> linkedRow(item, jobOrderId, null))
        .toList();
  }

  @Override
  public @NotNull List<LinkedStockRow> gameItemRowsOfOrder(@NotNull UUID jobOrderId) {
    return inventoryRepository.findGameItemRowsByJobOrderIdOrdered(jobOrderId).stream()
        .map(
            item ->
                linkedRow(
                    item,
                    jobOrderId,
                    inventoryItemMapper.gameItemToReferenceDto(item.getGameItem())))
        .toList();
  }

  @Override
  public @NotNull List<InventoryItemDto> rowsOfOrderForMaterial(
      @NotNull UUID jobOrderId, @NotNull UUID materialId) {
    return inventoryRepository.findByJobOrderIdAndMaterialId(jobOrderId, materialId).stream()
        .map(inventoryItemMapper::toDto)
        .toList();
  }

  @Override
  public @NotNull List<InventoryItemDto> orphanedRowsOfOrder(
      @NotNull UUID jobOrderId,
      @NotNull Set<UUID> requiredMaterialIds,
      @NotNull Set<UUID> requiredGameItemIds) {
    return Stream.concat(
            inventoryRepository.findByJobOrderIdOrdered(jobOrderId).stream()
                .filter(
                    item ->
                        item.getMaterial() == null
                            || !requiredMaterialIds.contains(item.getMaterial().getId())),
            inventoryRepository.findGameItemRowsByJobOrderIdOrdered(jobOrderId).stream()
                .filter(
                    item ->
                        item.getGameItem() == null
                            || !requiredGameItemIds.contains(item.getGameItem().getId())))
        .map(inventoryItemMapper::toDto)
        .toList();
  }

  @Override
  public @NotNull Optional<StockRow> findRow(@NotNull UUID rowId) {
    return inventoryRepository.findById(rowId).map(StockRow.class::cast);
  }

  @Override
  public @NotNull List<StockRow> findRowsWithMaterial(@NotNull Collection<UUID> rowIds) {
    return new ArrayList<StockRow>(inventoryRepository.findWithMaterialByIdIn(rowIds));
  }

  @Override
  public @NotNull List<StockRow> releasableRows(
      @NotNull UUID userId,
      @Nullable String query,
      boolean includeMaterial,
      boolean includeItem,
      int limit) {
    return new ArrayList<StockRow>(
        inventoryRepository.findReleasableForUser(
            userId, query, includeMaterial, includeItem, PageRequest.of(0, limit)));
  }

  /**
   * Projects one Lager row earmarked to an order into its stock-view row.
   *
   * @param item the row with owner, location and catalogue entry loaded
   * @param jobOrderId the order whose slice is read
   * @param gameItem the row's game-item reference, or {@code null} for a material row
   * @return the stock-view row
   */
  private static @NotNull LinkedStockRow linkedRow(
      @NotNull InventoryItem item,
      @NotNull UUID jobOrderId,
      @Nullable InventoryGameItemReferenceDto gameItem) {
    User owner = item.getUser();
    InventoryJobOrderAllocation slice = InventoryAllocations.jobOrderSlice(item, jobOrderId);
    Double sliceAmount = slice == null ? null : slice.getAmount();
    return new LinkedStockRow(
        item.getId(),
        item.getVersion() != null ? item.getVersion() : 0L,
        owner.getDisplayName() != null ? owner.getDisplayName() : owner.getUsername(),
        owner.getId(),
        item.getLocation().getName(),
        item.getLocation().getId(),
        gameItem == null ? Objects.requireNonNull(item.getMaterial()).getName() : null,
        item.getQuality(),
        gameItem,
        item.getAmount(),
        sliceAmount != null ? sliceAmount : item.getAmount(),
        slice != null && Boolean.TRUE.equals(slice.getDelivered()));
  }
}
