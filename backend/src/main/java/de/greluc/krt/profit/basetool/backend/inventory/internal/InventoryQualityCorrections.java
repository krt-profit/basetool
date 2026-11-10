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
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryQualityCorrectionOptionDto;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryRowVersionDto;
import de.greluc.krt.profit.basetool.backend.inventory.api.OrderMaterialScope;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockChangeObserver;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockChangeReason;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockCommands.StockBookInScope;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockOfferLookup;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockOfferLookup.StockOfferFact;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockQuantityComposition;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockQuantityFragment;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockQuantityFragments;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockViewerAccess;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.kernel.ScuAmounts;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Corrects explicitly selected retained quality without changing unrelated physical evidence. */
@Service
@RequiredArgsConstructor
class InventoryQualityCorrections {
  private final InventoryItemRepository rows;
  private final tools.jackson.databind.ObjectMapper json;
  private final InventoryOrderWrites orderWrites;
  private final StockViewerAccess access;
  private final StockOfferLookup offers;
  private final StockChangeObserver stockChanges;
  private final InventoryCheckoutService checkout;
  private final AuditRecorder audit;

  /**
   * Reads permitted current row choices with the exact known receipt intersection.
   *
   * @param known original known physical intervals
   * @return permitted independently versioned rows with their affected amount
   */
  @Transactional(readOnly = true)
  public @NotNull List<InventoryQualityCorrectionOptionDto> currentRows(
      @NotNull Collection<StockQuantityFragment> known) {
    List<UUID> permitted =
        rows.findRowLots(rowIds(known)).stream()
            .filter(this::mayEdit)
            .map(InventoryOrderAllocationQueries.RowLot::getId)
            .toList();
    if (permitted.isEmpty()) {
      return List.of();
    }
    return rows.findQuantityCorrectionRows(permitted).stream()
        .filter(row ->
            access.mayEditInventoryIdentity(
                row.getUser() == null ? null : row.getUser().getId(),
                row.getOwningOrgUnit() == null ? null : row.getOwningOrgUnit().getId()))
        .filter(row -> !intersection(row, known).isEmpty())
        .map(row ->
            new InventoryQualityCorrectionOptionDto(
                row.getId(),
                row.getVersion(),
                row.getLocation().getName(),
                row.getUser().getEffectiveName(),
                row.getOwningOrgUnit() == null ? null : row.getOwningOrgUnit().getName(),
                Boolean.TRUE.equals(row.getPersonal()),
                row.getAmount(),
                row.getQuality(),
                ScuAmounts.fromQuanta(StockQuantityFragments.total(intersection(row, known)))))
        .toList();
  }

