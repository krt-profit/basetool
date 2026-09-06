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
import org.springframework.stereotype.Component;

/**
 * Single seam that blanks the inventory <em>owner identity and location</em> of the stock linked to
 * a job order before those projections leave the API to a caller who is not entitled to the order's
 * responsible (processing) side (REQ-ORDERS-029, ADR-0107). It is applied by the four
 * order-inventory read endpoints whenever {@code
 * OwnerScopeService.canSeeJobOrderInventoryOwners(jobOrderId)} is {@code false} — i.e. a
 * requesting-side viewer of an SK-public order: they keep seeing the order and its stock
 * <em>progress</em> (amounts, delivered marker, ordered/manufactured context), but the fulfilling
 * side's owner and Standort are hidden. For a squadron-responsible order the gate coincides with
 * {@code canSeeJobOrder}, so this redactor is never applied there.
 *
 * <p><b>The explicit full-field {@code new …Dto(...)} reconstruction is deliberate — do not
 * "simplify" it into pass-through withers.</b> Every component of each record is listed out here,
 * so adding an owner- or location-bearing field to any of these DTOs is a compile error until a
 * human decides, field by field, whether a requesting-side viewer may see it. That
 * compiler-enforced exhaustiveness is the load-bearing safety net that keeps a newly added identity
 * field from silently leaking; a wither ({@code dto.withOwner(null)}) would default a new field to
 * <em>pass-through</em>, the dangerous default for a redactor. Mirrors {@code
 * MissionPeerRedactor}.
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
   * Blanks the owner ({@code ownerName}, {@code ownerId}) and location ({@code location}, {@code
   * locationId}) of a single Item-Bestand entry, keeping the entry id, version, whole-unit amounts
   * and delivered marker.
   *
   * @param entry the entry to redact.
   * @return a copy with owner/location nulled.
   */
  private JobOrderItemStockEntryDto redactItemStockEntry(JobOrderItemStockEntryDto entry) {
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
   * Returns a copy of the material-collection entries with owner ({@code ownerName}, {@code
   * ownerId}) and location ({@code location}, {@code locationId}) blanked; material, quality,
   * quantities and the delivered marker are kept.
   *
   * @param entries the material-collection entries to redact; a {@code null} input is returned
   *     unchanged.
   * @return the entries with owner/location nulled.
   */
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
   * Returns a copy of the inventory-item projections (the per-material drill-down / orphaned-link
   * pickers) with the owner ({@code user}, {@code owningSquadron}) and {@code location} blanked;
   * the catalog reference, quality, amounts, allocations, note, version and creation instant are
   * kept.
   *
   * @param items the inventory-item projections to redact; a {@code null} input is returned
   *     unchanged.
   * @return the items with owner/location nulled.
   */
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
  private InventoryItemDto redactInventoryItem(InventoryItemDto item) {
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
        // The redaction hides WHOSE row it is, not whether this caller may write to it. Dropping
        // canEdit here would lock the owner out of a row the server still lets them edit.
        item.canEdit(),
        item.createdAt());
  }
}
