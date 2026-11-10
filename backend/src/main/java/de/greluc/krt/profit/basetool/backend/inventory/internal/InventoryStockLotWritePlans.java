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

import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLotWritePlans;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockRow;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipQueryService;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Owns complete physical lot planning, locked-state confirmation and ordered stock execution. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class InventoryStockLotWritePlans implements StockLotWritePlans {
  private final InventoryOrderWrites orderWrites;
  private final InventoryItemRepository rows;
  private final InventoryStockPlanningReads planningReads;
  private final OrgUnitMembershipQueryService memberships;
  private final InventoryLotBatchExecutor executor;

  @Override
  public @NotNull Prepared prepareLotBatch(
      @NotNull UUID holder,
      @NotNull Collection<StockLot> readLots,
      @NotNull List<Intent> preliminaryIntents) {
    orderWrites.lockEligibility();
    List<StockLot> lots = List.copyOf(readLots);
    validate(holder, lots, preliminaryIntents);
    Set<UUID> directUnits = memberships.findDirectMembershipOrgUnitIds(holder);
    var observed = planningReads.read(holder, lots);
    var preliminary =
        new InventoryLotBatchSimulation(observed, directUnits).simulate(preliminaryIntents);
    orderWrites.prepareObservedAction(
        preliminary.resources(),
        lots.stream().map(lot -> InventoryLotLockRepository.lotKey(holder, lot.key())).toList());
    if (!lots.isEmpty()) {
      orderWrites.lockObservedRows(
          rows.findRowsInHolderLots(holder, lots.stream().map(StockLot::key).toList()));
    }
    var locked = planningReads.read(holder, lots);
    PreparedAction action =
        new PreparedAction(holder, lots, observed, locked, preliminary, directUnits);
    TransactionSynchronizationManager.registerSynchronization(action);
    return action;
  }

  @Override
  public @NotNull Confirmed confirmLotBatch(
      @NotNull Prepared prepared, @NotNull List<Intent> finalOrderedIntents, boolean dryRun) {
    if (!(prepared instanceof PreparedAction action) || !current(action) || action.confirmed) {
      throw conflict();
    }
    validate(action.holder, action.lots, finalOrderedIntents);
    var confirmed =
        new InventoryLotBatchSimulation(action.locked, action.memberships)
            .simulate(finalOrderedIntents);
    if (!confirmed.unavailable().isEmpty()
        || !action.preliminary.resources().containsAll(confirmed.resources())
        || !action.preliminary.prelude().containsAll(confirmed.prelude())) {
      throw conflict();
    }
    for (var step : confirmed.steps().entrySet()) {
      if (!Objects.equals(action.preliminary.steps().get(step.getKey()), step.getValue())) {
        throw conflict();
      }
    }
    Map<UUID, InventoryStockRowSnapshot> before = byId(action.observed);
    Map<UUID, InventoryStockRowSnapshot> after = byId(action.locked);
    for (UUID resource : confirmed.resources()) {
      if (!Objects.equals(before.get(resource), after.get(resource))) {
        throw conflict();
      }
    }
    if (!dryRun) {
      orderWrites.assertRows(confirmed.resources());
    }
    action.confirmed = true;
    ConfirmedAction result =
        new ConfirmedAction(
            action, confirmed, finalOrderedIntents.stream().map(Intent::step).toList(), dryRun);
    TransactionSynchronizationManager.registerSynchronization(result);
    return result;
  }

  @Override
  public @NotNull Outcome executePreparedLot(
      @NotNull Confirmed confirmed, int step, boolean dryRun) {
    if (!(confirmed instanceof ConfirmedAction action)
        || !current(action)
        || !current(action.prepared)
        || dryRun != action.dryRun
        || action.next >= action.steps.size()
        || action.steps.get(action.next) != step) {
      throw conflict();
    }
    if (action.next++ == 0 && !dryRun) {
      executor.markPrelude(action.prepared.holder, action.program.prelude(), action.execution);
    }
    return executor.execute(
        action.prepared.holder,
        Objects.requireNonNull(action.program.steps().get(step)),
        action.execution,
        dryRun);
  }

  /** Validates holder ownership, unique operation identities and complete declared read lots. */
  private static void validate(UUID holder, Collection<StockLot> lots, List<Intent> intents) {
    Set<String> keys = new HashSet<>();
    lots.forEach(lot -> keys.add(lot.key()));
    Set<Integer> steps = new HashSet<>();
    for (Intent intent : intents) {
      StockLot lot =
          switch (intent) {
            case SetLotAmount set -> set.lot();
            case RebookLot rebook -> {
              if (!holder.equals(rebook.request().member())) {
                throw conflict();
              }
              yield rebook.request().lot();
            }
          };
      if (!steps.add(intent.step()) || !keys.contains(lot.key())) {
        throw conflict();
      }
    }
  }

  /** Returns immutable scalar observations indexed by physical row identity. */
  private static Map<UUID, InventoryStockRowSnapshot> byId(
      InventoryStockPlanningReads.LotRows observed) {
    return observed.entries().stream()
        .collect(
            java.util.stream.Collectors.toUnmodifiableMap(
                InventoryStockRowSnapshot::getId, java.util.function.Function.identity()));
  }

  /** Reports whether a handle belongs to the currently active originating transaction. */
  private static boolean current(TransactionSynchronization handle) {
    return TransactionSynchronizationManager.isSynchronizationActive()
        && TransactionSynchronizationManager.getSynchronizations().contains(handle);
  }

  /** Returns the normal conflict for a changed or unplanned physical action. */
  private static BusinessConflictException conflict() {
    return new BusinessConflictException("error.inventory.concurrent_scope_changed");
  }

  /** The immutable observations and preliminary closed resources of one originating transaction. */
  @RequiredArgsConstructor
  private static final class PreparedAction implements Prepared, TransactionSynchronization {
    private final UUID holder;
    private final List<StockLot> lots;
    private final InventoryStockPlanningReads.LotRows observed;
    private final InventoryStockPlanningReads.LotRows locked;
    private final InventoryLotBatchProgram preliminary;
    private final Set<UUID> memberships;
    private boolean confirmed;

    @Override
    public @NotNull Map<String, List<StockRow>> lockedRows() {
      if (!current(this)) {
        throw conflict();
      }
      return locked.views();
    }
  }

  /** The confirmed ordered action and its private transaction-local execution cursor. */
  @RequiredArgsConstructor
  private static final class ConfirmedAction implements Confirmed, TransactionSynchronization {
    private final PreparedAction prepared;
    private final InventoryLotBatchProgram program;
    private final List<Integer> steps;
    private final boolean dryRun;
    private final InventoryLotBatchExecutor.Execution execution =
        new InventoryLotBatchExecutor.Execution();
    private int next;
  }
}