  /**
   * Applies selected current-quality corrections under the complete owning resource plan.
   *
   * @param known original known physical intervals
   * @param selectedRows explicitly selected current stock rows
   * @param quality declared truthful current quality for those intervals
   * @param versions independently observed selected row versions
   * @param provisionScope original provision order and material
   * @param factId connected historical provision identity for audit
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void correct(
      @NotNull Collection<StockQuantityFragment> known,
      @NotNull List<UUID> selectedRows,
      int quality,
      @NotNull List<InventoryRowVersionDto> versions,
      @NotNull OrderMaterialScope provisionScope,
      UUID factId) {
    if (selectedRows.isEmpty()) {
      return;
    }
    orderWrites.lockEligibility();
    Set<UUID> selected = new TreeSet<>(selectedRows);
    if (selected.size() != selectedRows.size() || !rowIds(known).containsAll(selected)) {
      throw connectedConflict();
    }
    rows.findRowLots(selected).forEach(this::requireEditable);
    Map<UUID, InventoryItem> sources =
        rows.findQuantityCorrectionRows(selected).stream()
            .collect(Collectors.toMap(InventoryItem::getId, Function.identity()));
    if (!sources.keySet().equals(selected)) {
      throw connectedConflict();
    }
    Map<UUID, StockOfferFact> offerFacts = offers.offerFacts(selected);
    List<PlannedRow> plan = new ArrayList<>();
    List<StockBookInScope> bookings = new ArrayList<>();
    for (UUID id : selected) {
      InventoryItem row = Objects.requireNonNull(sources.get(id));
      if (row.getMaterial() == null
          || !row.getMaterial().getId().equals(provisionScope.materialId())) {
        throw connectedConflict();
      }
      StockQuantityComposition full = row.getQuantityComposition();
      List<StockQuantityFragment> changed = intersection(row, known);
      if (changed.isEmpty()) {
        throw connectedConflict();
      }
      StockOfferFact offer = offerFacts.getOrDefault(id, StockOfferFact.NONE);
      boolean merge =
          row.countsWholeUnits()
              && !Objects.equals(row.getQuality(), quality)
              && !(offer.offered() && full.totalQuanta() == StockQuantityFragments.total(changed));
      plan.add(new PlannedRow(row, full, changed, offer));
      bookings.add(
          new StockBookInScope(
              row.getUser().getId(),
              new StockLot(
                  row.getMaterial(),
                  null,
                  row.getLocation(),
                  quality,
                  Boolean.TRUE.equals(row.getStolen())),
              row.getJobOrderAllocations().stream()
                  .map(InventoryJobOrderAllocation::getJobOrderId)
                  .toList(),
              Boolean.TRUE.equals(row.getPersonal()),
              row.getOwningOrgUnit() == null ? null : row.getOwningOrgUnit().getId(),
              merge));
    }
    orderWrites.prepareWritesWithAuthority(
        selected,
        bookings,
        List.of(provisionScope),
        current -> validateAuthorityAndVersions(current, sources, versions));
    Map<UUID, StockOfferFact> currentOffers = offers.offerFacts(selected);
    for (PlannedRow source : plan) {
      if (!source.full().equals(source.row().getQuantityComposition())
          || !source.changed().equals(intersection(source.row(), known))
          || !source
              .offer()
              .equals(currentOffers.getOrDefault(source.row().getId(), StockOfferFact.NONE))) {
        throw concurrentConflict();
      }
    }
    List<QualityChange> changes = new ArrayList<>();
    for (PlannedRow source : plan) {
      if (!Objects.equals(source.row().getQuality(), quality)) {
        changes.add(change(source, quality));
      }
    }
    Map<MergeIdentity, InventoryItem> mergeTargets = new LinkedHashMap<>();
    for (QualityChange change : changes) {
      InventoryItem row = change.corrected();
      if (row.countsWholeUnits()
          && !currentOffers.getOrDefault(row.getId(), StockOfferFact.NONE).offered()) {
        mergeTargets.putIfAbsent(MergeIdentity.of(row), row);
      }
    }
    Map<MergeIdentity, InventoryItem> survivors = new LinkedHashMap<>();
    mergeTargets.forEach(
        (identity, row) -> survivors.put(identity, checkout.mergeStockIfRequested(row, false)));
    for (QualityChange change : changes) {
      InventoryItem target =
          survivors.getOrDefault(MergeIdentity.of(change.corrected()), change.corrected());
      recordAudit(change, target, quality, factId);
    }
  }

  /** Changes one validated row and retains its source facts for the final target audit. */
  private QualityChange change(PlannedRow planned, int quality) {
    InventoryItem source = planned.row();
    Integer previousQuality = source.getQuality();
    StockQuantityComposition selected = StockQuantityComposition.known(planned.changed());
    long changed = selected.totalQuanta();
    InventoryItem corrected = source;
    if (changed == planned.full().totalQuanta()) {
      source.setQuality(quality);
      orderWrites.assertRow(source);
      rows.saveAndFlush(source);
    } else {
      StockQuantityComposition remaining =
          InventoryQuantityComposition.subtract(planned.full(), selected);
      if (Math.subtractExact(planned.full().totalQuanta(), remaining.totalQuanta()) != changed) {
        throw connectedConflict();
      }
      double amount = ScuAmounts.fromQuanta(changed);
      corrected = LotRows.exactPart(source, selected, LotRows.Placement.of(source));
      corrected.setQuality(quality);
      transferSlices(source, corrected, amount);
      orderWrites.assertRow(corrected);
      rows.save(corrected);
      orderWrites.assertRow(source);
      LotRows.lowerExactQuantity(
          rows,
          stockChanges,
          source,
          ScuAmounts.fromQuanta(remaining.totalQuanta()),
          remaining,
          StockChangeReason.CORRECTION);
    }
    return new QualityChange(
        source.getId(), InventoryAuditLabels.label(source), previousQuality, changed, corrected);
  }

  /** Links each corrected source portion to its actual final physical survivor. */
  private void recordAudit(QualityChange change, InventoryItem target, int quality, UUID factId) {
    audit.record(
        AuditEventType.INVENTORY_QUALITY_CORRECTED,
        change.sourceId(),
        change.sourceLabel(),
        null,
        AuditDetails.of("reason", "FALSE_FACT_CORRECTED")
            .with("provision", factId)
            .with("previousQuality", change.previousQuality())
            .with("quality", quality)
            .with("amount", ScuAmounts.fromQuanta(change.quanta()))
            .with("correctedRow", target.getId()));
  }

  /** Moves the owning default reduction shares and their delivery markers to an exact split. */
  private static void transferSlices(InventoryItem source, InventoryItem corrected, double amount) {
    Map<UUID, Double> jobs = AllocationReductions.resolveReductionPlan(source, null, amount, true);
    Map<UUID, Double> missions =
        AllocationReductions.resolveReductionPlan(source, null, amount, false);
    jobs.forEach((order, quantity) ->
        InventoryAllocations.addJobOrder(
            corrected,
            order,
            quantity,
            Boolean.TRUE.equals(
                Objects.requireNonNull(InventoryAllocations.jobOrderSlice(source, order))
                    .getDelivered())));
    missions.forEach(
        (mission, quantity) -> InventoryAllocations.addMission(corrected, mission, quantity));
    AllocationReductions.applyPlan(source, jobs, true);
    AllocationReductions.applyPlan(source, missions, false);
  }

