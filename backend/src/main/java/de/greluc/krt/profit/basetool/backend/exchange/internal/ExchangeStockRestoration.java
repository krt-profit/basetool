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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import static de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeOps.round;

import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolRestoreResult;
import de.greluc.krt.profit.basetool.backend.inventory.api.RebookCommands;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLotWritePlans;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Confirms one complete historical stock program while preserving per-group Undo policy. */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
class ExchangeStockRestoration {
  private final StockLotWritePlans plans;
  private final ExchangeOrgStockJournal orgJournal;
  private final ExchangeStockJournal stockJournal;
  private final ExchangeStockJournalOrder journalOrder;
  private final ExchangeHistoricalStockLots catalogue;
  private final ExchangeChangeRepository changes;
  private final ExchangeOrgUnitService orgUnits;

  /** Prepares every stock group and executes only its confirmed named-entry prefix. */
  Action prepare(
      UUID member,
      @NotNull Collection<List<ExchangeJournalEntry>> groups,
      @Nullable Set<UUID> initialPools) {
    List<List<ExchangeJournalEntry>> orgGroups =
        groups.stream()
            .filter(group -> group.getFirst().getResource() == ExchangeResource.ORG_STOCK)
            .toList();
    List<List<ExchangeJournalEntry>> personalGroups =
        groups.stream()
            .filter(group -> group.getFirst().getResource() == ExchangeResource.STOCK)
            .toList();
    List<StockLotWritePlans.EntryRestore> declared = new ArrayList<>();
    for (int step = 0; step < orgGroups.size(); step++) {
      declared.add(orgJournal.declare(orgGroups.get(step), step));
    }
    var accepted =
        declared.stream().filter(entry -> orgJournal.permitted(entry, initialPools)).toList();
    var lots = catalogue.resolve(personalKeys(personalGroups));
    var initialLatest = latest(member, personalGroups);
    List<Personal> preliminary = new ArrayList<>();
    for (int index = 0; index < personalGroups.size(); index++) {
      preliminary.add(
          declare(member, personalGroups.get(index), declared.size() + index, lots, initialLatest));
    }
    List<StockLot> readLots =
        preliminary.stream().map(Personal::lot).filter(Objects::nonNull).distinct().toList();
    List<StockLotWritePlans.EntryRestore> finalEntries = new ArrayList<>(accepted);
    List<Personal> selected = new ArrayList<>(preliminary);
    var prepared =
        plans.prepareLotBatch(
            member,
            readLots,
            declared,
            accepted,
            intents(preliminary),
            _ -> {
              Set<UUID> currentPools =
                  initialPools == null ? null : orgUnits.logisticianUnits(member);
              finalEntries.removeIf(entry -> !orgJournal.permitted(entry, currentPools));
              var currentLots = catalogue.resolve(personalKeys(personalGroups));
              var currentLatest = latest(member, personalGroups);
              for (int index = 0; index < selected.size(); index++) {
                var current = selected.get(index);
                if (current.intent() != null) {
                  selected.set(
                      index,
                      declare(member, current.group(), current.step(), currentLots, currentLatest));
                }
              }
              return new StockLotWritePlans.Selection(finalEntries, intents(selected));
            });
    var confirmed = plans.confirmLotBatch(prepared, finalEntries, intents(selected), false);
    List<PoolRestoreResult> results =
        finalEntries.isEmpty() ? List.of() : plans.executePreparedEntryRestores(confirmed);
    IdentityHashMap<List<ExchangeJournalEntry>, String> reasons = new IdentityHashMap<>();
    IdentityHashMap<List<ExchangeJournalEntry>, Personal> pending = new IdentityHashMap<>();
    for (int index = 0; index < declared.size(); index++) {
      reasons.put(orgGroups.get(index), ExchangeUndoService.GONE);
    }
    for (int index = 0; index < finalEntries.size(); index++) {
      int original = finalEntries.get(index).step();
      reasons.put(
          orgGroups.get(original),
          switch (results.get(index)) {
            case RESTORED -> null;
            case CHANGED_AFTERWARDS -> ExchangeUndoService.CHANGED_AFTERWARDS;
            case GONE -> ExchangeUndoService.GONE;
          });
    }
    for (var personal : selected) {
      reasons.put(personal.group(), personal.reason());
      if (personal.intent() != null) {
        pending.put(personal.group(), personal);
      }
    }
    Action action = new Action(confirmed, reasons, pending);
    TransactionSynchronizationManager.registerSynchronization(action);
    return action;
  }

