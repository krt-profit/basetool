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

import de.greluc.krt.profit.basetool.backend.inventory.api.RebookCommands;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockChangeEffects;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockCommands;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLotWritePlans;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipQueryService;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Executes confirmed selections through the existing owning stock and rebooking commands. */
@Component
@RequiredArgsConstructor
final class InventoryLotBatchExecutor {
  private final StockCommands stock;
  private final RebookCommands rebooks;
  private final InventoryStockPlanningReads reads;
  private final OrgUnitMembershipQueryService memberships;

  /** Applies the confirmed stolen prelude once, after the calling mass-change decision. */
  void markPrelude(UUID holder, List<InventoryLotBatchProgram.Mark> prelude, Execution execution) {
    for (var mark : prelude) {
      UUID source = execution.id(mark.source());
      var observed = stockRow(holder, source);
      if (observed.getOffer().offered()
          || (mark.whole()
              && BigDecimal.valueOf(observed.getAmount()).compareTo(mark.amount()) != 0)) {
        throw conflict();
      }
      StockLot target =
          new StockLot(
              observed.getMaterial(),
              observed.getGameItem(),
              observed.getLocation(),
              observed.getQuality(),
              mark.stolen());
      requireMerge(
          holder,
          target,
          observed.getPersonal(),
          observed.getOwningOrgUnit() == null ? null : observed.getOwningOrgUnit().getId(),
          mark.victims(),
          execution);
      execution.bind(
          mark.target(),
          stock.markStolen(
              holder, source, mark.stolen(), mark.whole() ? null : mark.amount().doubleValue()));
      requireDeleted(holder, target, mark.victims(), execution);
    }
  }

  /** Runs one exact ordered physical step, or asks the ordinary owning commands in dry mode. */
  StockLotWritePlans.Outcome execute(
      UUID holder, InventoryLotBatchProgram.Step step, Execution execution, boolean dryRun) {
    if (!dryRun && step.intent() instanceof StockLotWritePlans.SetLotAmount set) {
      requireBookOutPrefix(holder, set.lot(), step.mutations(), execution);
    }
    StockChangeEffects effects = StockChangeEffects.NONE;
    RebookCommands.RebookRefusal refusal = null;
    for (var mutation : step.mutations()) {
      switch (mutation) {
        case InventoryLotBatchProgram.BookIn booking -> {
          if (!dryRun) {
            requireMerge(holder, booking.lot(), true, null, booking.victims(), execution);
            execution.bind(
                booking.target(),
                stock.bookIn(holder, booking.lot(), booking.amount().doubleValue()));
            requireDeleted(holder, booking.lot(), booking.victims(), execution);
          }
        }
        case InventoryLotBatchProgram.BookOut take -> {
          if (!dryRun) {
            var changed =
                stock.bookOutRow(holder, execution.id(take.source()), take.amount().doubleValue());
            effects =
                new StockChangeEffects(
                    effects.reduced() + changed.reduced(), effects.removed() + changed.removed());
          }
        }
        case InventoryLotBatchProgram.Rebooking rebooking ->
            refusal = rebook(holder, step.intent().step(), rebooking, execution, dryRun);
        case InventoryLotBatchProgram.Mark _ ->
            throw new IllegalStateException("Markers belong to the prelude");
      }
    }
    String key =
        switch (step.intent()) {
          case StockLotWritePlans.SetLotAmount set -> set.lot().key();
          case StockLotWritePlans.RebookLot rebook -> rebook.request().lot().key();
        };
    execution.touched.add(key);
    return new StockLotWritePlans.Outcome(refusal, effects);
  }

