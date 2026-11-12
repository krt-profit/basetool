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

import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolRestoreResult;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLotWritePlans;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockQuantityComposition;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockQuantityEvidence;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockRow;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipQueryService;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
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
  private final InventoryStockPlanningReads planningReads;
  private final OrgUnitMembershipQueryService memberships;
  private final InventoryLotBatchExecutor executor;
  private final InventoryPoolRestoreFacts restoreFacts;
  private final InventoryPoolRestoreDecisions restoreDecisions;
  private final InventoryPoolRestorer restorer;
  private final InventoryLotRestoreProofs proofs;

  @Override
  public @NotNull Prepared prepareLotBatch(
      @NotNull UUID holder,
      @NotNull Collection<StockLot> readLots,
      @NotNull List<EntryRestore> declaredHistoricalEntries,
      @NotNull List<EntryRestore> preliminaryEntryRestores,
      @NotNull List<Intent> preliminaryIntents) {
    return prepareLotBatch(
        holder,
        readLots,
        declaredHistoricalEntries,
        preliminaryEntryRestores,
        preliminaryIntents,
        UnaryOperator.identity());
  }

  @Override
  public @NotNull Prepared prepareLotBatch(
      @NotNull UUID holder,
      @NotNull Collection<StockLot> readLots,
      @NotNull List<EntryRestore> declaredHistoricalEntries,
      @NotNull List<EntryRestore> preliminaryEntryRestores,
      @NotNull List<Intent> preliminaryIntents,
      @NotNull UnaryOperator<Selection> afterResourceWaits) {
    List<StockLot> lots = List.copyOf(readLots);
    List<EntryRestore> declared = List.copyOf(declaredHistoricalEntries);
    Selection original = new Selection(preliminaryEntryRestores, preliminaryIntents);
    final Form form =
        validate(holder, lots, declared, original.entryRestores(), original.intents());
    orderWrites.lockEligibility();
    Set<UUID> directUnits = memberships.findDirectMembershipOrgUnitIds(holder);
    var observed = planningReads.read(holder, lots);
    var observedFacts = restoreFacts.read(namedRows(observed, declared));
    var prefix =
        InventoryPoolRestoreProgram.plan(original.entryRestores(), observedFacts, restoreDecisions);
    var preliminary =
        simulate(holder, observed, observedFacts, prefix, directUnits, original.intents());
    var interest = proofs.interest(prefix, preliminary);
    Set<UUID> overlaps = proofs.overlapping(interest);
    Set<UUID> named = new HashSet<>(observedFacts.keySet());
    named.addAll(overlaps);
    var allObservedFacts = new HashMap<>(restoreFacts.read(named));
    allObservedFacts.putAll(observedFacts);
    Set<Long> lotKeys = lotKeys(holder, lots, declared, prefix, allObservedFacts);
    Set<UUID> resources = resources(prefix, preliminary);
    var evidence = proofs.additions(prefix, preliminary);
    orderWrites.prepareRestorationResources(resources, prefix.targetScopes(), evidence);
    Selection selected = narrow(original, afterResourceWaits);
    if (form == Form.RESTORE) {
      if (!overlaps.equals(proofs.overlapping(interest))) {
        throw conflict();
      }
      var afterEvidence = planningReads.read(holder, lots);
      named.addAll(namedRows(afterEvidence, declared));
      var afterEvidenceFacts = restoreFacts.read(named);
      var afterEvidencePrefix =
          InventoryPoolRestoreProgram.plan(
              selected.entryRestores(), afterEvidenceFacts, restoreDecisions);
      var afterEvidenceProgram =
          simulate(
              holder,
              afterEvidence,
              afterEvidenceFacts,
              afterEvidencePrefix,
              directUnits,
              selected.intents());
      var currentEvidence = proofs.additions(afterEvidencePrefix, afterEvidenceProgram);
      if (!resources.containsAll(resources(afterEvidencePrefix, afterEvidenceProgram))
          || !evidence.containsAll(currentEvidence)
          || !lotKeys.containsAll(
              lotKeys(holder, lots, declared, afterEvidencePrefix, afterEvidenceFacts))) {
        throw conflict();
      }
      orderWrites.prepareRestorationResources(
          resources(afterEvidencePrefix, afterEvidenceProgram),
          afterEvidencePrefix.targetScopes(),
          currentEvidence);
    }
    orderWrites.lockStockLots(lotKeys);
    selected = narrow(selected, afterResourceWaits);

    var afterLots = planningReads.read(holder, lots);
    named.addAll(namedRows(afterLots, declared));
    var afterLotFacts = restoreFacts.read(named);
    var afterLotPrefix =
        InventoryPoolRestoreProgram.plan(selected.entryRestores(), afterLotFacts, restoreDecisions);
    var afterLotProgram =
        simulate(holder, afterLots, afterLotFacts, afterLotPrefix, directUnits, selected.intents());
    if (!overlaps.equals(proofs.overlapping(interest))
        || !resources.containsAll(resources(afterLotPrefix, afterLotProgram))
        || !evidence.containsAll(proofs.additions(afterLotPrefix, afterLotProgram))) {
      throw conflict();
    }
    orderWrites.requireRestorationEvidence(proofs.additions(afterLotPrefix, afterLotProgram));
    orderWrites.revalidateObservedResources(
        resources(afterLotPrefix, afterLotProgram),
        lotKeys(holder, lots, declared, afterLotPrefix, afterLotFacts),
        afterLotPrefix.targetScopes());
    orderWrites.lockObservedRows(named);
    var locked = planningReads.read(holder, lots);
    named.addAll(namedRows(locked, declared));
    var lockedFacts = restoreFacts.read(named);
    PreparedAction action =
        new PreparedAction(
            holder,
            lots,
            form,
            declared,
            selected.entryRestores(),
            selected.intents(),
            Map.copyOf(allObservedFacts),
            locked,
            lockedFacts,
            prefix,
            preliminary,
            directUnits,
            evidence,
            interest,
            overlaps);
    TransactionSynchronizationManager.registerSynchronization(action);
    return action;
  }

  @Override
  public @NotNull Confirmed confirmLotBatch(
      @NotNull Prepared prepared,
      @NotNull List<EntryRestore> finalEntryRestores,
      @NotNull List<Intent> finalOrderedIntents,
      boolean dryRun) {
    if (!(prepared instanceof PreparedAction action) || !current(action) || action.confirmed) {
      throw conflict();
    }
    Form form =
        validate(
            action.holder,
            action.lots,
            action.entryRequests,
            finalEntryRestores,
            finalOrderedIntents);
    if ((form != Form.EMPTY && form != action.form)
        || !orderedSubset(action.acceptedEntryRequests, finalEntryRestores)
        || !orderedSubset(action.intents, finalOrderedIntents)) {
      throw conflict();
    }
    var prefix =
        InventoryPoolRestoreProgram.plan(finalEntryRestores, action.lockedFacts, restoreDecisions);
    var confirmed =
        simulate(
            action.holder,
            action.locked,
            action.lockedFacts,
            prefix,
            action.memberships,
            finalOrderedIntents);
    if (!confirmed.unavailable().isEmpty()
        || !resources(action.prefix, action.preliminary).containsAll(resources(prefix, confirmed))
        || !action.preliminary.prelude().containsAll(confirmed.prelude())
        || !action.evidence.containsAll(proofs.additions(prefix, confirmed))
        || !action.overlaps.equals(proofs.overlapping(action.interest))) {
      throw conflict();
    }
    orderWrites.requireRestorationEvidence(proofs.additions(prefix, confirmed));
    for (var step : confirmed.steps().entrySet()) {
      if (!Objects.equals(action.preliminary.steps().get(step.getKey()), step.getValue())) {
        throw conflict();
      }
    }
    for (var step : prefix.steps()) {
      if (step.decision().changes()
          && action.prefix.steps().stream().noneMatch(step::sameSelection)) {
        throw conflict();
      }
    }
    for (UUID resource : resources(prefix, confirmed)) {
      if (!Objects.equals(action.observedFacts.get(resource), action.lockedFacts.get(resource))) {
        throw conflict();
      }
    }
    var startingRows =
        new InventoryLotProspectiveRows(action.locked, quantities(action.lockedFacts));
    startingRows.applyEntryPrefix(action.holder, prefix, action.locked.rows().keySet());
    if (form == Form.RESTORE && !resources(prefix, confirmed).isEmpty()) {
      proofs.requireUnique(
          action.lockedFacts, prefix, confirmed, startingRows.quantities().keySet());
    }
    if (!dryRun) {
      orderWrites.assertRows(resources(prefix, confirmed));
      orderWrites.assertObservedTargets(prefix.targetOrders(), prefix.targetScopes());
    }
    Map<String, StockQuantityComposition> initial = new HashMap<>();
    for (var lot : action.lots) {
      initial.put(
          lot.key(),
          InventoryQuantityComposition.canonical(
              startingRows.lot(lot).stream()
                  .flatMap(row -> startingRows.requireQuantity(row).parts().stream())
                  .toList()));
    }
    action.confirmed = true;
    ConfirmedAction result =
        new ConfirmedAction(
            action,
            prefix,
            confirmed,
            finalOrderedIntents.stream().map(Intent::step).toList(),
            dryRun,
            new InventoryLotBatchExecutor.Execution(initial, confirmed.prelude()));
    result.prefixExecuted = prefix.steps().isEmpty();
    TransactionSynchronizationManager.registerSynchronization(result);
    return result;
  }

  @Override
  public @NotNull List<PoolRestoreResult> executePreparedEntryRestores(
      @NotNull Confirmed confirmed) {
    if (!(confirmed instanceof ConfirmedAction action)
        || !current(action)
        || !current(action.prepared)
        || action.prefixExecuted
        || action.next != 0) {
      throw conflict();
    }
    action.prefixExecuted = true;
    return action.dryRun ? action.prefix.results() : restorer.restorePrepared(action.prefix);
  }

  @Override
  public @NotNull Outcome executePreparedLot(
      @NotNull Confirmed confirmed, int step, boolean dryRun) {
    if (!(confirmed instanceof ConfirmedAction action)
        || !current(action)
        || !current(action.prepared)
        || !action.prefixExecuted
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

  /** Simulates the exact supported operation form from complete immutable quantity facts. */
  private static InventoryLotBatchProgram simulate(
      UUID holder,
      InventoryStockPlanningReads.LotRows observed,
      Map<UUID, Optional<InventoryPoolRestoreFacts.Entry>> facts,
      InventoryPoolRestoreProgram prefix,
      Set<UUID> memberships,
      List<Intent> intents) {
    return new InventoryLotBatchSimulation(observed, memberships, quantities(facts), holder, prefix)
        .simulate(intents);
  }

  /** Returns complete present quantity proof without treating absence as an empty composition. */
  private static Map<UUID, StockQuantityComposition> quantities(
      Map<UUID, Optional<InventoryPoolRestoreFacts.Entry>> facts) {
    Map<UUID, StockQuantityComposition> result = new HashMap<>();
    facts.forEach((id, value) -> value.ifPresent(entry -> result.put(id, entry.quantity())));
    return Map.copyOf(result);
  }

  /** Collects physical read observations and every explicit historical row identity. */
  private static Set<UUID> namedRows(
      InventoryStockPlanningReads.LotRows lots, List<EntryRestore> entries) {
    Set<UUID> named = new HashSet<>();
    lots.entries().forEach(row -> named.add(row.getId()));
    entries.forEach(entry -> named.add(entry.request().entryId()));
    return named;
  }

  /** Combines actual mutations without attaching eligibility to observation-only resources. */
  private static Set<UUID> resources(
      InventoryPoolRestoreProgram prefix, InventoryLotBatchProgram program) {
    Set<UUID> result = new HashSet<>(prefix.resources());
    result.addAll(program.resources());
    return Set.copyOf(result);
  }

  /** Collects all current, historical and explicit destination lots before physical locks. */
  private Set<Long> lotKeys(
      UUID holder,
      List<StockLot> lots,
      List<EntryRestore> declared,
      InventoryPoolRestoreProgram prefix,
      Map<UUID, Optional<InventoryPoolRestoreFacts.Entry>> facts) {
    Set<Long> keys = new HashSet<>(prefix.lotKeys());
    keys.addAll(proofs.lots(facts));
    for (var entry : declared) {
      var expected = entry.request().expected();
      if (expected != null) {
        keys.add(InventoryLotLockRepository.lotKey(expected.holder(), expected.lotKey()));
      }
      var target = entry.request().target();
      if (target != null) {
        keys.add(InventoryLotLockRepository.lotKey(target.holder(), target.lotKey()));
      }
    }
    lots.forEach(lot -> keys.add(InventoryLotLockRepository.lotKey(holder, lot.key())));
    return Set.copyOf(keys);
  }

  /** Validates the two actual producer forms and unique names before acquiring any lock. */
  private static Form validate(
      UUID holder,
      Collection<StockLot> lots,
      List<EntryRestore> declared,
      List<EntryRestore> accepted,
      List<Intent> intents) {
    if (!orderedSubset(declared, accepted)) {
      throw conflict();
    }
    Set<String> keys = new HashSet<>();
    lots.forEach(lot -> keys.add(lot.key()));
    Set<Integer> steps = new HashSet<>();
    Set<UUID> named = new HashSet<>();
    for (var entry : declared) {
      if (!steps.add(entry.step()) || !named.add(entry.request().entryId())) {
        throw conflict();
      }
    }
    boolean restore = !declared.isEmpty();
    boolean ordinary = false;
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
            case RestoreLot restored -> {
              if (restored.inversePoolMoves().stream()
                  .anyMatch(move ->
                      !holder.equals(move.member())
                          || !restored.lot().key().equals(move.lot().key()))) {
                throw conflict();
              }
              yield restored.lot();
            }
          };
      restore |= intent instanceof RestoreLot;
      ordinary |= !(intent instanceof RestoreLot);
      if (!steps.add(intent.step()) || !keys.contains(lot.key())) {
        throw conflict();
      }
    }
    if (restore && ordinary) {
      throw conflict();
    }
    return restore ? Form.RESTORE : ordinary ? Form.ORDINARY : Form.EMPTY;
  }

  /** Admits only an unchanged ordered subset of the preceding read-only policy selection. */
  private static @NotNull Selection narrow(
      @NotNull Selection current, @NotNull UnaryOperator<Selection> afterResourceWaits) {
    Selection selected = afterResourceWaits.apply(current);
    if (selected == null
        || !orderedSubset(current.entryRestores(), selected.entryRestores())
        || !orderedSubset(current.intents(), selected.intents())) {
      throw conflict();
    }
    return selected;
  }

  /** Retains the originally declared relative order and exact immutable operation values. */
  private static <T> boolean orderedSubset(List<T> declared, List<T> selected) {
    int cursor = 0;
    for (T value : selected) {
      while (cursor < declared.size() && !declared.get(cursor).equals(value)) {
        cursor++;
      }
      if (cursor == declared.size()) {
        return false;
      }
      cursor++;
    }
    return true;
  }

  /** Reports whether a handle belongs to the currently active originating transaction. */
  private static boolean current(TransactionSynchronization handle) {
    return TransactionSynchronizationManager.isSynchronizationActive()
        && TransactionSynchronizationManager.getSynchronizations().contains(handle);
  }

  /** Returns the normal conflict for an altered or unplanned physical action. */
  private static BusinessConflictException conflict() {
    return new BusinessConflictException("error.inventory.concurrent_scope_changed");
  }

  /** Supported ordinary writes, historical restoration, or a neutral empty action. */
  private enum Form {
    /** No physical operation. */
    EMPTY,
    /** The ordinary quantity and rebooking batch. */
    ORDINARY,
    /** The historical entry prefix and personal lot groups. */
    RESTORE
  }

  /** Complete preliminary observations and the fresh locked calling-policy facts. */
  @RequiredArgsConstructor
  private static final class PreparedAction implements Prepared, TransactionSynchronization {
    private final UUID holder;
    private final List<StockLot> lots;
    private final Form form;
    private final List<EntryRestore> entryRequests;
    private final List<EntryRestore> acceptedEntryRequests;
    private final List<Intent> intents;
    private final Map<UUID, Optional<InventoryPoolRestoreFacts.Entry>> observedFacts;
    private final InventoryStockPlanningReads.LotRows locked;
    private final Map<UUID, Optional<InventoryPoolRestoreFacts.Entry>> lockedFacts;
    private final InventoryPoolRestoreProgram prefix;
    private final InventoryLotBatchProgram preliminary;
    private final Set<UUID> memberships;
    private final Set<InventoryQuantityEvidenceLockRepository.Identity> evidence;
    private final List<StockQuantityEvidence> interest;
    private final Set<UUID> overlaps;
    private boolean confirmed;

    @Override
    public @NotNull Map<String, List<StockRow>> lockedRows() {
      if (!current(this)) {
        throw conflict();
      }
      return locked.views();
    }

    @Override
    public @NotNull Map<UUID, Optional<EntryState>> lockedEntries() {
      if (!current(this)) {
        throw conflict();
      }
      Map<UUID, Optional<EntryState>> result = new HashMap<>();
      entryRequests.forEach(entry -> {
        UUID id = entry.request().entryId();
        result.put(
            id,
            Objects.requireNonNull(lockedFacts.get(id))
                .map(value -> new EntryState(value.sharedState())));
      });
      return Map.copyOf(result);
    }
  }

  /** Fixed ordered execution and originating-transaction resource ownership. */
  @RequiredArgsConstructor
  private static final class ConfirmedAction implements Confirmed, TransactionSynchronization {
    private final PreparedAction prepared;
    private final InventoryPoolRestoreProgram prefix;
    private final InventoryLotBatchProgram program;
    private final List<Integer> steps;
    private final boolean dryRun;
    private final InventoryLotBatchExecutor.Execution execution;
    private boolean prefixExecuted;
    private int next;
  }
}