  /** Executes each personal group at its existing position among the other resource restorers. */
  @Nullable
  String restore(Action action, List<ExchangeJournalEntry> group) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()
        || !TransactionSynchronizationManager.getSynchronizations().contains(action)
        || !action.reasons.containsKey(group)
        || !action.executed.add(group)) {
      throw new BusinessConflictException("error.inventory.concurrent_scope_changed");
    }
    var personal = action.pending.get(group);
    if (personal != null) {
      return plans.executePreparedLot(action.confirmed, personal.step(), false).refusal() == null
          ? null
          : ExchangeUndoService.GONE;
    }
    return action.reasons.get(group);
  }

  /** Reads the latest epoch of all personal keys with the unchanged latest-tx/seq rule. */
  private Map<String, Long> latest(UUID member, List<List<ExchangeJournalEntry>> groups) {
    var keys = personalKeys(groups);
    return keys.isEmpty()
        ? Map.of()
        : changes.findLatestForKeys(member, ExchangeResource.STOCK.feed().name(), keys).stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    ExchangeChangeRepository.ChangedKey::getEntityKey,
                    ExchangeChangeRepository.ChangedKey::getTx));
  }

  /** Retains the current feed guard, old missing-state refusal and canonical inverse pool moves. */
  private Personal declare(
      UUID member,
      List<ExchangeJournalEntry> group,
      int step,
      Map<String, Optional<StockLot>> lots,
      Map<String, Long> latest) {
    var newest = group.getFirst();
    StockLot lot = Objects.requireNonNull(lots.get(newest.getEntityKey())).orElse(null);
    Long epoch = latest.get(newest.getEntityKey());
    if (epoch == null || epoch.longValue() != newest.getTx()) {
      return new Personal(group, step, lot, null, ExchangeUndoService.CHANGED_AFTERWARDS);
    }
    var ordered = journalOrder.read(group);
    var before = ordered.before(ordered.newestFirst().getLast());
    if (before == null || before.quantity() == null || before.unit() == null || lot == null) {
      return new Personal(group, step, lot, null, ExchangeUndoService.GONE);
    }
    Map<UUID, BigDecimal> moved = new TreeMap<>();
    for (var write : group) {
      boolean share = write.getAction() == ExchangeJournalAction.STOCK_SHARE;
      if (share || write.getAction() == ExchangeJournalAction.STOCK_UNSHARE) {
        var after = stockJournal.read(write.getAfterState());
        BigDecimal amount = after == null ? null : share ? after.shared() : after.unshared();
        if (after == null || after.orgUnit() == null || amount == null) {
          return new Personal(group, step, lot, null, ExchangeUndoService.GONE);
        }
        moved.merge(after.orgUnit(), share ? amount : amount.negate(), BigDecimal::add);
      }
    }
    List<RebookCommands.Rebook> toPersonal = new ArrayList<>();
    List<RebookCommands.Rebook> toPool = new ArrayList<>();
    moved.forEach((pool, amount) -> {
      if (amount.signum() > 0) {
        toPersonal.add(
            new RebookCommands.Rebook(
                member,
                lot,
                pool,
                RebookCommands.RebookDirection.TO_PERSONAL,
                amount.doubleValue()));
      } else if (amount.signum() < 0) {
        toPool.add(
            new RebookCommands.Rebook(
                member,
                lot,
                pool,
                RebookCommands.RebookDirection.TO_POOL,
                amount.negate().doubleValue()));
      }
    });
    toPersonal.addAll(toPool);
    return new Personal(
        group,
        step,
        lot,
        new StockLotWritePlans.RestoreLot(
            step, lot, toPersonal, round(before.quantity(), before.unit()), ordered.target()),
        null);
  }

  /** Returns genuine personal history keys without narrowing any selected journal group. */
  private static List<String> personalKeys(List<List<ExchangeJournalEntry>> groups) {
    return groups.stream().map(group -> group.getFirst().getEntityKey()).distinct().toList();
  }

  /** Retains exact accepted personal operation order after Exchange policy re-evaluation. */
  private static List<StockLotWritePlans.Intent> intents(List<Personal> groups) {
    return groups.stream()
        .filter(personal -> personal.intent() != null)
        .<StockLotWritePlans.Intent>map(personal -> Objects.requireNonNull(personal.intent()))
        .toList();
  }

  /** One actual group identity with an accepted physical intent or its unchanged refusal reason. */
  private record Personal(
      List<ExchangeJournalEntry> group,
      int step,
      @Nullable StockLot lot,
      @Nullable StockLotWritePlans.RestoreLot intent,
      @Nullable String reason) {}

  /** Originating-transaction outcomes and the remaining ordered personal operations. */
  @RequiredArgsConstructor
  static final class Action implements TransactionSynchronization {
    private final StockLotWritePlans.Confirmed confirmed;
    private final IdentityHashMap<List<ExchangeJournalEntry>, String> reasons;
    private final IdentityHashMap<List<ExchangeJournalEntry>, Personal> pending;
    private final Set<List<ExchangeJournalEntry>> executed =
        Collections.newSetFromMap(new IdentityHashMap<>());
  }
}
