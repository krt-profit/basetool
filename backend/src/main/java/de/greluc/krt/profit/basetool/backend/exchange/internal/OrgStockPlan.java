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

import de.greluc.krt.profit.basetool.backend.exchange.internal.OrgStockPlan.Allocate;
import de.greluc.krt.profit.basetool.backend.exchange.internal.OrgStockPlan.BookIn;
import de.greluc.krt.profit.basetool.backend.exchange.internal.OrgStockPlan.BookOut;
import de.greluc.krt.profit.basetool.backend.exchange.internal.OrgStockPlan.Locks;
import de.greluc.krt.profit.basetool.backend.exchange.internal.OrgStockPlan.Move;
import de.greluc.krt.profit.basetool.backend.exchange.internal.OrgStockPlan.MovePool;
import de.greluc.krt.profit.basetool.backend.exchange.internal.OrgStockPlan.Note;
import de.greluc.krt.profit.basetool.backend.exchange.internal.OrgStockPlan.PoolWrite;
import de.greluc.krt.profit.basetool.backend.exchange.internal.OrgStockPlan.Sell;
import de.greluc.krt.profit.basetool.backend.exchange.internal.OrgStockPlan.Stolen;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolAllocation;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolBookIn;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolBookOut;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolChange;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolMove;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolNote;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolOutcome;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolRelocation;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolSale;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolStolen;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Sealed plans shared by org-stock validation and execution. */
final class OrgStockPlan {
  private OrgStockPlan() {}

  /** An op the Lager decides: what it locks, runs and journals. */
  sealed interface PoolWrite permits BookIn, BookOut, Sell, Move, MovePool, Note, Stolen, Allocate {

    /**
     * Adds the lots and entries this op touches to the batch's locks.
     *
     * @param locks the batch's locks
     */
    void lockInto(@NotNull Locks locks);

    /**
     * Hands this op to the Lager.
     *
     * @param commands the Lager's pool commands
     * @param dryRun whether to check it only
     * @return the Lager's outcome
     */
    @NotNull
    PoolOutcome run(@NotNull PoolEntryCommands commands, boolean dryRun);

    /**
     * Returns the {@code exchange_journal} action this op's entries are recorded under.
     *
     * @return the action
     */
    @NotNull
    ExchangeJournalAction action();

    /**
     * Returns the entry whose journal row counts as a removal for the mass-change guard.
     *
     * @return the entry a book-out, a sale or a move into another pool takes stock from, or {@code
     *     null}
     */
    @Nullable
    UUID removedFrom();
  }

  /**
   * The lots, entries and relocations a batch locks before any op runs (ADR-0229).
   *
   * @param holderLots the lots the book-ins book into, by holder
   * @param entries the entries the ops write
   * @param relocations the lots moves and markings land in
   */
  record Locks(
      @NotNull Map<UUID, List<StockLot>> holderLots,
      @NotNull Set<UUID> entries,
      @NotNull List<PoolRelocation> relocations) {}

  /**
   * A book-in the Lager decides.
   *
   * @param request the book-in
   */
  record BookIn(@NotNull PoolBookIn request) implements PoolWrite {

    @Override
    public void lockInto(@NotNull Locks locks) {
      locks
          .holderLots()
          .computeIfAbsent(request.holder(), _ -> new ArrayList<>())
          .add(request.lot());
    }

    @Override
    public @NotNull PoolOutcome run(@NotNull PoolEntryCommands commands, boolean dryRun) {
      return commands.bookIn(request, dryRun);
    }

    @Override
    public @NotNull ExchangeJournalAction action() {
      return ExchangeJournalAction.ORG_STOCK_BOOK_IN;
    }

    @Override
    public @Nullable UUID removedFrom() {
      return null;
    }
  }

  /**
   * A book-out the Lager decides.
   *
   * @param request the book-out
   */
  record BookOut(@NotNull PoolBookOut request) implements PoolWrite {

    @Override
    public void lockInto(@NotNull Locks locks) {
      locks.entries().add(request.entryId());
    }

    @Override
    public @NotNull PoolOutcome run(@NotNull PoolEntryCommands commands, boolean dryRun) {
      return commands.bookOut(request, dryRun);
    }

    @Override
    public @NotNull ExchangeJournalAction action() {
      return ExchangeJournalAction.ORG_STOCK_BOOK_OUT;
    }

    @Override
    public @NotNull UUID removedFrom() {
      return request.entryId();
    }
  }

  /**
   * A sale the Lager decides.
   *
   * @param request the sale
   */
  record Sell(@NotNull PoolSale request) implements PoolWrite {

