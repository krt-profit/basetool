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
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryQuantityCorrectionOptionDto;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryRowVersionDto;
import de.greluc.krt.profit.basetool.backend.inventory.api.OrderMaterialScope;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockChangeObserver;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockQuantityCorrections;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockQuantityFragment;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockQuantityFragments;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.kernel.ScuAmounts;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Corrects exact retained physical intervals through the owning Inventory grants and lock protocol.
 */
@Service
@RequiredArgsConstructor
public class InventoryQuantityCorrections implements StockQuantityCorrections {
  private final InventoryItemRepository rows;
  private final tools.jackson.databind.ObjectMapper json;
  private final InventoryOrderWrites orderWrites;
  private final InventoryAccessPolicy access;
  private final StockChangeObserver stockChanges;
  private final AuditRecorder audit;

  @Override
  @Transactional(readOnly = true)
  public List<InventoryQuantityCorrectionOptionDto> currentRows(
      Collection<StockQuantityFragment> quantity) {
    List<UUID> ids = rowIds(quantity);
    return rows.findQuantityCorrectionRows(ids).stream()
        .filter(row -> removedFrom(row, quantity) > 0L)
        .map(row -> {
          requireEditable(row);
          return new InventoryQuantityCorrectionOptionDto(
              row.getId(),
              row.getVersion(),
              row.getLocation().getName(),
              row.getUser().getEffectiveName(),
              row.getOwningOrgUnit() == null ? null : row.getOwningOrgUnit().getName(),
              Boolean.TRUE.equals(row.getPersonal()),
              row.getAmount(),
              row.getQuality());
        })
        .toList();
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void removeKnownQuantity(
      Collection<StockQuantityFragment> removed,
      List<InventoryRowVersionDto> versions,
      Collection<OrderMaterialScope> requestedScopes,
      UUID factId) {
    orderWrites.lockEligibility();
    Set<UUID> ids = new TreeSet<>(rowIds(removed));
    List<InventoryOrderAllocationQueries.RowLot> placements = rows.findRowLots(ids);
    orderWrites.lockRowsAndTargets(ids, requestedScopes, List.of());
    Set<Long> lotKeys =
        placements.stream()
            .map(row ->
                InventoryStockCommands.lotLockKey(
                    row.getHolderId(),
                    StockLot.key(
                        row.getMaterialId(),
                        row.getGameItemId(),
                        row.getLocationId(),
                        row.getQuality(),
                        Boolean.TRUE.equals(row.getStolen()))))
            .collect(Collectors.toCollection(TreeSet::new));
    orderWrites.lockStockLots(lotKeys);
    final List<InventoryItem> locked = ids.isEmpty() ? List.of() : rows.lockAllByIdOrderById(ids);
    locked.forEach(this::requireEditable);
    orderWrites.validateRows(ids);
    if (!new HashSet<>(rowIds(removed)).equals(ids)) {
      throw new BusinessConflictException("error.inventory.concurrent_scope_changed");
    }
    Map<UUID, Long> observed =
        versions.stream()
            .collect(Collectors.toMap(InventoryRowVersionDto::id, InventoryRowVersionDto::version));
    if (!observed.keySet().equals(ids)) {
      throw new BusinessConflictException("orders.claim.correction.stock_versions_changed");
    }
    long located = 0L;
    for (InventoryItem row : locked) {
      OptimisticLock.check(
          row.getVersion(), observed.get(row.getId()), InventoryItem.class, row.getId());
      located = Math.addExact(located, removedFrom(row, removed));
    }
    if (located != StockQuantityFragments.total(removed)) {
      throw new BusinessConflictException("orders.claim.correction.connected_stock_required");
    }
    for (InventoryItem row : locked) {
      final List<StockQuantityFragment> remaining =
          StockQuantityFragments.subtract(row.getQuantityProvenance(), removed);
      long corrected = removedFrom(row, removed);
      if (corrected == 0L) {
        continue;
      }
      double amount = corrected / 1000.0;
      final double before = row.getAmount();
      AllocationReductions.applyPlan(
          row, AllocationReductions.resolveReductionPlan(row, null, amount, true), true);
      AllocationReductions.applyPlan(
          row, AllocationReductions.resolveReductionPlan(row, null, amount, false), false);
      orderWrites.assertRow(row);
      double after = Math.subtractExact(ScuAmounts.toQuanta(before), corrected) / 1000.0;
      LotRows.lowerKnownQuantity(rows, stockChanges, row, after, remaining);
      audit.record(
          AuditEventType.INVENTORY_QUANTITY_CORRECTED,
          row.getId(),
          InventoryAuditLabels.label(row),
          null,
          AuditDetails.of("reason", "FALSE_FACT_CORRECTED")
              .with("provision", factId)
              .with("previousAmount", before)
              .with("amount", after));
    }
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public List<StockQuantityFragment> addKnownQuantity(
      Collection<StockQuantityFragment> connected,
      long additionalQuanta,
      UUID selectedRowId,
      List<InventoryRowVersionDto> versions,
      OrderMaterialScope provisionScope,
      UUID factId) {
    orderWrites.lockEligibility();
    if (additionalQuanta <= 0L || !rowIds(connected).contains(selectedRowId)) {
      throw new BusinessConflictException("orders.claim.correction.connected_stock_required");
    }
    List<UUID> selected = List.of(selectedRowId);
    List<InventoryOrderAllocationQueries.RowLot> placements = rows.findRowLots(selected);
    orderWrites.lockRowsAndTargets(selected, List.of(provisionScope), List.of());
    orderWrites.lockStockLots(
        placements.stream()
            .map(row ->
                InventoryStockCommands.lotLockKey(
                    row.getHolderId(),
                    StockLot.key(
                        row.getMaterialId(),
                        row.getGameItemId(),
                        row.getLocationId(),
                        row.getQuality(),
                        Boolean.TRUE.equals(row.getStolen()))))
            .toList());
    InventoryItem row =
        rows.findByIdForUpdate(selectedRowId)
            .orElseThrow(() ->
                new BusinessConflictException("orders.claim.correction.connected_stock_required"));
    requireEditable(row);
    orderWrites.validateRows(selected);
    Long observed =
        versions.stream()
            .filter(version -> version.id().equals(selectedRowId))
            .map(InventoryRowVersionDto::version)
            .findFirst()
            .orElse(null);
    OptimisticLock.check(row.getVersion(), observed, InventoryItem.class, selectedRowId);
    if (removedFrom(row, connected) == 0L
        || row.getMaterial() == null
        || !row.getMaterial().getId().equals(provisionScope.materialId())) {
      throw new BusinessConflictException("orders.claim.correction.connected_stock_required");
    }
    final List<StockQuantityFragment> beforeQuantity = row.getQuantityProvenance();
    double before = row.getAmount();
    long after = Math.addExact(ScuAmounts.toQuanta(before), additionalQuanta);
    row.setAmount(after / 1000.0);
    var originalAllocation = InventoryAllocations.jobOrderSlice(row, provisionScope.orderId());
    if (originalAllocation != null) {
      originalAllocation.setAmount(
          Math.addExact(ScuAmounts.toQuanta(originalAllocation.getAmount()), additionalQuanta)
              / 1000.0);
    }
    orderWrites.assertRow(row);
    rows.saveAndFlush(row);
    audit.record(
        AuditEventType.INVENTORY_QUANTITY_CORRECTED,
        row.getId(),
        InventoryAuditLabels.label(row),
        null,
        AuditDetails.of("reason", "FALSE_FACT_CORRECTED")
            .with("provision", factId)
            .with("previousAmount", before)
            .with("amount", row.getAmount()));
    return StockQuantityFragments.subtract(row.getQuantityProvenance(), beforeQuantity);
  }

  private List<UUID> rowIds(Collection<StockQuantityFragment> quantity) {
    return quantity.isEmpty()
        ? List.of()
        : rows.findRowsWithQuantity(json.writeValueAsString(quantity));
  }

  private static long removedFrom(InventoryItem row, Collection<StockQuantityFragment> removed) {
    return Math.subtractExact(
        StockQuantityFragments.total(row.getQuantityProvenance()),
        StockQuantityFragments.total(
            StockQuantityFragments.subtract(row.getQuantityProvenance(), removed)));
  }

  private void requireEditable(InventoryItem row) {
    if (!access.canEditInventoryItem(row.getId())) {
      throw new AccessDeniedException("orders.claim.correction.stock_forbidden");
    }
  }
}
