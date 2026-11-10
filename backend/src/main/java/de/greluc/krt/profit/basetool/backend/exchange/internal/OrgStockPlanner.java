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
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeChangeSetAdapter.Planned;
import de.greluc.krt.profit.basetool.backend.exchange.internal.OrgStockPlan.Allocate;
import de.greluc.krt.profit.basetool.backend.exchange.internal.OrgStockPlan.BookIn;
import de.greluc.krt.profit.basetool.backend.exchange.internal.OrgStockPlan.BookOut;
import de.greluc.krt.profit.basetool.backend.exchange.internal.OrgStockPlan.Move;
import de.greluc.krt.profit.basetool.backend.exchange.internal.OrgStockPlan.MovePool;
import de.greluc.krt.profit.basetool.backend.exchange.internal.OrgStockPlan.Note;
import de.greluc.krt.profit.basetool.backend.exchange.internal.OrgStockPlan.PoolWrite;
import de.greluc.krt.profit.basetool.backend.exchange.internal.OrgStockPlan.Sell;
import de.greluc.krt.profit.basetool.backend.exchange.internal.OrgStockPlan.Stolen;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeItemRef;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeOrgStockChangeSet;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeStockChangeSet;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.EarmarkChange;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.EarmarkDimension;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolAllocation;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolBookIn;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolBookOut;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolChange;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolEarmark;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolMove;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolNote;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolSale;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolStolen;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;

/** Validates org-stock operations and resolves their sealed execution plans. */
@Service
@RequiredArgsConstructor
class OrgStockPlanner {
  private final ExchangeOrgUnitService orgUnitService;
  private final ExchangeOrgStockRoster roster;
  private final ExchangeLotCatalogue lotCatalogue;
  private final ExchangeLocationResolver locationResolver;

  /** The refusal of an op on a pool or entry the writer may not write. */
  static final String NOT_PERMITTED = "NOT_PERMITTED";

  /** The refusal of a holder who is not on the unit's roster. */
  static final String HOLDER_UNKNOWN = "HOLDER_UNKNOWN";

  /** The refusal of a place without a Lager location. */
  static final String LOCATION_UNKNOWN = ExchangeReasons.LOCATION_UNKNOWN;

  /** The refusal of a quantity whose unit is not the material's. */
  static final String UNIT_MISMATCH = ExchangeReasons.UNIT_MISMATCH;

  /**
   * Decides every op on its own, before anything is locked.
   *
   * @param caller the client, installation and writer
   * @param changeSet the writes
   * @return one plan per op
   * @throws BadRequestException when an op lacks a field its kind needs
   */
  public @NotNull List<Planned<PoolWrite>> plan(
      @NotNull ExchangeCaller caller, @NotNull ExchangeOrgStockChangeSet changeSet) {
    List<ExchangeOrgStockChangeSet.Op> ops = changeSet.ops();
    ops.forEach(OrgStockPlanner::requireFields);
    Set<UUID> pools = orgUnitService.logisticianUnits(caller.member());
    Map<Integer, ExchangeLotCatalogue.Entry> catalogue = resolveBookIns(ops);
    Map<UUID, Map<String, UUID>> rosters = new HashMap<>();
    List<Planned<PoolWrite>> plan = new ArrayList<>(ops.size());
    for (int i = 0; i < ops.size(); i++) {
      ExchangeOrgStockChangeSet.Op op = ops.get(i);
      plan.add(
          switch (op.operation()) {
            case BOOK_IN ->
                planBookIn(caller, op, pools, Objects.requireNonNull(catalogue.get(i)), rosters);
            case SELL -> planSell(caller, op, pools);
            case MOVE_LOCATION -> planMoveLocation(caller, op, pools);
            case MOVE_HOLDER -> planMoveHolder(caller, op, pools, rosters);
            case MOVE_POOL -> planMovePool(caller, op, pools);
            case NOTE -> planNote(caller, op, pools);
            case STOLEN -> planStolen(caller, op, pools);
            case BOOK_OUT -> planBookOut(caller, op, pools);
            case ALLOCATION_ADD -> planAllocation(op, pools, EarmarkChange.ADD);
            case ALLOCATION_CHANGE -> planAllocation(op, pools, EarmarkChange.CHANGE);
            case ALLOCATION_REMOVE -> planAllocation(op, pools, EarmarkChange.REMOVE);
          });
    }
    return plan;
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
        switch (op.operation()) {
          case BOOK_IN ->
              op.holder() != null
                  && op.material() != null
                  && op.location() != null
                  && op.quality() != null
                  && op.stolen() != null
                  && op.quantity() != null;
          case SELL ->
              entry
                  && op.quantity() != null
                  && op.terminal() != null
                  && !op.terminal().isBlank()
                  && op.proceeds() != null;
          case MOVE_LOCATION -> entry && op.quantity() != null && op.location() != null;
          case MOVE_HOLDER ->
              entry && op.quantity() != null && op.holder() != null && op.toUnit() != null;
          case MOVE_POOL -> entry && op.toUnit() != null;
          case NOTE -> entry;
          case STOLEN -> entry && op.stolen() != null;
          case BOOK_OUT -> entry && op.quantity() != null;
          case ALLOCATION_ADD, ALLOCATION_CHANGE -> entry && op.quantity() != null && oneTarget(op);
          case ALLOCATION_REMOVE -> entry && oneTarget(op);
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
   * @return the catalogue entry of each book-in, by op index; a retired item ends its op
   */
  private @NotNull Map<Integer, ExchangeLotCatalogue.Entry> resolveBookIns(
      @NotNull List<ExchangeOrgStockChangeSet.Op> ops) {
    List<Integer> indexes = new ArrayList<>();
    List<ExchangeItemRef> refs = new ArrayList<>();
    for (int i = 0; i < ops.size(); i++) {
      if (ops.get(i).operation() == ExchangeOrgStockChangeSet.Operation.BOOK_IN) {
        indexes.add(i);
        refs.add(Objects.requireNonNull(ops.get(i).material()));
      }
    }
    if (refs.isEmpty()) {
      return Map.of();
    }
    Map<Integer, ExchangeLotCatalogue.Entry> byRef = lotCatalogue.resolve(refs);
    Map<Integer, ExchangeLotCatalogue.Entry> byOp = new HashMap<>();
    byRef.forEach((index, entry) -> byOp.put(indexes.get(index), entry.forBookIn()));
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
}