    @Override
    public void lockInto(@NotNull Locks locks) {
      locks.entries().add(request.take().entryId());
    }

    @Override
    public @NotNull PoolOutcome run(@NotNull PoolEntryCommands commands, boolean dryRun) {
      return commands.sell(request, dryRun);
    }

    @Override
    public @NotNull ExchangeJournalAction action() {
      return ExchangeJournalAction.ORG_STOCK_SELL;
    }

    @Override
    public @NotNull UUID removedFrom() {
      return request.take().entryId();
    }
  }

  /**
   * A move to another location or holder the Lager decides.
   *
   * @param request the move
   * @param action {@code ORG_STOCK_MOVE_LOCATION} or {@code ORG_STOCK_MOVE_HOLDER}
   */
  record Move(@NotNull PoolMove request, @NotNull ExchangeJournalAction action)
      implements PoolWrite {

    @Override
    public void lockInto(@NotNull Locks locks) {
      locks
          .relocations()
          .add(
              new PoolRelocation(
                  request.take().entryId(),
                  request.targetHolder(),
                  request.targetLocation() == null ? null : request.targetLocation().getId(),
                  null));
    }

    @Override
    public @NotNull PoolOutcome run(@NotNull PoolEntryCommands commands, boolean dryRun) {
      return commands.move(request, dryRun);
    }

    @Override
    public @Nullable UUID removedFrom() {
      return request.targetPoolUnitId().equals(request.take().poolUnitId())
          ? null
          : request.take().entryId();
    }
  }

  /**
   * A note edit the Lager decides.
   *
   * @param request the note edit
   */
  record Note(@NotNull PoolNote request) implements PoolWrite {

    @Override
    public void lockInto(@NotNull Locks locks) {
      locks.entries().add(request.entryId());
    }

    @Override
    public @NotNull PoolOutcome run(@NotNull PoolEntryCommands commands, boolean dryRun) {
      return commands.setNote(request, dryRun);
    }

    @Override
    public @NotNull ExchangeJournalAction action() {
      return ExchangeJournalAction.ORG_STOCK_NOTE;
    }

    @Override
    public @Nullable UUID removedFrom() {
      return null;
    }
  }

  /**
   * A move into another pool of the holder the Lager decides.
   *
   * @param request the pool change
   */
  record MovePool(@NotNull PoolChange request) implements PoolWrite {

    @Override
    public void lockInto(@NotNull Locks locks) {
      locks.entries().add(request.entryId());
    }

    @Override
    public @NotNull PoolOutcome run(@NotNull PoolEntryCommands commands, boolean dryRun) {
      return commands.movePool(request, dryRun);
    }

    @Override
    public @NotNull ExchangeJournalAction action() {
      return ExchangeJournalAction.ORG_STOCK_MOVE_POOL;
    }

    @Override
    public @NotNull UUID removedFrom() {
      return request.entryId();
    }
  }

  /**
   * A stolen marking the Lager decides.
   *
   * @param request the marking
   */
  record Stolen(@NotNull PoolStolen request) implements PoolWrite {

    @Override
    public void lockInto(@NotNull Locks locks) {
      locks.relocations().add(new PoolRelocation(request.entryId(), null, null, request.stolen()));
    }

    @Override
    public @NotNull PoolOutcome run(@NotNull PoolEntryCommands commands, boolean dryRun) {
      return commands.markStolen(request, dryRun);
    }

    @Override
    public @NotNull ExchangeJournalAction action() {
      return ExchangeJournalAction.ORG_STOCK_STOLEN;
    }

    @Override
    public @Nullable UUID removedFrom() {
      return null;
    }
  }

  /**
   * An earmark write the Lager decides.
   *
   * @param request the earmark write
   */
  record Allocate(@NotNull PoolAllocation request) implements PoolWrite {

    @Override
    public void lockInto(@NotNull Locks locks) {
      locks.entries().add(request.entryId());
    }

    @Override
    public @NotNull PoolOutcome run(@NotNull PoolEntryCommands commands, boolean dryRun) {
      return commands.allocate(request, dryRun);
    }

    @Override
    public @NotNull ExchangeJournalAction action() {
      return switch (request.change()) {
        case ADD -> ExchangeJournalAction.ORG_STOCK_EARMARK_ADD;
        case CHANGE -> ExchangeJournalAction.ORG_STOCK_EARMARK_CHANGE;
        case REMOVE -> ExchangeJournalAction.ORG_STOCK_EARMARK_REMOVE;
      };
    }

    @Override
    public @Nullable UUID removedFrom() {
      return null;
    }
  }
}