  /** Locates proved connected intervals without using storage identity as a physical origin. */
  private List<UUID> rowIds(Collection<StockQuantityFragment> known) {
    return known.isEmpty() ? List.of() : rows.findRowsWithQuantity(json.writeValueAsString(known));
  }

  /** Selects only actual overlap while preserving the current physical interval order. */
  private static List<StockQuantityFragment> intersection(
      InventoryItem row, Collection<StockQuantityFragment> known) {
    List<StockQuantityFragment> current = row.getQuantityProvenance();
    return StockQuantityFragments.subtract(
        current, StockQuantityFragments.subtract(current, known));
  }

  /** Rechecks full current authority before either client or preplanning entity versions. */
  private void validateAuthorityAndVersions(
      List<InventoryOrderAllocationQueries.RowLot> current,
      Map<UUID, InventoryItem> sources,
      List<InventoryRowVersionDto> versions) {
    current.forEach(this::requireEditable);
    Map<UUID, InventoryOrderAllocationQueries.RowLot> facts =
        current.stream()
            .collect(
                Collectors.toMap(
                    InventoryOrderAllocationQueries.RowLot::getId, Function.identity()));
    Map<UUID, Long> observed = new HashMap<>();
    for (InventoryRowVersionDto version : versions) {
      if (version.version() == null
          || observed.putIfAbsent(version.id(), version.version()) != null) {
        throw versionConflict();
      }
    }
    if (!observed.keySet().equals(sources.keySet())
        || !facts.keySet().containsAll(sources.keySet())) {
      throw versionConflict();
    }
    sources.forEach((id, row) -> {
      InventoryOrderAllocationQueries.RowLot actual = Objects.requireNonNull(facts.get(id));
      OptimisticLock.checkRequired(
          actual.getVersion(), Objects.requireNonNull(observed.get(id)), InventoryItem.class, id);
      OptimisticLock.check(actual.getVersion(), row.getVersion(), InventoryItem.class, id);
    });
  }

  /** Applies both ordinary Inventory action gates to freshly read scalar ownership. */
  private boolean mayEdit(InventoryOrderAllocationQueries.RowLot row) {
    return access.mayEditInventoryIdentity(row.getHolderId(), row.getOwningOrgUnitId());
  }

  /** Refuses unavailable current Inventory authority before client-version disclosure. */
  private void requireEditable(InventoryOrderAllocationQueries.RowLot row) {
    if (!mayEdit(row)) {
      throw new AccessDeniedException("orders.claim.correction.stock_forbidden");
    }
  }

  /** Returns the public conflict for missing retained connected physical evidence. */
  private static BusinessConflictException connectedConflict() {
    return new BusinessConflictException("orders.claim.correction.connected_stock_required");
  }

  /** Returns the public conflict for an incomplete or duplicate selected version set. */
  private static BusinessConflictException versionConflict() {
    return new BusinessConflictException("orders.claim.correction.stock_versions_changed");
  }

  /** Returns the public conflict for changed preplanned physical facts. */
  private static BusinessConflictException concurrentConflict() {
    return new BusinessConflictException("error.inventory.concurrent_scope_changed");
  }

  /** The original source portion and its corrected carrier before mandatory merge fanout. */
  private record QualityChange(
      UUID sourceId,
      String sourceLabel,
      @Nullable Integer previousQuality,
      long quanta,
      InventoryItem corrected) {}

  /** One selected source's complete physical evidence and ordinary merge-policy observations. */
  private record PlannedRow(
      InventoryItem row,
      StockQuantityComposition full,
      List<StockQuantityFragment> changed,
      StockOfferFact offer) {}

  /** The physical merge identity, independent of allocation targets. */
  private record MergeIdentity(UUID holder, String lot, boolean personal, @Nullable UUID pool) {
    /** Reads the current complete merge identity of a corrected row. */
    static MergeIdentity of(InventoryItem row) {
      return new MergeIdentity(
          row.getUser().getId(),
          StockLot.key(
              row.getMaterial() == null ? null : row.getMaterial().getId(),
              row.getGameItem() == null ? null : row.getGameItem().getId(),
              row.getLocation().getId(),
              row.getQuality(),
              Boolean.TRUE.equals(row.getStolen())),
          Boolean.TRUE.equals(row.getPersonal()),
          row.getOwningOrgUnit() == null ? null : row.getOwningOrgUnit().getId());
    }
  }
}
