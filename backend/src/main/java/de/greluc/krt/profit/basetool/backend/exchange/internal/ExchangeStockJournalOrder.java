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

import de.greluc.krt.profit.basetool.backend.inventory.api.StockLotWritePlans;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockQuantityComposition;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Orders only coherently admitted stock operations within their actual transaction and batch. */
@Component
@RequiredArgsConstructor
final class ExchangeStockJournalOrder {
  private final ObjectMapper objectMapper;

  /** Retains the original group slots while ordering each provable transaction/batch bucket. */
  Ordered read(List<ExchangeJournalEntry> group) {
    Map<UUID, Optional<ExchangeStockJournal.State>> before = new HashMap<>();
    Map<UUID, Optional<ExchangeStockJournal.State>> after = new HashMap<>();
    Map<Batch, List<Integer>> positions = new LinkedHashMap<>();
    for (int index = 0; index < group.size(); index++) {
      var entry = group.get(index);
      before.put(entry.getId(), decode(entry.getBeforeState()));
      after.put(entry.getId(), decode(entry.getAfterState()));
      positions.computeIfAbsent(batch(entry), _ -> new ArrayList<>()).add(index);
    }
    List<ExchangeJournalEntry> ordered = new ArrayList<>(group);
    Set<Batch> provedBatches = new HashSet<>();
    positions.forEach((batch, slots) -> {
      List<ExchangeJournalEntry> entries = slots.stream().map(group::get).toList();
      if (batch.id() == null || !coherent(entries, before, after)) {
        return;
      }
      provedBatches.add(batch);
      if (entries.size() > 1) {
        entries =
            entries.stream()
                .sorted(
                    Comparator.comparingInt((ExchangeJournalEntry entry) ->
                            Objects.requireNonNull(
                                Objects.requireNonNull(before.get(entry.getId()))
                                    .orElseThrow()
                                    .operationStep()))
                        .reversed())
                .toList();
      }
      for (int index = 0; index < slots.size(); index++) {
        ordered.set(slots.get(index), entries.get(index));
      }
    });
    Map<Long, Set<Batch>> transactions = new HashMap<>();
    positions
        .keySet()
        .forEach(
            batch -> transactions.computeIfAbsent(batch.tx(), _ -> new HashSet<>()).add(batch));
    Set<Long> orderedTransactions = new HashSet<>();
    transactions.forEach((tx, batches) -> {
      if (batches.size() == 1 && provedBatches.contains(batches.iterator().next())) {
        orderedTransactions.add(tx);
      }
    });
    return new Ordered(ordered, before, orderedTransactions);
  }

  /** Requires matching before/after indices, preserving unindexed single-entry boundaries. */
  private static boolean coherent(
      List<ExchangeJournalEntry> entries,
      Map<UUID, Optional<ExchangeStockJournal.State>> before,
      Map<UUID, Optional<ExchangeStockJournal.State>> after) {
    Set<Integer> steps = new HashSet<>();
    for (var entry : entries) {
      var first = Objects.requireNonNull(before.get(entry.getId())).orElse(null);
      var last = Objects.requireNonNull(after.get(entry.getId())).orElse(null);
      Integer beforeStep = first == null ? null : first.operationStep();
      Integer afterStep = last == null ? null : last.operationStep();
      if (entries.size() == 1 && beforeStep == null && afterStep == null) {
        return true;
      }
      if (beforeStep == null || !beforeStep.equals(afterStep) || !steps.add(beforeStep)) {
        return false;
      }
    }
    return true;
  }

  /** Reads explicit complete proof while preserving absent old states. */
  private Optional<ExchangeStockJournal.State> decode(@Nullable String json) {
    return json == null
        ? Optional.empty()
        : Optional.of(ExchangeStockJournal.State.read(objectMapper, objectMapper.readTree(json)));
  }

  /** Reads the actual database transaction and containing server batch identity. */
  private static Batch batch(ExchangeJournalEntry entry) {
    return new Batch(entry.getTx(), entry.getBatchId());
  }

  /** One actual transaction/batch bucket with explicit missing metadata. */
  private record Batch(long tx, @Nullable UUID id) {}

  /** The retained group order and explicit complete before-state proof. */
  record Ordered(
      List<ExchangeJournalEntry> newestFirst,
      Map<UUID, Optional<ExchangeStockJournal.State>> before,
      Set<Long> orderedTransactions) {
    Ordered {
      newestFirst = List.copyOf(newestFirst);
      before = Map.copyOf(before);
      orderedTransactions = Set.copyOf(orderedTransactions);
    }

    /** Returns the exact selected before-state, including genuine absence. */
    @Nullable
    ExchangeStockJournal.State before(ExchangeJournalEntry entry) {
      return Objects.requireNonNull(before.get(entry.getId())).orElse(null);
    }

    /** Selects proved original quantity or an explicit unproved old journal target. */
    StockLotWritePlans.QuantityTarget target() {
      var oldest = newestFirst.getLast();
      var target = before(oldest);
      return target != null
              && target.completeQuantity() != null
              && orderedTransactions.contains(oldest.getTx())
          ? new StockLotWritePlans.ExactQuantity(target.completeQuantity())
          : new StockLotWritePlans.LegacyQuantity(oldest.getId(), firstKnownBoundary());
    }

    /** Finds the first actual known before-boundary only within a provably ordered transaction. */
    private @Nullable StockQuantityComposition firstKnownBoundary() {
      for (var entry : newestFirst.reversed()) {
        var state = before(entry);
        if (state != null && state.completeQuantity() != null) {
          return orderedTransactions.contains(entry.getTx()) ? state.completeQuantity() : null;
        }
      }
      return null;
    }
  }
}