  /**
   * Rechecks actual creation timestamps and the canonical free prefix once before a lot deduction.
   */
  private void requireBookOutPrefix(
      UUID holder,
      StockLot lot,
      List<InventoryLotBatchProgram.Mutation> mutations,
      Execution execution) {
    var expected =
        mutations.stream()
            .filter(InventoryLotBatchProgram.BookOut.class::isInstance)
            .map(InventoryLotBatchProgram.BookOut.class::cast)
            .map(take -> new PrefixTake(execution.id(take.source()), take.amount()))
            .toList();
    if (expected.isEmpty()) {
      return;
    }
    BigDecimal total =
        expected.stream().map(PrefixTake::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    var sources =
        Objects.requireNonNull(reads.read(holder, List.of(lot)).rows().get(lot.key())).stream()
            .map(row ->
                new InventoryStockDecisions.DrawSource<>(
                    row.getId(),
                    BigDecimal.valueOf(row.getAmount()),
                    BigDecimal.valueOf(row.jobOrderReserved()),
                    BigDecimal.valueOf(row.missionReserved()),
                    BigDecimal.valueOf(row.donationReserved()),
                    row.getStolen(),
                    row.getOffer().offered()))
            .toList();
    var actual =
        InventoryStockDecisions.freeDraws(
                sources, total, InventoryItem.countsWholeUnits(lot.material(), lot.gameItem()))
            .stream()
            .map(take -> new PrefixTake(take.key(), take.amount()))
            .toList();
    if (!expected.equals(actual)) {
      throw conflict();
    }
  }

  /** Preserves ordinary refusal and dry-run dependency behavior while validating actual takes. */
  private @Nullable RebookCommands.RebookRefusal rebook(
      UUID holder,
      int step,
      InventoryLotBatchProgram.Rebooking expected,
      Execution execution,
      boolean dryRun) {
    String key = expected.request().lot().key();
    if (execution.refused.containsKey(key)) {
      return execution.refused.get(key);
    }
    if (dryRun && execution.touched.contains(key)) {
      return null;
    }
    if (!dryRun) {
      var observed = reads.read(holder, List.of(expected.request().lot()));
      var actual =
          new InventoryLotBatchSimulation(
                  observed, memberships.findDirectMembershipOrgUnitIds(holder))
              .simulate(List.of(new StockLotWritePlans.RebookLot(step, expected.request())));
      var selected =
          (InventoryLotBatchProgram.Rebooking)
              Objects.requireNonNull(actual.steps().get(step)).mutations().getFirst();
      if (selected.refusal() != expected.refusal()
          || !normalized(expected.takes(), execution)
              .equals(normalized(selected.takes(), new Execution()))) {
        throw conflict();
      }
    }
    var outcome = rebooks.rebook(expected.request(), dryRun);
    if (!dryRun && outcome.refusal() != expected.refusal()) {
      throw conflict();
    }
    if (outcome.refusal() != null) {
      execution.refused.put(key, outcome.refusal());
      return outcome.refusal();
    }
    if (!dryRun) {
      if (outcome.targetRows().size() != expected.takes().size()) {
        throw conflict();
      }
      for (int index = 0; index < outcome.targetRows().size(); index++) {
        execution.bind(expected.takes().get(index).target(), outcome.targetRows().get(index));
      }
      Set<UUID> allowed = new HashSet<>(outcome.targetRows());
      expected
          .takes()
          .forEach(take -> {
            allowed.add(execution.id(take.source()));
            take.victims().forEach(victim -> allowed.add(execution.id(victim)));
          });
      if (outcome.changes().stream()
          .anyMatch(change ->
              (change.before() != null && !allowed.contains(change.before().id()))
                  || (change.after() != null && !allowed.contains(change.after().id())))) {
        throw conflict();
      }
      requireDeleted(
          holder,
          expected.request().lot(),
          expected.takes().stream()
              .flatMap(take -> take.victims().stream())
              .collect(java.util.stream.Collectors.toUnmodifiableSet()),
          execution);
    }
    return null;
  }

  /**
   * Normalizes source and merge selection to actual row identities for immediate execution proof.
   */
  private static List<Take> normalized(
      List<InventoryLotBatchProgram.Transfer> takes, Execution execution) {
    Map<InventoryLotBatchProgram.RowKey, Integer> local = new HashMap<>();
    List<Take> result = new java.util.ArrayList<>();
    for (var take : takes) {
      result.add(
          new Take(
              binding(take.source(), execution, local),
              take.amount(),
              take.victims().stream()
                  .map(key -> binding(key, execution, local))
                  .collect(java.util.stream.Collectors.toUnmodifiableSet())));
      if (take.target() instanceof InventoryLotBatchProgram.Created
          && !execution.created.containsKey(take.target())) {
        local.put(take.target(), local.size());
      }
    }
    return List.copyOf(result);
  }

  /** Resolves an earlier action result or an earlier take of the same owning command. */
  private static Binding binding(
      InventoryLotBatchProgram.RowKey key,
      Execution execution,
      Map<InventoryLotBatchProgram.RowKey, Integer> local) {
    if (key instanceof InventoryLotBatchProgram.Existing existing) {
      return new Real(existing.id());
    }
    UUID id = execution.created.get(key);
    if (id != null) {
      return new Real(id);
    }
    Integer index = local.get(key);
    if (index == null) {
      throw conflict();
    }
    return new Prospective(index);
  }

  /** Checks the current offer-sensitive destination set without taking any additional guards. */
  private void requireMerge(
      UUID holder,
      StockLot lot,
      boolean personal,
      @Nullable UUID unit,
      Set<InventoryLotBatchProgram.RowKey> victims,
      Execution execution) {
    if (!InventoryItem.countsWholeUnits(lot.material(), lot.gameItem())) {
      if (!victims.isEmpty()) {
        throw conflict();
      }
      return;
    }
    Set<UUID> current =
        reads.read(holder, List.of(lot)).entries().stream()
            .filter(row -> row.getPersonal() == personal && !row.getOffer().offered())
            .filter(row ->
                Objects.equals(
                    unit, row.getOwningOrgUnit() == null ? null : row.getOwningOrgUnit().getId()))
            .map(InventoryStockRowSnapshot::getId)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    Set<UUID> planned =
        victims.stream()
            .map(execution::id)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    if (!planned.equals(current)) {
      throw conflict();
    }
  }

  /** Verifies that every confirmed merge victim was actually folded by the owning command. */
  private void requireDeleted(
      UUID holder,
      StockLot lot,
      Set<InventoryLotBatchProgram.RowKey> victims,
      Execution execution) {
    if (victims.isEmpty()) {
      return;
    }
    Set<UUID> ids =
        victims.stream()
            .map(execution::id)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    if (reads.read(holder, List.of(lot)).entries().stream()
        .anyMatch(row -> ids.contains(row.getId()))) {
      throw conflict();
    }
  }

  /** Reads current scalar facts for a named prelude source already in the complete plan. */
  private InventoryStockRowSnapshot stockRow(UUID holder, UUID id) {
    var row = stock.lockRow(id).orElseThrow(InventoryLotBatchExecutor::conflict);
    StockLot lot =
        new StockLot(
            row.getMaterial(),
            row.getGameItem(),
            row.getLocation(),
            row.getQuality(),
            Boolean.TRUE.equals(row.getStolen()));
    return reads.read(holder, List.of(lot)).entries().stream()
        .filter(item -> item.getId().equals(id))
        .findFirst()
        .orElseThrow(InventoryLotBatchExecutor::conflict);
  }

  /** Returns the normal retry conflict for an altered physical selection. */
  private static BusinessConflictException conflict() {
    return new BusinessConflictException("error.inventory.concurrent_scope_changed");
  }

  /** The real ordered take and victims of one owning rebooking source. */
  private record Take(Binding source, BigDecimal amount, Set<Binding> victims) {}

  /** One canonical physical free-prefix take. */
  private record PrefixTake(UUID source, BigDecimal amount) {}

  /** An actual row or an earlier result inside one atomic owning rebooking command. */
  private sealed interface Binding permits Real, Prospective {}

  /** An actual physical source or prior command survivor. */
  private record Real(UUID id) implements Binding {}

  /** An earlier prospective take within the same owning rebooking command. */
  private record Prospective(int index) implements Binding {}

  /** Private transaction-local result bindings and already executed lot dependencies. */
  static final class Execution {
    private final Map<InventoryLotBatchProgram.RowKey, UUID> created = new HashMap<>();
    private final Set<String> touched = new HashSet<>();
    private final Map<String, RebookCommands.RebookRefusal> refused = new HashMap<>();

    /** Resolves a real source or an actual survivor returned by an earlier owning command. */
    UUID id(InventoryLotBatchProgram.RowKey key) {
      if (key instanceof InventoryLotBatchProgram.Existing existing) {
        return existing.id();
      }
      UUID id = created.get(key);
      if (id == null) {
        throw conflict();
      }
      return id;
    }

    /**
     * Records the actual resulting survivor without inventing a physical or provenance identity.
     */
    void bind(InventoryLotBatchProgram.RowKey key, UUID actual) {
      if (key instanceof InventoryLotBatchProgram.Existing existing) {
        if (!existing.id().equals(actual)) {
          throw conflict();
        }
      } else {
        UUID previous = created.putIfAbsent(key, actual);
        if (previous != null && !previous.equals(actual)) {
          throw conflict();
        }
      }
    }
  }
}
