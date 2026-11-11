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

import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeChangeSetAdapter.Effects;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeChangeSetAdapter.Execution;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeChangeSetAdapter.Outcome;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeChangeSetAdapter.Planned;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeChangeSetAdapter.Write;
import de.greluc.krt.profit.basetool.backend.exchange.internal.OrgStockPlan.Locks;
import de.greluc.krt.profit.basetool.backend.exchange.internal.OrgStockPlan.PoolWrite;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolOutcome;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

/** Locks and executes org-stock plans with journal and live-sync effects. */
@Service
@RequiredArgsConstructor
class OrgStockExecutor {
  private final PoolEntryCommands poolEntryCommands;
  private final ExchangeJournalService journalService;
  private final ExchangeLiveSync liveSync;

  /**
   * Locks every holder lot and entry the plan touches, then hands each op to the Lager, journals
   * what it changed and refreshes the Lager pages afterwards.
   *
   * @param caller the client, installation and writer
   * @param batch the change set's id
   * @param plan the plan
   * @param dryRun whether the Lager only checks the ops
   * @return the execution
   */
  public @NotNull Execution<PoolWrite> start(
      @NotNull ExchangeCaller caller,
      @NotNull UUID batch,
      @NotNull List<Planned<PoolWrite>> plan,
      boolean dryRun) {
    lock(plan);
    return new Execution<>() {
      private int reduced;
      private int removed;

      @Override
      public @NotNull Outcome run(@NotNull PoolWrite write) {
        PoolOutcome outcome = write.run(poolEntryCommands, dryRun);
        if (outcome.refusal() != null) {
          return Outcome.rejected(outcome.refusal().name());
        }
        if (outcome.unchanged()) {
          return Outcome.UNCHANGED;
        }
        reduced += outcome.offers().reduced();
        removed += outcome.offers().removed();
        if (!dryRun) {
          journal(caller, batch, write, outcome);
        }
        return Outcome.APPLIED;
      }

      @Override
      public @NotNull Effects effects() {
        return new Effects(reduced, removed, null);
      }

      @Override
      public void announce() {
        liveSync.stockChanged(reduced + removed > 0);
      }
    };
  }

  /**
   * Takes the advisory locks of every holder lot the planned ops touch, in one ascending pass.
   *
   * @param plan the plan
   */
  private void lock(@NotNull List<Planned<PoolWrite>> plan) {
    Locks locks = new Locks(new HashMap<>(), new HashSet<>(), new ArrayList<>());
    for (Planned<PoolWrite> planned : plan) {
      if (planned instanceof Write<PoolWrite>(PoolWrite write)) {
        write.lockInto(locks);
      }
    }
    if (!locks.holderLots().isEmpty()
        || !locks.entries().isEmpty()
        || !locks.relocations().isEmpty()) {
      poolEntryCommands.lockLots(locks.holderLots(), locks.entries(), locks.relocations());
    }
  }

  /**
   * Journals one applied op under the writer, one entry per Lager entry it changed, keyed by that
   * entry; the entry a book-out, a sale or a move out of the pool takes stock from counts as a
   * removal.
   *
   * @param caller the writer
   * @param batch the change set's id
   * @param write the op
   * @param outcome its outcome
   */
  private void journal(
      @NotNull ExchangeCaller caller,
      @NotNull UUID batch,
      @NotNull PoolWrite write,
      @NotNull PoolOutcome outcome) {
    ExchangeJournalAction action = write.action();
    UUID removedFrom = write.removedFrom();
    for (PoolEntryCommands.PoolEntryChange change : outcome.changes()) {
      PoolEntryCommands.PoolEntryState key =
          change.after() != null ? change.after() : Objects.requireNonNull(change.before());
      journalService.record(
          caller,
          batch,
          action,
          key.id().toString(),
          key.id().equals(removedFrom),
          change.before(),
          change.after());
    }
  }
}
