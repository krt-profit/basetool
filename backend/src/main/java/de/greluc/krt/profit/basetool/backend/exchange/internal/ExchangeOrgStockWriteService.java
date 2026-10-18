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

import static de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeOps.REJECTED;
import static de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeOps.UNCHANGED;
import static de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeOps.round;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.exchange.api.ExchangeProblemException;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeChangeResultDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeItemRef;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeOrgStockChangeSet;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeOrgStockMembersDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeOrgStockTargetsDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeOrgStockUnitDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeOrgUnitDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeStockChangeSet;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.EarmarkChange;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.EarmarkDimension;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolAllocation;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolBookIn;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolBookOut;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolChange;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolEarmark;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolMove;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolNote;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolOutcome;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolRelocation;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolSale;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolStolen;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockReads;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies a Logistician's org-stock writes to the shared entries of their units' pools through the
 * Lager's own create, book-out, sale, transfer, note edit, stolen marking and allocation writes,
 * each op on its own, journaled under the writer (REQ-XCH-045, REQ-XCH-046, ADR-0257); and lists
 * the roster a book-in or a {@code move-holder} names its holder from and the targets an earmark
 * names.
 *
 * <p>An op on a pool whose unit the writer is not a Logistician and direct member of is refused
 * {@code NOT_PERMITTED}, and so is a {@code move-holder} into such a pool and an op on an entry
 * that is personal or lies in another pool. Every holder lot the batch touches is locked before any
 * op runs (ADR-0229). The {@link ExchangeChangeSetRunner} runs the change set (ADR-0285).
 */
