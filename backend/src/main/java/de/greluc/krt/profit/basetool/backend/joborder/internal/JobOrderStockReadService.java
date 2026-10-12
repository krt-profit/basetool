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

import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryGameItemReferenceDto;
import de.greluc.krt.profit.basetool.backend.inventory.api.LinkedStockRow;
import de.greluc.krt.profit.basetool.backend.inventory.api.MaterialCollectionEntryDto;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockReads;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderItem;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemStockEntryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemStockGroupDto;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the Lager stock earmarked to a job order, for the order's material collection and its
 * item-stock view (REQ-ORDERS-028).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class JobOrderStockReadService {

  private final StockReads stockReads;
  private final JobOrderRepository jobOrderRepository;

  /**
   * Returns all inventory items linked to the given job order, sorted server-side by owner name,
   * location, material name, quality (desc), quantity (desc).
   *
   * @param jobOrderId the UUID of the job order
   * @return sorted list of {@link MaterialCollectionEntryDto}
   * @throws NotFoundException when the job order is unknown
   */
  public List<MaterialCollectionEntryDto> getMaterialCollection(UUID jobOrderId) {
    Entities.require(jobOrderRepository.findById(jobOrderId), "Job order not found");
    return stockReads.materialRowsOfOrder(jobOrderId).stream()
        .map(
            row ->
                new MaterialCollectionEntryDto(
                    row.id(),
                    row.version(),
                    row.ownerName(),
                    row.ownerId(),
                    row.locationName(),
                    row.locationId(),
                    row.materialName(),
                    row.quality() != null ? row.quality().doubleValue() : null,
                    row.amount(),
                    row.allocatedAmount(),
                    row.delivered()))
        .toList();
  }

  /**
   * Returns the game-item stock earmarked to the given job order, grouped per game item and sorted
   * by name (REQ-ORDERS-028). Each group carries the ordered and manufactured amounts of the
   * order's matching item lines, {@code 0} when the order no longer requests that item.
   *
   * @param jobOrderId the UUID of the job order
   * @return name-sorted list of {@link JobOrderItemStockGroupDto}; empty when no game-item stock is
   *     earmarked to the order
   * @throws NotFoundException when the job order is unknown
   */
  @NotNull
  public List<JobOrderItemStockGroupDto> getItemStockForJobOrder(UUID jobOrderId) {
    JobOrder jobOrder =
        Entities.require(jobOrderRepository.findById(jobOrderId), "Job order not found");

    Map<UUID, int[]> lineTotals = new HashMap<>();
    for (JobOrderItem line : jobOrder.getItems()) {
      if (line.getGameItem() == null) {
        continue;
      }
      int[] totals = lineTotals.computeIfAbsent(line.getGameItem().getId(), _ -> new int[2]);
      totals[0] += line.getAmount() != null ? line.getAmount() : 0;
      totals[1] += line.getManufacturedAmount() != null ? line.getManufacturedAmount() : 0;
    }

    Map<UUID, List<LinkedStockRow>> byGameItem = new LinkedHashMap<>();
    for (LinkedStockRow row : stockReads.gameItemRowsOfOrder(jobOrderId)) {
      InventoryGameItemReferenceDto gameItem = row.gameItem();
      if (gameItem != null) {
        byGameItem.computeIfAbsent(gameItem.id(), _ -> new ArrayList<>()).add(row);
      }
    }

    List<JobOrderItemStockGroupDto> groups = new ArrayList<>();
    byGameItem.forEach(
        (gameItemId, rows) -> {
          List<JobOrderItemStockEntryDto> entries = new ArrayList<>();
          long allocatedTotal = 0L;
          for (LinkedStockRow row : rows) {
            long allocatedQuantity = Math.round(row.allocatedAmount());
            allocatedTotal += allocatedQuantity;
            entries.add(
                new JobOrderItemStockEntryDto(
                    row.id(),
                    row.version(),
                    row.ownerName(),
                    row.ownerId(),
                    row.locationName(),
                    row.locationId(),
                    Math.round(row.amount()),
                    allocatedQuantity,
                    row.delivered()));
          }
          int[] totals = lineTotals.getOrDefault(gameItemId, new int[2]);
          groups.add(
              new JobOrderItemStockGroupDto(
                  rows.getFirst().gameItem(), totals[0], totals[1], allocatedTotal, entries));
        });
    groups.sort(
        Comparator.<JobOrderItemStockGroupDto, String>comparing(
                g -> g.gameItem() != null && g.gameItem().name() != null ? g.gameItem().name() : "",
                String.CASE_INSENSITIVE_ORDER)
            .thenComparing(g -> g.gameItem() != null ? String.valueOf(g.gameItem().id()) : ""));
    return groups;
  }
}
