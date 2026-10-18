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
import de.greluc.krt.profit.basetool.backend.inventory.api.StockCommands;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Owns the journal state of an org-stock entry, which is the Lager's own {@link
 * PoolEntryCommands.PoolEntryState}, and sets the entries back for an undo, all in one call
 * together with the member's own lots (REQ-XCH-046, ADR-0229, ADR-0285).
 */
@Component
@RequiredArgsConstructor
public class ExchangeOrgStockJournal {

  private final PoolEntryCommands poolEntryCommands;
  private final StockCommands stockCommands;
  private final ObjectMapper objectMapper;

  /**
   * Locks the member's own lots the undo sets back afterwards, together with every org-stock entry
   * in scope, and sets each entry back to its state before the client's first write in the span; an
   * entry of a pool the member may no longer write is left alone as {@code GONE}.
   *
   * @param member the member whose writes are undone
   * @param lots the member's own lots the undo sets back afterwards
   * @param groups the client's writes to each entry, newest first
   * @param pools the units the member is a Logistician and direct member of now, or {@code null}
   *     when every pool may be set back
   * @return the reason each group was left alone, or {@code null} for a restored one, by group
   */
  public @NotNull Map<List<ExchangeJournalEntry>, String> lockAndRestore(
      @NotNull UUID member,
      @NotNull List<String> lots,
      @NotNull List<List<ExchangeJournalEntry>> groups,
      @Nullable Set<UUID> pools) {
    if (groups.isEmpty()) {
      stockCommands.lockLots(member, lots);
      return Map.of();
    }
    IdentityHashMap<List<ExchangeJournalEntry>, String> reasons = new IdentityHashMap<>();
    List<PoolEntryCommands.PoolRestore> restores = new ArrayList<>();
    List<List<ExchangeJournalEntry>> restoring = new ArrayList<>();
    for (List<ExchangeJournalEntry> group : groups) {
      IdentityHashMap<ExchangeJournalEntry, WriteOrder> places = new IdentityHashMap<>();
      group.forEach(entry -> places.put(entry, writeOrder(entry)));
      List<ExchangeJournalEntry> ordered =
          group.stream().sorted(Comparator.comparing(places::get)).toList();
      PoolEntryCommands.PoolEntryState target = read(ordered.getFirst().getBeforeState());
      PoolEntryCommands.PoolEntryState expected = read(ordered.getLast().getAfterState());
      if (pools != null && !(inPools(target, pools) && inPools(expected, pools))) {
        reasons.put(group, ExchangeUndoService.GONE);
        continue;
      }
      restores.add(
          new PoolEntryCommands.PoolRestore(
              UUID.fromString(group.getFirst().getEntityKey()), expected, target));
      restoring.add(group);
    }
    List<PoolEntryCommands.PoolRestoreResult> results =
        poolEntryCommands.restore(Map.of(member, lots), restores);
    for (int i = 0; i < results.size(); i++) {
      reasons.put(
          restoring.get(i),
          switch (results.get(i)) {
            case RESTORED -> null;
            case CHANGED_AFTERWARDS -> ExchangeUndoService.CHANGED_AFTERWARDS;
            case GONE -> ExchangeUndoService.GONE;
          });
    }
    return reasons;
  }

  /**
   * Reads a journaled org-stock state.
   *
   * @param json the state as the journal stores it, or {@code null}
   * @return the state, or {@code null} for an absent entry
   */
  @Nullable
  PoolEntryCommands.PoolEntryState read(@Nullable String json) {
    return json == null
        ? null
        : objectMapper.readValue(json, PoolEntryCommands.PoolEntryState.class);
  }

  /**
   * Orders an entry's journaled writes: by transaction, and within one by the version the entry had
   * before the write, since a change set's rows share their recording time.
   *
   * @param entry a journaled write
   * @return its place, the earliest first
   */
  private @NotNull WriteOrder writeOrder(@NotNull ExchangeJournalEntry entry) {
    PoolEntryCommands.PoolEntryState before = read(entry.getBeforeState());
    return new WriteOrder(entry.getTx(), before == null ? Long.MIN_VALUE : before.version());
  }

  /**
   * Whether a journaled state lies in one of the given pools.
   *
   * @param state the state, or {@code null} for an absent entry
   * @param pools the pools
   * @return {@code true} for an absent entry or one in those pools
   */
  private static boolean inPools(
      @Nullable PoolEntryCommands.PoolEntryState state, @NotNull Set<UUID> pools) {
    return state == null || pools.contains(state.poolUnitId());
  }

  /**
   * The place of a journaled org-stock write among the writes to the same entry.
   *
   * @param tx the writing transaction
   * @param versionBefore the entry's version before the write, the lowest for a creation
   */
  private record WriteOrder(long tx, long versionBefore) implements Comparable<WriteOrder> {

    @Override
    public int compareTo(@NotNull WriteOrder other) {
      int byTx = Long.compare(tx, other.tx);
      return byTx != 0 ? byTx : Long.compare(versionBefore, other.versionBefore);
    }
  }
}
