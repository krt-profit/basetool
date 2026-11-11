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

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockOfferLookup;
import de.greluc.krt.profit.basetool.backend.kernel.ScuAmounts;
import de.greluc.krt.profit.basetool.backend.kernel.StringNormalization;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Merges compatible stock rows and their earmarks under the existing row locks. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class InventoryMergeService {
  private static final int NOTE_MAX_LENGTH = 1000;
  private static final String NOTE_MERGE_SEPARATOR = "\n";
  private final InventoryItemRepository inventoryItemRepository;
  private final StockOfferLookup stockOfferLookup;
  private final AuditRecorder auditRecorder;

  /**
   * Folds every other row with the same physical stock identity into {@code row} (REQ-INV-026):
   * amounts summed, distinct notes concatenated, earmarks unioned, siblings deleted.
   *
   * <p>Always applies to {@code PIECE} materials and game items; for {@code SCU} only when {@code
   * clientRequestedMerge} is set. The group is locked {@code FOR UPDATE}, and rows backing a
   * Materialbörse offer are never changed or folded; the offers are checked again once the group is
   * locked, since an offer released while the lock waited is not excluded by the locking read. Must
   * run inside the caller's transaction.
   *
   * @param row the just-written target row (managed); the merge survivor
   * @param clientRequestedMerge the per-action opt-in for an {@code SCU} material (ignored for
   *     {@code PIECE} materials and game-item rows)
   * @return {@code row}, merged or unchanged
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public InventoryItem mergeStockIfRequested(
      @NotNull InventoryItem row, boolean clientRequestedMerge) {
    final Material material = row.getMaterial();
    final boolean gameItemRow = row.getGameItem() != null;
    if (material == null && !gameItemRow) {
      return row;
    }
    if (!row.countsWholeUnits() && !clientRequestedMerge) {
      return row;
    }
    if (stockOfferLookup.isOffered(row.getId())) {
      return row;
    }

    final List<InventoryItem> group =
        gameItemRow
            ? inventoryItemRepository.findMergeGroupForUpdate(
                row.getUser().getId(),
                null,
                Objects.requireNonNull(row.getGameItem()).getId(),
                row.getLocation().getId(),
                null,
                row.getPersonal(),
                row.getStolen(),
                row.getOwningOrgUnit() != null ? row.getOwningOrgUnit().getId() : null)
            : inventoryItemRepository.findMergeGroupForUpdate(
                row.getUser().getId(),
                Objects.requireNonNull(material).getId(),
                row.getLocation().getId(),
                row.getQuality(),
                row.getPersonal(),
                row.getStolen(),
                row.getOwningOrgUnit() != null ? row.getOwningOrgUnit().getId() : null);

    final List<InventoryItem> candidates =
        group.stream().filter(candidate -> !candidate.getId().equals(row.getId())).toList();
    final Set<UUID> offered =
        stockOfferLookup.offeredRows(candidates.stream().map(InventoryItem::getId).toList());
    final List<InventoryItem> victims =
        candidates.stream().filter(candidate -> !offered.contains(candidate.getId())).toList();
    if (victims.isEmpty()) {
      return row;
    }

    double total = row.getAmount() != null ? row.getAmount() : 0.0;
    final Set<String> notes = new LinkedHashSet<>();
    collectNote(notes, row.getNote());
    for (InventoryItem victim : victims) {
      total += victim.getAmount() != null ? victim.getAmount() : 0.0;
      collectNote(notes, victim.getNote());
    }

    row.setAmount(ScuAmounts.roundToScuScale(total));
    row.setNote(mergeNotes(notes));
    for (InventoryItem victim : victims) {
      InventoryAllocations.unionInto(row, victim);
    }

    inventoryItemRepository.deleteAll(victims);
    inventoryItemRepository.saveAndFlush(row);

    auditRecorder.record(
        AuditEventType.INVENTORY_ITEM_MERGED,
        row.getId(),
        InventoryAuditLabels.label(row),
        row.getUser().getId(),
        AuditDetails.of("merged", victims.size())
            .with("total", row.getAmount())
            .with("q", row.getQuality())
            .with("trigger", row.countsWholeUnits() ? "auto" : "manual"));
    return row;
  }

  /**
   * Adds a note to the merge accumulator, trimmed to {@code null} and skipped when blank, so the
   * merged note carries only the distinct non-empty contributions in first-seen order.
   *
   * @param notes the ordered accumulator of distinct notes.
   * @param note the raw note of one folded row, possibly {@code null} or blank.
   */
  private static void collectNote(Set<String> notes, @Nullable String note) {
    final String normalized = StringNormalization.trimToNull(note);
    if (normalized != null) {
      notes.add(normalized);
    }
  }

  /**
   * Joins the distinct merged notes with {@link #NOTE_MERGE_SEPARATOR}, truncating to {@link
   * #NOTE_MAX_LENGTH} so the write never overflows the {@code inventory_item.note} column.
   *
   * @param notes the ordered accumulator of distinct notes.
   * @return the combined note, or {@code null} when no folded row carried a note.
   */
  @Nullable
  private static String mergeNotes(Set<String> notes) {
    if (notes.isEmpty()) {
      return null;
    }
    final String joined = String.join(NOTE_MERGE_SEPARATOR, notes);
    return joined.length() > NOTE_MAX_LENGTH ? joined.substring(0, NOTE_MAX_LENGTH) : joined;
  }
}