@Service
@RequiredArgsConstructor
public class ExchangeOrgStockWriteService
    implements ExchangeChangeSetAdapter<
        ExchangeOrgStockChangeSet, ExchangeOrgStockWriteService.PoolWrite> {

  /** The refusal of an op on a pool or entry the writer may not write. */
  static final String NOT_PERMITTED = "NOT_PERMITTED";

  /** The refusal of a holder who is not on the unit's roster. */
  static final String HOLDER_UNKNOWN = "HOLDER_UNKNOWN";

  /** The refusal of a place without a Lager location. */
  static final String LOCATION_UNKNOWN = "LOCATION_UNKNOWN";

  /** The refusal of a quantity whose unit is not the material's. */
  static final String UNIT_MISMATCH = "UNIT_MISMATCH";

  private final ExchangeOrgUnitService orgUnitService;
  private final ExchangeOrgStockRoster roster;
  private final ExchangeOrgStockTargets targets;
  private final ExchangeLotCatalogue lotCatalogue;
  private final ExchangeLocationResolver locationResolver;
  private final PoolEntryCommands poolEntryCommands;
  private final ExchangeJournalService journalService;
  private final ExchangeMassChangeGuard guard;
  private final StockReads stockReads;
  private final ExchangeLiveSync liveSync;
  private final ExchangeChangeSetRunner runner;

  /**
   * Applies one change set in one transaction, op by op in its order, unless it takes so much out
   * of one pool that the writer must confirm it (REQ-XCH-046).
   *
   * @param caller the client, installation and writer
   * @param changeSet the writes
   * @return the counts, the offers the ops lowered or removed, and the detail of every op that was
   *     not applied
   * @throws BadRequestException when an op lacks a field its kind needs
   * @throws ExchangeProblemException {@code 409 MASS_CHANGE_CONFIRMATION_REQUIRED} when the batch
   *     takes too much out of one pool; nothing is written then
   */
  @Transactional
  public @NotNull ExchangeChangeResultDto apply(
      @NotNull ExchangeCaller caller, @NotNull ExchangeOrgStockChangeSet changeSet) {
    return runner.run(caller, this, changeSet, true);
  }

  /**
   * Applies a change set the writer confirmed after the mass-change guard held it back, without
   * asking the guard again (REQ-XCH-021, REQ-XCH-046).
   *
   * @param caller the client, installation and writer the change set was staged for
   * @param changeSet the writes
   * @return the counts, the offers the ops lowered or removed, and the detail of every op that was
   *     not applied
   * @throws BadRequestException when an op lacks a field its kind needs
   */
  @Transactional
  public @NotNull ExchangeChangeResultDto applyConfirmed(
      @NotNull ExchangeCaller caller, @NotNull ExchangeOrgStockChangeSet changeSet) {
    return runner.run(caller, this, changeSet, false);
  }

  @Override
  public @NotNull ExchangeResource resource() {
    return ExchangeResource.ORG_STOCK;
  }

  @Override
  public boolean dryRun(@NotNull ExchangeOrgStockChangeSet changeSet) {
    return changeSet.dryRun();
  }

  @Override
  public @Nullable String opId(@NotNull ExchangeOrgStockChangeSet changeSet, int index) {
    return changeSet.ops().get(index).opId();
  }

  /**
   * Decides every op on its own, before anything is locked.
   *
   * @param caller the client, installation and writer
   * @param changeSet the writes
   * @return one plan per op
   * @throws BadRequestException when an op lacks a field its kind needs
   */
  @Override
  public @NotNull List<Planned<PoolWrite>> plan(
      @NotNull ExchangeCaller caller, @NotNull ExchangeOrgStockChangeSet changeSet) {
    List<ExchangeOrgStockChangeSet.Op> ops = changeSet.ops();
    ops.forEach(ExchangeOrgStockWriteService::requireFields);
    Set<UUID> pools = orgUnitService.logisticianUnits(caller.member());
    Map<Integer, ExchangeLotCatalogue.Entry> catalogue = resolveBookIns(ops);
    Map<UUID, Map<String, UUID>> rosters = new HashMap<>();
    List<Planned<PoolWrite>> plan = new ArrayList<>(ops.size());
    for (int i = 0; i < ops.size(); i++) {
      ExchangeOrgStockChangeSet.Op op = ops.get(i);
      plan.add(
          switch (op.op()) {
            case ExchangeOrgStockChangeSet.BOOK_IN ->
                planBookIn(caller, op, pools, Objects.requireNonNull(catalogue.get(i)), rosters);
            case ExchangeOrgStockChangeSet.SELL -> planSell(caller, op, pools);
            case ExchangeOrgStockChangeSet.MOVE_LOCATION -> planMoveLocation(caller, op, pools);
            case ExchangeOrgStockChangeSet.MOVE_HOLDER ->
                planMoveHolder(caller, op, pools, rosters);
            case ExchangeOrgStockChangeSet.MOVE_POOL -> planMovePool(caller, op, pools);
            case ExchangeOrgStockChangeSet.NOTE -> planNote(caller, op, pools);
            case ExchangeOrgStockChangeSet.STOLEN -> planStolen(caller, op, pools);
            case ExchangeOrgStockChangeSet.BOOK_OUT -> planBookOut(caller, op, pools);
            case ExchangeOrgStockChangeSet.ALLOCATION_ADD ->
                planAllocation(op, pools, EarmarkChange.ADD);
            case ExchangeOrgStockChangeSet.ALLOCATION_CHANGE ->
                planAllocation(op, pools, EarmarkChange.CHANGE);
            case ExchangeOrgStockChangeSet.ALLOCATION_REMOVE ->
                planAllocation(op, pools, EarmarkChange.REMOVE);
            default -> throw new BadRequestException("Unknown org-stock op");
          });
    }
    return plan;
  }

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
  @Override
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
   * Whether the batch's book-outs, sales and moves out of a pool take any one pool over the
   * mass-change guard's threshold for the writer.
   *
   * @param caller the client and writer
   * @param changeSet the writes
   * @param plan what each op will do
   * @return {@code true} when the writer must confirm the batch
   */
  @Override
  public boolean holdsBack(
      @NotNull ExchangeCaller caller,
      @NotNull ExchangeOrgStockChangeSet changeSet,
      @NotNull List<Planned<PoolWrite>> plan) {
    Map<UUID, Long> removals = new TreeMap<>();
    for (int i = 0; i < plan.size(); i++) {
      if (plan.get(i) instanceof Write<PoolWrite>(PoolWrite write) && write.removedFrom() != null) {
        removals.merge(changeSet.ops().get(i).unit(), 1L, Long::sum);
      }
    }
    for (Map.Entry<UUID, Long> pool : removals.entrySet()) {
      if (guard.requiresPoolConfirmation(
          caller, pool.getKey(), stockReads.countPoolEntries(pool.getKey()), pool.getValue())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Lists the roster of one of the writer's Logistician units.
   *
   * @param caller the client and writer
   * @param unit the unit
   * @return the unit and its members who do not withhold their name
   * @throws AccessDeniedException when the writer is not a Logistician and direct member of the
   *     unit
   */
  @Transactional(readOnly = true)
  public @NotNull ExchangeOrgStockMembersDto members(
      @NotNull ExchangeCaller caller, @NotNull UUID unit) {
    return new ExchangeOrgStockMembersDto(
        logisticianUnit(caller, unit),
        roster.of(caller.clientId(), unit).stream()
            .map(holder -> new ExchangeOrgStockMembersDto.Member(holder.ref(), holder.name()))
            .toList());
  }

  /**
   * Lists the job orders and missions an entry of one of the writer's Logistician units may be
   * earmarked to, as the web's picker offers them for that unit.
   *
   * @param caller the writer
   * @param unit the unit
   * @param material the material or item, as the org stock's lots name it
   * @return the unit and its targets; no targets for an id that names no material or item
   * @throws AccessDeniedException when the writer is not a Logistician and direct member of the
   *     unit
   */
  @Transactional(readOnly = true)
  public @NotNull ExchangeOrgStockTargetsDto targets(
      @NotNull ExchangeCaller caller, @NotNull UUID unit, @NotNull UUID material) {
    ExchangeOrgStockUnitDto named = logisticianUnit(caller, unit);
    ExchangeOrgStockTargets.Targets found = targets.of(unit, material);
    return new ExchangeOrgStockTargetsDto(named, found.jobOrders(), found.missions());
  }

  /**
   * Names one of the writer's Logistician units.
   *
   * @param caller the writer
   * @param unit the unit
   * @return the unit's id and name
   * @throws AccessDeniedException when the writer is not a Logistician and direct member of the
   *     unit
   */
  private @NotNull ExchangeOrgStockUnitDto logisticianUnit(
      @NotNull ExchangeCaller caller, @NotNull UUID unit) {
    if (!orgUnitService.logisticianUnits(caller.member()).contains(unit)) {
      throw new AccessDeniedException("The member is not a Logistician of the unit");
    }
    ExchangeOrgUnitDto named =
        orgUnitService.orgUnits(caller.member()).items().stream()
            .filter(candidate -> candidate.id().equals(unit))
            .findFirst()
            .orElseThrow(() -> new AccessDeniedException("The member does not belong to the unit"));
    return new ExchangeOrgStockUnitDto(named.id(), named.name());
  }

  /**
   * Refuses an op that lacks a field its kind needs; the gateway's schema keeps such an op from
   * arriving.
   *
   * @param op the op
   * @throws BadRequestException when a field is missing
   */
  private static void requireFields(@NotNull ExchangeOrgStockChangeSet.Op op) {
    boolean entry = op.entry() != null && op.version() != null;
    boolean complete =
        switch (op.op()) {
          case ExchangeOrgStockChangeSet.BOOK_IN ->
              op.holder() != null
                  && op.material() != null
                  && op.location() != null
                  && op.quality() != null
                  && op.stolen() != null
                  && op.quantity() != null;
          case ExchangeOrgStockChangeSet.SELL ->
              entry
                  && op.quantity() != null
                  && op.terminal() != null
                  && !op.terminal().isBlank()
                  && op.proceeds() != null;
          case ExchangeOrgStockChangeSet.MOVE_LOCATION ->
              entry && op.quantity() != null && op.location() != null;
          case ExchangeOrgStockChangeSet.MOVE_HOLDER ->
              entry && op.quantity() != null && op.holder() != null && op.toUnit() != null;
          case ExchangeOrgStockChangeSet.MOVE_POOL -> entry && op.toUnit() != null;
          case ExchangeOrgStockChangeSet.NOTE -> entry;
          case ExchangeOrgStockChangeSet.STOLEN -> entry && op.stolen() != null;
          case ExchangeOrgStockChangeSet.BOOK_OUT -> entry && op.quantity() != null;
          case ExchangeOrgStockChangeSet.ALLOCATION_ADD,
              ExchangeOrgStockChangeSet.ALLOCATION_CHANGE ->
              entry && op.quantity() != null && oneTarget(op);
          case ExchangeOrgStockChangeSet.ALLOCATION_REMOVE -> entry && oneTarget(op);
          default -> false;
        };
    if (!complete) {
      throw new BadRequestException("An org-stock op lacks a field its kind needs");
    }
  }

  /**
   * Whether an earmark op names exactly one target, a job order or a mission.
   *
   * @param op the op
   * @return {@code true} when it names one
   */
  private static boolean oneTarget(@NotNull ExchangeOrgStockChangeSet.Op op) {
    return (op.jobOrder() == null) != (op.mission() == null);
  }

  /**
   * Resolves the material or item of every book-in in one pass.
   *
   * @param ops the ops
   * @return the catalogue entry of each book-in, by op index
   */
  private @NotNull Map<Integer, ExchangeLotCatalogue.Entry> resolveBookIns(
      @NotNull List<ExchangeOrgStockChangeSet.Op> ops) {
    List<Integer> indexes = new ArrayList<>();
    List<ExchangeItemRef> refs = new ArrayList<>();
    for (int i = 0; i < ops.size(); i++) {
      if (ExchangeOrgStockChangeSet.BOOK_IN.equals(ops.get(i).op())) {
        indexes.add(i);
        refs.add(Objects.requireNonNull(ops.get(i).material()));
      }
    }
    if (refs.isEmpty()) {
      return Map.of();
    }
    Map<Integer, ExchangeLotCatalogue.Entry> byRef = lotCatalogue.resolve(refs);
    Map<Integer, ExchangeLotCatalogue.Entry> byOp = new HashMap<>();
    byRef.forEach((index, entry) -> byOp.put(indexes.get(index), entry));
    return byOp;
  }

  /**
   * Decides what a book-in will do, before anything is locked.
   *
   * @param caller the writer
   * @param op the op
   * @param pools the units the writer is a Logistician and direct member of
   * @param entry the op's material or item, or the result that ends it
   * @param rosters each unit's roster read so far, ref to member
   * @return the plan
   */
  private @NotNull Planned<PoolWrite> planBookIn(
      @NotNull ExchangeCaller caller,
      @NotNull ExchangeOrgStockChangeSet.Op op,
      @NotNull Set<UUID> pools,
      @NotNull ExchangeLotCatalogue.Entry entry,
      @NotNull Map<UUID, Map<String, UUID>> rosters) {
    if (!pools.contains(op.unit())) {
      return ExchangeChangeSetAdapter.skip(REJECTED, NOT_PERMITTED);
    }
    UUID holder = holderOf(caller, op.unit(), Objects.requireNonNull(op.holder()), rosters);
    if (holder == null) {
      return ExchangeChangeSetAdapter.skip(REJECTED, HOLDER_UNKNOWN);
    }
    if (entry.result() != null) {
      return ExchangeChangeSetAdapter.skip(entry.result(), entry.reason());
    }
    Optional<Location> location = locationResolver.resolve(Objects.requireNonNull(op.location()));
    if (location.isEmpty()) {
      return ExchangeChangeSetAdapter.skip(REJECTED, LOCATION_UNKNOWN);
    }
    String unit = entry.unit();
    ExchangeStockChangeSet.Quantity quantity = Objects.requireNonNull(op.quantity());
    if (!sameUnit(unit, quantity, op.jobOrders(), op.missions())) {
      return ExchangeChangeSetAdapter.skip(REJECTED, UNIT_MISMATCH);
    }
    BigDecimal amount = round(quantity.amount(), unit);
    if (amount.signum() == 0) {
      return ExchangeChangeSetAdapter.skip(UNCHANGED, null);
    }
    StockLot lot =
        new StockLot(
            entry.material(),
            entry.gameItem(),
            location.get(),
            entry.material() == null ? null : op.quality(),
            Boolean.TRUE.equals(op.stolen()));
    return ExchangeChangeSetAdapter.write(
        new BookIn(
            new PoolBookIn(
                holder,
                op.unit(),
                lot,
                amount.doubleValue(),
                op.note(),
                Boolean.TRUE.equals(op.mergeScu()),
                jobOrderEarmarks(op.jobOrders(), unit),
                missionEarmarks(op.missions(), unit))));
  }

  /**
   * Decides what a book-out will do, before anything is locked.
   *
   * @param caller the writer
   * @param op the op
   * @param pools the units the writer is a Logistician and direct member of
   * @return the plan
   */
  private static @NotNull Planned<PoolWrite> planBookOut(
      @NotNull ExchangeCaller caller,
      @NotNull ExchangeOrgStockChangeSet.Op op,
      @NotNull Set<UUID> pools) {
    Planned<PoolWrite> skip = checkTake(op, pools);
    return skip != null ? skip : ExchangeChangeSetAdapter.write(new BookOut(takeOf(caller, op)));
  }

  /**
   * Decides what a sale will do, before anything is locked.
   *
   * @param caller the writer
   * @param op the op
   * @param pools the units the writer is a Logistician and direct member of
   * @return the plan
   */
  private static @NotNull Planned<PoolWrite> planSell(
      @NotNull ExchangeCaller caller,
      @NotNull ExchangeOrgStockChangeSet.Op op,
      @NotNull Set<UUID> pools) {
    Planned<PoolWrite> skip = checkTake(op, pools);
    return skip != null
        ? skip
        : ExchangeChangeSetAdapter.write(
            new Sell(
                new PoolSale(
                    takeOf(caller, op),
                    Objects.requireNonNull(op.terminal()).trim(),
                    Objects.requireNonNull(op.proceeds()))));
  }

  /**
   * Decides what a move to another location will do, before anything is locked.
   *
   * @param caller the writer
   * @param op the op
   * @param pools the units the writer is a Logistician and direct member of
   * @return the plan
   */
  private @NotNull Planned<PoolWrite> planMoveLocation(
      @NotNull ExchangeCaller caller,
      @NotNull ExchangeOrgStockChangeSet.Op op,
      @NotNull Set<UUID> pools) {
    Planned<PoolWrite> skip = checkTake(op, pools);
    if (skip != null) {
      return skip;
    }
    Optional<Location> location = locationResolver.resolve(Objects.requireNonNull(op.location()));
    if (location.isEmpty()) {
      return ExchangeChangeSetAdapter.skip(REJECTED, LOCATION_UNKNOWN);
    }
    return ExchangeChangeSetAdapter.write(
        new Move(
            new PoolMove(
                takeOf(caller, op),
                null,
                location.get(),
                op.unit(),
                Boolean.TRUE.equals(op.mergeScu())),
            ExchangeJournalAction.ORG_STOCK_MOVE_LOCATION));
  }

  /**
   * Decides what a move to another holder will do, before anything is locked.
   *
   * @param caller the writer
   * @param op the op
   * @param pools the units the writer is a Logistician and direct member of
   * @param rosters each unit's roster read so far, ref to member
   * @return the plan
   */
  private @NotNull Planned<PoolWrite> planMoveHolder(
      @NotNull ExchangeCaller caller,
      @NotNull ExchangeOrgStockChangeSet.Op op,
      @NotNull Set<UUID> pools,
      @NotNull Map<UUID, Map<String, UUID>> rosters) {
    UUID toUnit = Objects.requireNonNull(op.toUnit());
    if (!pools.contains(toUnit)) {
      return ExchangeChangeSetAdapter.skip(REJECTED, NOT_PERMITTED);
    }
    Planned<PoolWrite> skip = checkTake(op, pools);
    if (skip != null) {
      return skip;
    }
    UUID holder = holderOf(caller, toUnit, Objects.requireNonNull(op.holder()), rosters);
    if (holder == null) {
      return ExchangeChangeSetAdapter.skip(REJECTED, HOLDER_UNKNOWN);
    }
    return ExchangeChangeSetAdapter.write(
        new Move(
            new PoolMove(
                takeOf(caller, op), holder, null, toUnit, Boolean.TRUE.equals(op.mergeScu())),
            ExchangeJournalAction.ORG_STOCK_MOVE_HOLDER));
  }

  /**
   * Decides what a move into another pool of the holder will do, before anything is locked.
   *
   * @param caller the writer
   * @param op the op
   * @param pools the units the writer is a Logistician and direct member of
   * @return the plan
   */
  private static @NotNull Planned<PoolWrite> planMovePool(
      @NotNull ExchangeCaller caller,
      @NotNull ExchangeOrgStockChangeSet.Op op,
      @NotNull Set<UUID> pools) {
    UUID toUnit = Objects.requireNonNull(op.toUnit());
    if (!pools.contains(op.unit()) || !pools.contains(toUnit)) {
      return ExchangeChangeSetAdapter.skip(REJECTED, NOT_PERMITTED);
    }
    ExchangeStockChangeSet.Quantity quantity = op.quantity();
    Double amount = null;
    if (quantity != null) {
      BigDecimal rounded = round(quantity.amount(), quantity.unit());
      if (rounded.signum() == 0) {
        return ExchangeChangeSetAdapter.skip(UNCHANGED, null);
      }
      amount = rounded.doubleValue();
    }
    return ExchangeChangeSetAdapter.write(
        new MovePool(
            new PoolChange(
                caller.member(),
                Objects.requireNonNull(op.entry()),
                op.unit(),
                Objects.requireNonNull(op.version()),
                toUnit,
                amount,
                quantity == null ? null : quantity.unit(),
                Boolean.TRUE.equals(op.mergeScu()))));
  }

  /**
   * Decides what a note edit will do, before anything is locked.
   *
   * @param caller the writer
   * @param op the op
   * @param pools the units the writer is a Logistician and direct member of
   * @return the plan
   */
  private static @NotNull Planned<PoolWrite> planNote(
      @NotNull ExchangeCaller caller,
      @NotNull ExchangeOrgStockChangeSet.Op op,
      @NotNull Set<UUID> pools) {
    if (!pools.contains(op.unit())) {
      return ExchangeChangeSetAdapter.skip(REJECTED, NOT_PERMITTED);
    }
    return ExchangeChangeSetAdapter.write(
        new Note(
            new PoolNote(
                caller.member(),
                Objects.requireNonNull(op.entry()),
                op.unit(),
                Objects.requireNonNull(op.version()),
                op.note())));
  }

  /**
   * Decides what a stolen marking will do, before anything is locked.
   *
   * @param caller the writer
   * @param op the op
   * @param pools the units the writer is a Logistician and direct member of
   * @return the plan
   */
  private static @NotNull Planned<PoolWrite> planStolen(
      @NotNull ExchangeCaller caller,
      @NotNull ExchangeOrgStockChangeSet.Op op,
      @NotNull Set<UUID> pools) {
    if (!pools.contains(op.unit())) {
      return ExchangeChangeSetAdapter.skip(REJECTED, NOT_PERMITTED);
    }
    ExchangeStockChangeSet.Quantity quantity = op.quantity();
    Double amount = null;
    if (quantity != null) {
      BigDecimal rounded = round(quantity.amount(), quantity.unit());
      if (rounded.signum() == 0) {
        return ExchangeChangeSetAdapter.skip(UNCHANGED, null);
      }
      amount = rounded.doubleValue();
    }
    return ExchangeChangeSetAdapter.write(
        new Stolen(
            new PoolStolen(
                caller.member(),
                Objects.requireNonNull(op.entry()),
                op.unit(),
                Objects.requireNonNull(op.version()),
                Boolean.TRUE.equals(op.stolen()),
                amount,
                quantity == null ? null : quantity.unit())));
  }

  /**
   * Decides what an earmark write will do, before anything is locked.
   *
   * @param op the op
   * @param pools the units the writer is a Logistician and direct member of
   * @param change whether the earmark is added, changed or removed
   * @return the plan
   */
  private static @NotNull Planned<PoolWrite> planAllocation(
      @NotNull ExchangeOrgStockChangeSet.Op op,
      @NotNull Set<UUID> pools,
      @NotNull EarmarkChange change) {
    if (!pools.contains(op.unit())) {
      return ExchangeChangeSetAdapter.skip(REJECTED, NOT_PERMITTED);
    }
    ExchangeStockChangeSet.Quantity quantity = op.quantity();
    Double amount = null;
    if (change != EarmarkChange.REMOVE) {
      BigDecimal rounded = round(Objects.requireNonNull(quantity).amount(), quantity.unit());
      if (rounded.signum() == 0) {
        return ExchangeChangeSetAdapter.skip(UNCHANGED, null);
      }
      amount = rounded.doubleValue();
    }
    UUID jobOrder = op.jobOrder();
    return ExchangeChangeSetAdapter.write(
        new Allocate(
            new PoolAllocation(
                Objects.requireNonNull(op.entry()),
                op.unit(),
                Objects.requireNonNull(op.version()),
                change,
                jobOrder != null ? EarmarkDimension.JOB_ORDER : EarmarkDimension.MISSION,
                jobOrder != null ? jobOrder : Objects.requireNonNull(op.mission()),
                amount,
                change == EarmarkChange.REMOVE ? null : Objects.requireNonNull(quantity).unit())));
  }

  /**
   * Decides the part of a book-out, sale or move that needs no entry: the pool, the units of the
   * amount and the plans, and an amount that rounds to nothing.
   *
   * @param op the op
   * @param pools the units the writer is a Logistician and direct member of
   * @return the result that ends the op, or {@code null} when the Lager decides it
   */
  private static @Nullable Planned<PoolWrite> checkTake(
      @NotNull ExchangeOrgStockChangeSet.Op op, @NotNull Set<UUID> pools) {
    if (!pools.contains(op.unit())) {
      return ExchangeChangeSetAdapter.skip(REJECTED, NOT_PERMITTED);
    }
    ExchangeStockChangeSet.Quantity quantity = Objects.requireNonNull(op.quantity());
    String unit = quantity.unit();
    if (!sameUnit(unit, quantity, op.fromJobOrders(), op.fromMissions())) {
      return ExchangeChangeSetAdapter.skip(REJECTED, UNIT_MISMATCH);
    }
    if (round(quantity.amount(), unit).signum() == 0) {
      return ExchangeChangeSetAdapter.skip(UNCHANGED, null);
    }
    return null;
  }

  /**
   * Builds the entry, amount and plans of a book-out, sale or move that {@link #checkTake} let
   * through.
   *
   * @param caller the writer
   * @param op the op
   * @return the take
   */
  private static @NotNull PoolBookOut takeOf(
      @NotNull ExchangeCaller caller, @NotNull ExchangeOrgStockChangeSet.Op op) {
    ExchangeStockChangeSet.Quantity quantity = Objects.requireNonNull(op.quantity());
    String unit = quantity.unit();
    return new PoolBookOut(
        caller.member(),
        Objects.requireNonNull(op.entry()),
        op.unit(),
        Objects.requireNonNull(op.version()),
        round(quantity.amount(), unit).doubleValue(),
        unit,
        jobOrderEarmarks(op.fromJobOrders(), unit),
        missionEarmarks(op.fromMissions(), unit));
  }

  /**
   * Looks a holder up on a unit's roster.
   *
   * @param caller the client the refs are made for
   * @param unit the unit
   * @param ref the holder's ref
   * @param rosters each unit's roster read so far, ref to member
   * @return the member, or {@code null} when the ref is not on the roster
   */
  private @Nullable UUID holderOf(
      @NotNull ExchangeCaller caller,
      @NotNull UUID unit,
      @NotNull String ref,
      @NotNull Map<UUID, Map<String, UUID>> rosters) {
    return rosters.computeIfAbsent(unit, candidate -> rosterOf(caller, candidate)).get(ref);
  }

  /**
   * Reads a unit's roster as a map from ref to member.
   *
   * @param caller the client the refs are made for
   * @param unit the unit
   * @return the members by ref
   */
  private @NotNull Map<String, UUID> rosterOf(@NotNull ExchangeCaller caller, @NotNull UUID unit) {
    return roster.of(caller.clientId(), unit).stream()
        .collect(
            Collectors.toMap(
                ExchangeOrgStockRoster.Holder::ref,
                ExchangeOrgStockRoster.Holder::userId,
                (first, _) -> first,
                LinkedHashMap::new));
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

  /**
   * Whether a quantity and every earmark line carry the given unit.
   *
   * @param unit the unit
   * @param quantity the op's quantity
   * @param jobOrders the job-order lines, or {@code null}
   * @param missions the mission lines, or {@code null}
   * @return {@code true} when all match
   */
  private static boolean sameUnit(
      @NotNull String unit,
      @NotNull ExchangeStockChangeSet.Quantity quantity,
      @Nullable List<ExchangeOrgStockChangeSet.JobOrderLine> jobOrders,
      @Nullable List<ExchangeOrgStockChangeSet.MissionLine> missions) {
    return unit.equals(quantity.unit())
        && (jobOrders == null
            || jobOrders.stream().allMatch(line -> unit.equals(line.quantity().unit())))
        && (missions == null
            || missions.stream().allMatch(line -> unit.equals(line.quantity().unit())));
  }

  /**
   * Rounds job-order lines to the unit and drops those that round to nothing.
   *
   * @param lines the lines, or {@code null}
   * @param unit the unit
   * @return the earmarks
   */
  private static @NotNull List<PoolEarmark> jobOrderEarmarks(
      @Nullable List<ExchangeOrgStockChangeSet.JobOrderLine> lines, @NotNull String unit) {
    return lines == null
        ? List.of()
        : earmarks(
            lines,
            ExchangeOrgStockChangeSet.JobOrderLine::jobOrder,
            ExchangeOrgStockChangeSet.JobOrderLine::quantity,
            unit);
  }

  /**
   * Rounds mission lines to the unit and drops those that round to nothing.
   *
   * @param lines the lines, or {@code null}
   * @param unit the unit
   * @return the earmarks
   */
  private static @NotNull List<PoolEarmark> missionEarmarks(
      @Nullable List<ExchangeOrgStockChangeSet.MissionLine> lines, @NotNull String unit) {
    return lines == null
        ? List.of()
        : earmarks(
            lines,
            ExchangeOrgStockChangeSet.MissionLine::mission,
            ExchangeOrgStockChangeSet.MissionLine::quantity,
            unit);
  }

  /**
   * Rounds earmark lines to the unit and drops those that round to nothing.
   *
   * @param lines the lines
   * @param target each line's job order or mission
   * @param quantity each line's quantity
   * @param unit the unit
   * @param <T> the line type
   * @return the earmarks
   */
  private static <T> @NotNull List<PoolEarmark> earmarks(
      @NotNull List<T> lines,
      @NotNull Function<T, UUID> target,
      @NotNull Function<T, ExchangeStockChangeSet.Quantity> quantity,
      @NotNull String unit) {
    List<PoolEarmark> earmarks = new ArrayList<>();
    for (T line : lines) {
      BigDecimal amount = round(quantity.apply(line).amount(), unit);
      if (amount.signum() > 0) {
        earmarks.add(new PoolEarmark(target.apply(line), amount.doubleValue()));
      }
    }
    return List.copyOf(earmarks);
  }

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
  private record BookIn(@NotNull PoolBookIn request) implements PoolWrite {

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
  private record BookOut(@NotNull PoolBookOut request) implements PoolWrite {

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
  private record Sell(@NotNull PoolSale request) implements PoolWrite {

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
  private record Move(@NotNull PoolMove request, @NotNull ExchangeJournalAction action)
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
  private record Note(@NotNull PoolNote request) implements PoolWrite {

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
  private record MovePool(@NotNull PoolChange request) implements PoolWrite {

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
  private record Stolen(@NotNull PoolStolen request) implements PoolWrite {

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
  private record Allocate(@NotNull PoolAllocation request) implements PoolWrite {

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
