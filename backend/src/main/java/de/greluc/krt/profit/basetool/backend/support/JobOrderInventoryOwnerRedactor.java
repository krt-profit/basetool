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

package de.greluc.krt.profit.basetool.backend.support;

import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemStockEntryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemStockGroupDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialCollectionEntryDto;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Blanks owner identity and location of a job order's linked stock for a caller who may not see the
 * order's responsible side (REQ-ORDERS-029, ADR-0107).
 *
 * <p>Each DTO is rebuilt field by field so that a new field fails compilation until someone decides
 * whether it may be shown; do not replace this with withers.
 */
@Component
public class JobOrderInventoryOwnerRedactor {

  /**
   * Returns a copy of the Item-Bestand groups with every entry's owner and location blanked (the
   * amounts, delivered marker and per-group ordered/manufactured context are kept).
   *
   * @param groups the item-stock groups to redact; a {@code null} input is returned unchanged.
   * @return the groups with each nested {@link JobOrderItemStockEntryDto} owner/location nulled.
   */
  @Nullable
  public List<JobOrderItemStockGroupDto> redactItemStockGroups(
      List<JobOrderItemStockGroupDto> groups) {
    if (groups == null) {
      return null;
    }
    return groups.stream()
        .map(
            g ->
                new JobOrderItemStockGroupDto(
                    g.gameItem(),
                    g.orderedAmount(),
                    g.manufacturedAmount(),
                    g.allocatedTotal(),
                    g.entries().stream().map(this::redactItemStockEntry).toList()))
        .toList();
  }

  /**
   * Blanks the owner and location of one Item-Bestand entry, keeping id, version, amounts and
   * delivered marker.
   *
   * @param entry the entry to redact
   * @return a copy with owner and location nulled
   */
  @NotNull
  private JobOrderItemStockEntryDto redactItemStockEntry(@NotNull JobOrderItemStockEntryDto entry) {
    return new JobOrderItemStockEntryDto(
        entry.inventoryEntryId(),
        entry.version(),
        null,
        null,
        null,
        null,
        entry.quantity(),
        entry.allocatedQuantity(),
        entry.delivered());
  }

  /**
   * Returns the material-collection entries with owner and location blanked.
   *
   * @param entries the entries to redact; {@code null} is returned unchanged
   * @return the entries with owner and location nulled
   */
  @Nullable
  public List<MaterialCollectionEntryDto> redactMaterialCollection(
      List<MaterialCollectionEntryDto> entries) {
    if (entries == null) {
      return null;
    }
    return entries.stream()
        .map(
            e ->
                new MaterialCollectionEntryDto(
                    e.inventoryEntryId(),
                    e.version(),
                    null,
                    null,
                    null,
                    null,
                    e.materialName(),
                    e.quality(),
                    e.quantity(),
                    e.allocatedQuantity(),
                    e.delivered()))
        .toList();
  }

  /**
   * Returns the inventory-item projections with owner ({@code user}, {@code owningSquadron}) and
   * {@code location} blanked.
   *
   * @param items the projections to redact; {@code null} is returned unchanged
   * @return the items with owner and location nulled
   */
  @Nullable
  public List<InventoryItemDto> redactInventoryItems(List<InventoryItemDto> items) {
    if (items == null) {
      return null;
    }
    return items.stream().map(this::redactInventoryItem).toList();
  }

  /**
   * Blanks the owner ({@code user}, {@code owningSquadron}) and {@code location} of a single
   * inventory-item projection, keeping every non-identity field.
   *
   * @param item the inventory-item projection to redact.
   * @return a copy with owner/location nulled.
   */
  @NotNull
  private InventoryItemDto redactInventoryItem(@NotNull InventoryItemDto item) {
    return new InventoryItemDto(
        item.id(),
        null,
        item.material(),
        item.gameItem(),
        null,
        item.quality(),
        item.amount(),
        item.personal(),
        item.jobOrderAllocations(),
        item.jobOrderRest(),
        item.missionAllocations(),
        item.missionRest(),
        item.note(),
        null,
        item.version(),
        item.canEdit(),
        item.createdAt());
  }
}
