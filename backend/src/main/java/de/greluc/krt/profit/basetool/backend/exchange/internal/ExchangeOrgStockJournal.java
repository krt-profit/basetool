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

import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLotWritePlans;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Decodes genuine named-entry history and its original expected/target restoration boundary. */
@Component
@RequiredArgsConstructor
public class ExchangeOrgStockJournal {
  private final ObjectMapper objectMapper;

  /** Declares the real earliest journal reference and immutable historical restoration states. */
  StockLotWritePlans.EntryRestore declare(List<ExchangeJournalEntry> group, int step) {
    IdentityHashMap<ExchangeJournalEntry, WriteOrder> places = new IdentityHashMap<>();
    group.forEach(entry -> places.put(entry, writeOrder(entry)));
    List<ExchangeJournalEntry> ordered =
        group.stream().sorted(Comparator.comparing(places::get)).toList();
    var first = ordered.getFirst();
    return new StockLotWritePlans.EntryRestore(
        step,
        first.getId(),
        new PoolEntryCommands.PoolRestore(
            UUID.fromString(group.getFirst().getEntityKey()),
            read(ordered.getLast().getAfterState()),
            read(first.getBeforeState())));
  }

  /** Checks the same caller pool rights on both historical placement boundaries. */
  boolean permitted(StockLotWritePlans.EntryRestore restore, @Nullable Set<UUID> pools) {
    return pools == null
        || (inPools(restore.request().target(), pools)
            && inPools(restore.request().expected(), pools));
  }

  /** Reads a stored shared state without inventing missing original quantity evidence. */
  @Nullable
  PoolEntryCommands.PoolEntryState read(@Nullable String json) {
    return json == null
        ? null
        : objectMapper.readValue(json, PoolEntryCommands.PoolEntryState.class);
  }

  /** Orders writes by their actual transaction and the named entry's prior row version. */
  private @NotNull WriteOrder writeOrder(@NotNull ExchangeJournalEntry entry) {
    var before = read(entry.getBeforeState());
    return new WriteOrder(entry.getTx(), before == null ? Long.MIN_VALUE : before.version());
  }

  /** Accepts absent boundaries and only the caller's current permitted pools. */
  private static boolean inPools(
      @Nullable PoolEntryCommands.PoolEntryState state, Set<UUID> pools) {
    return state == null || pools.contains(state.poolUnitId());
  }

  /** The actual transaction and prior row version of one named historical write. */
  private record WriteOrder(long tx, long versionBefore) implements Comparable<WriteOrder> {
    @Override
    public int compareTo(@NotNull WriteOrder other) {
      int byTx = Long.compare(tx, other.tx);
      return byTx != 0 ? byTx : Long.compare(versionBefore, other.versionBefore);
    }
  }
}
