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

import static de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeOps.APPLIED;
import static de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeOps.REJECTED;
import static de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeOps.UNCHANGED;
import static de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeOps.round;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeChangeResultDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeItemRef;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeOrgStockChangeSet;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeOrgStockMembersDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeOrgStockUnitDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeOrgUnitDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeStockChangeSet;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolBookIn;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolBookOut;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolEarmark;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolMove;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolNote;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolOutcome;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolRelocation;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolSale;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolStolen;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies a Logistician's org-stock writes to the shared entries of their units' pools through the
 * Lager's own create, book-out, sale, transfer, note edit and stolen marking, each op on its own,
 * journaled under the writer (REQ-XCH-045, REQ-XCH-046, ADR-0257); and lists the roster a book-in
 * or a {@code move-holder} names its holder from.
 *
 * <p>An op on a pool whose unit the writer is not a Logistician and direct member of is refused
 * {@code NOT_PERMITTED}, and so is a {@code move-holder} into such a pool and an op on an entry
 * that is personal or lies in another pool. Every holder lot the batch touches is locked before any
 * op runs (ADR-0229).
 */
@Service
@RequiredArgsConstructor
public class ExchangeOrgStockWriteService {

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
  private final ExchangeLotCatalogue lotCatalogue;
  private final ExchangeLocationResolver locationResolver;
  private final PoolEntryCommands poolEntryCommands;
  private final ExchangeJournalService journalService;
  private final ExchangeLiveSync liveSync;
  private final MeterRegistry meterRegistry;

  /**
   * Applies one change set in one transaction, op by op in its order.
   *
   * @param caller the client, installation and writer
   * @param changeSet the writes
   * @return the counts, the offers the ops lowered or removed, and the detail of every op that was
   *     not applied
   * @throws BadRequestException when an op lacks a field its kind needs
   */
  @Transactional
  public @NotNull ExchangeChangeResultDto apply(
      @NotNull ExchangeCaller caller, @NotNull ExchangeOrgStockChangeSet changeSet) {
    List<ExchangeOrgStockChangeSet.Op> ops = changeSet.ops();
    ops.forEach(ExchangeOrgStockWriteService::requireFields);
    Set<UUID> pools = orgUnitService.logisticianUnits(caller.member());
    Map<Integer, ExchangeLotCatalogue.Entry> catalogue = resolveBookIns(ops);
    Map<UUID, Map<String, UUID>> rosters = new HashMap<>();
    List<Planned> plan = new ArrayList<>(ops.size());
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
            case ExchangeOrgStockChangeSet.NOTE -> planNote(caller, op, pools);
            case ExchangeOrgStockChangeSet.STOLEN -> planStolen(caller, op, pools);
            case ExchangeOrgStockChangeSet.BOOK_OUT -> planBookOut(caller, op, pools);
            default -> throw new BadRequestException("Unknown org-stock op");
          });
    }
    lock(plan);
    boolean dryRun = changeSet.dryRun();
    UUID batch = UUID.randomUUID();
    int applied = 0;
    int unchanged = 0;
    int reduced = 0;
    int removed = 0;
    List<ExchangeChangeResultDto.OpResult> results = new ArrayList<>();
    for (int i = 0; i < plan.size(); i++) {
      String opId = ops.get(i).opId();
      Planned planned = plan.get(i);
      if (planned instanceof Skip skip) {
        if (UNCHANGED.equals(skip.result())) {
          unchanged++;
        }
        count(caller, skip.result(), dryRun);
        results.add(new ExchangeChangeResultDto.OpResult(i, opId, skip.result(), skip.reason()));
        continue;
      }
      PoolOutcome outcome = run(planned, dryRun);
      if (outcome.refusal() != null) {
        count(caller, REJECTED, dryRun);
        results.add(
            new ExchangeChangeResultDto.OpResult(i, opId, REJECTED, outcome.refusal().name()));
        continue;
      }
      if (outcome.unchanged()) {
        unchanged++;
        count(caller, UNCHANGED, dryRun);
        results.add(new ExchangeChangeResultDto.OpResult(i, opId, UNCHANGED, null));
        continue;
      }
      applied++;
      count(caller, APPLIED, dryRun);
      reduced += outcome.offers().reduced();
      removed += outcome.offers().removed();
      if (!dryRun) {
        journal(caller, batch, planned, outcome);
      }
    }
    if (!dryRun && applied > 0) {
      liveSync.stockChanged(reduced + removed > 0);
    }
    return new ExchangeChangeResultDto(
        dryRun,
        applied,
        unchanged,
        plan.size() - applied - unchanged,
        List.copyOf(results),
        reduced,
        removed,
        null);
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
    if (!orgUnitService.logisticianUnits(caller.member()).contains(unit)) {
      throw new AccessDeniedException("The member is not a Logistician of the unit");
    }
    ExchangeOrgUnitDto named =
        orgUnitService.orgUnits(caller.member()).items().stream()
            .filter(candidate -> candidate.id().equals(unit))
            .findFirst()
            .orElseThrow(() -> new AccessDeniedException("The member does not belong to the unit"));
    return new ExchangeOrgStockMembersDto(
        new ExchangeOrgStockUnitDto(named.id(), named.name()),
        roster.of(caller.clientId(), unit).stream()
            .map(holder -> new ExchangeOrgStockMembersDto.Member(holder.ref(), holder.name()))
            .toList());
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
          case ExchangeOrgStockChangeSet.NOTE -> entry;
          case ExchangeOrgStockChangeSet.STOLEN -> entry && op.stolen() != null;
          case ExchangeOrgStockChangeSet.BOOK_OUT -> entry && op.quantity() != null;
          default -> false;
        };
    if (!complete) {
      throw new BadRequestException("An org-stock op lacks a field its kind needs");
    }
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
  private @NotNull Planned planBookIn(
      @NotNull ExchangeCaller caller,
      @NotNull ExchangeOrgStockChangeSet.Op op,
      @NotNull Set<UUID> pools,
      @NotNull ExchangeLotCatalogue.Entry entry,
      @NotNull Map<UUID, Map<String, UUID>> rosters) {
    if (!pools.contains(op.unit())) {
      return new Skip(REJECTED, NOT_PERMITTED);
    }
    UUID holder = holderOf(caller, op.unit(), Objects.requireNonNull(op.holder()), rosters);
    if (holder == null) {
      return new Skip(REJECTED, HOLDER_UNKNOWN);
    }
    if (entry.result() != null) {
      return new Skip(entry.result(), entry.reason());
    }
    Optional<Location> location = locationResolver.resolve(Objects.requireNonNull(op.location()));
    if (location.isEmpty()) {
      return new Skip(REJECTED, LOCATION_UNKNOWN);
    }
    String unit = entry.unit();
    ExchangeStockChangeSet.Quantity quantity = Objects.requireNonNull(op.quantity());
    if (!sameUnit(unit, quantity, op.jobOrders(), op.missions())) {
      return new Skip(REJECTED, UNIT_MISMATCH);
    }
    BigDecimal amount = round(quantity.amount(), unit);
    if (amount.signum() == 0) {
      return new Skip(UNCHANGED, null);
    }
    StockLot lot =
        new StockLot(
            entry.material(),
            entry.gameItem(),
            location.get(),
            entry.material() == null ? null : op.quality(),
            Boolean.TRUE.equals(op.stolen()));
    return new BookIn(
        new PoolBookIn(
            holder,
            op.unit(),
            lot,
            amount.doubleValue(),
            op.note(),
            Boolean.TRUE.equals(op.mergeScu()),
            jobOrderEarmarks(op.jobOrders(), unit),
            missionEarmarks(op.missions(), unit)));
  }

  /**
   * Decides what a book-out will do, before anything is locked.
   *
   * @param caller the writer
   * @param op the op
   * @param pools the units the writer is a Logistician and direct member of
   * @return the plan
   */
  private static @NotNull Planned planBookOut(
      @NotNull ExchangeCaller caller,
      @NotNull ExchangeOrgStockChangeSet.Op op,
      @NotNull Set<UUID> pools) {
    Skip skip = checkTake(op, pools);
    return skip != null ? skip : new BookOut(takeOf(caller, op));
  }

  /**
   * Decides what a sale will do, before anything is locked.
   *
   * @param caller the writer
   * @param op the op
   * @param pools the units the writer is a Logistician and direct member of
   * @return the plan
   */
  private static @NotNull Planned planSell(
      @NotNull ExchangeCaller caller,
      @NotNull ExchangeOrgStockChangeSet.Op op,
      @NotNull Set<UUID> pools) {
    Skip skip = checkTake(op, pools);
    return skip != null
        ? skip
        : new Sell(
            new PoolSale(
                takeOf(caller, op),
                Objects.requireNonNull(op.terminal()).trim(),
                Objects.requireNonNull(op.proceeds())));
  }

  /**
   * Decides what a move to another location will do, before anything is locked.
   *
   * @param caller the writer
   * @param op the op
   * @param pools the units the writer is a Logistician and direct member of
   * @return the plan
   */
  private @NotNull Planned planMoveLocation(
      @NotNull ExchangeCaller caller,
      @NotNull ExchangeOrgStockChangeSet.Op op,
      @NotNull Set<UUID> pools) {
    Skip skip = checkTake(op, pools);
    if (skip != null) {
      return skip;
    }
    Optional<Location> location = locationResolver.resolve(Objects.requireNonNull(op.location()));
    if (location.isEmpty()) {
      return new Skip(REJECTED, LOCATION_UNKNOWN);
    }
    return new Move(
        new PoolMove(
            takeOf(caller, op),
            null,
            location.get(),
            op.unit(),
            Boolean.TRUE.equals(op.mergeScu())),
        ExchangeJournalAction.ORG_STOCK_MOVE_LOCATION);
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
  private @NotNull Planned planMoveHolder(
      @NotNull ExchangeCaller caller,
      @NotNull ExchangeOrgStockChangeSet.Op op,
      @NotNull Set<UUID> pools,
      @NotNull Map<UUID, Map<String, UUID>> rosters) {
    UUID toUnit = Objects.requireNonNull(op.toUnit());
    if (!pools.contains(toUnit)) {
      return new Skip(REJECTED, NOT_PERMITTED);
    }
    Skip skip = checkTake(op, pools);
    if (skip != null) {
      return skip;
    }
    UUID holder = holderOf(caller, toUnit, Objects.requireNonNull(op.holder()), rosters);
    if (holder == null) {
      return new Skip(REJECTED, HOLDER_UNKNOWN);
    }
    return new Move(
        new PoolMove(takeOf(caller, op), holder, null, toUnit, Boolean.TRUE.equals(op.mergeScu())),
        ExchangeJournalAction.ORG_STOCK_MOVE_HOLDER);
  }

  /**
   * Decides what a note edit will do, before anything is locked.
   *
   * @param caller the writer
   * @param op the op
   * @param pools the units the writer is a Logistician and direct member of
   * @return the plan
   */
  private static @NotNull Planned planNote(
      @NotNull ExchangeCaller caller,
      @NotNull ExchangeOrgStockChangeSet.Op op,
      @NotNull Set<UUID> pools) {
    if (!pools.contains(op.unit())) {
      return new Skip(REJECTED, NOT_PERMITTED);
    }
    return new Note(
        new PoolNote(
            caller.member(),
            Objects.requireNonNull(op.entry()),
            op.unit(),
            Objects.requireNonNull(op.version()),
            op.note()));
  }

  /**
   * Decides what a stolen marking will do, before anything is locked.
   *
   * @param caller the writer
   * @param op the op
   * @param pools the units the writer is a Logistician and direct member of
   * @return the plan
   */
  private static @NotNull Planned planStolen(
      @NotNull ExchangeCaller caller,
      @NotNull ExchangeOrgStockChangeSet.Op op,
      @NotNull Set<UUID> pools) {
    if (!pools.contains(op.unit())) {
      return new Skip(REJECTED, NOT_PERMITTED);
    }
    ExchangeStockChangeSet.Quantity quantity = op.quantity();
    Double amount = null;
    if (quantity != null) {
      BigDecimal rounded = round(quantity.amount(), quantity.unit());
      if (rounded.signum() == 0) {
        return new Skip(UNCHANGED, null);
      }
      amount = rounded.doubleValue();
    }
    return new Stolen(
        new PoolStolen(
            caller.member(),
            Objects.requireNonNull(op.entry()),
            op.unit(),
            Objects.requireNonNull(op.version()),
            Boolean.TRUE.equals(op.stolen()),
            amount,
            quantity == null ? null : quantity.unit()));
  }

  /**
   * Decides the part of a book-out, sale or move that needs no entry: the pool, the units of the
   * amount and the plans, and an amount that rounds to nothing.
   *
   * @param op the op
   * @param pools the units the writer is a Logistician and direct member of
   * @return the result that ends the op, or {@code null} when the Lager decides it
   */
  private static @Nullable Skip checkTake(
      @NotNull ExchangeOrgStockChangeSet.Op op, @NotNull Set<UUID> pools) {
    if (!pools.contains(op.unit())) {
      return new Skip(REJECTED, NOT_PERMITTED);
    }
    ExchangeStockChangeSet.Quantity quantity = Objects.requireNonNull(op.quantity());
    String unit = quantity.unit();
    if (!sameUnit(unit, quantity, op.fromJobOrders(), op.fromMissions())) {
      return new Skip(REJECTED, UNIT_MISMATCH);
    }
    if (round(quantity.amount(), unit).signum() == 0) {
      return new Skip(UNCHANGED, null);
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
  private void lock(@NotNull List<Planned> plan) {
    Map<UUID, List<StockLot>> holderLots = new HashMap<>();
    Set<UUID> entries = new HashSet<>();
    List<PoolRelocation> relocations = new ArrayList<>();
    for (Planned planned : plan) {
      switch (planned) {
        case BookIn bookIn ->
            holderLots
                .computeIfAbsent(bookIn.request().holder(), _ -> new ArrayList<>())
                .add(bookIn.request().lot());
        case BookOut bookOut -> entries.add(bookOut.request().entryId());
        case Sell sell -> entries.add(sell.request().take().entryId());
        case Move move -> {
          PoolMove request = move.request();
          relocations.add(
              new PoolRelocation(
                  request.take().entryId(),
                  request.targetHolder(),
                  request.targetLocation() == null ? null : request.targetLocation().getId(),
                  null));
        }
        case Note note -> entries.add(note.request().entryId());
        case Stolen stolen ->
            relocations.add(
                new PoolRelocation(
                    stolen.request().entryId(), null, null, stolen.request().stolen()));
        case Skip _ -> {}
      }
    }
    if (!holderLots.isEmpty() || !entries.isEmpty() || !relocations.isEmpty()) {
      poolEntryCommands.lockLots(holderLots, entries, relocations);
    }
  }

  /**
   * Hands one planned op to the Lager.
   *
   * @param planned the op, not a {@link Skip}
   * @param dryRun whether to check it only
   * @return the Lager's outcome
   */
  private @NotNull PoolOutcome run(@NotNull Planned planned, boolean dryRun) {
    return switch (planned) {
      case BookIn bookIn -> poolEntryCommands.bookIn(bookIn.request(), dryRun);
      case BookOut bookOut -> poolEntryCommands.bookOut(bookOut.request(), dryRun);
      case Sell sell -> poolEntryCommands.sell(sell.request(), dryRun);
      case Move move -> poolEntryCommands.move(move.request(), dryRun);
      case Note note -> poolEntryCommands.setNote(note.request(), dryRun);
      case Stolen stolen -> poolEntryCommands.markStolen(stolen.request(), dryRun);
      case Skip _ -> throw new IllegalStateException("A skipped op reaches no Lager command");
    };
  }

  /**
   * Journals one applied op under the writer, one entry per Lager entry it changed, keyed by that
   * entry; the entry a book-out, a sale or a move out of the pool takes stock from counts as a
   * removal.
   *
   * @param caller the writer
   * @param batch the change set's id
   * @param planned the op
   * @param outcome its outcome
   */
  private void journal(
      @NotNull ExchangeCaller caller,
      @NotNull UUID batch,
      @NotNull Planned planned,
      @NotNull PoolOutcome outcome) {
    ExchangeJournalAction action = actionOf(planned);
    UUID removedFrom = removedFrom(planned);
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
   * Maps a planned op to the {@code exchange_journal} action its entries are recorded under.
   *
   * @param planned the op, not a {@link Skip}
   * @return the action; a move's is the one it was planned with
   */
  private static @NotNull ExchangeJournalAction actionOf(@NotNull Planned planned) {
    return switch (planned) {
      case BookIn _ -> ExchangeJournalAction.ORG_STOCK_BOOK_IN;
      case BookOut _ -> ExchangeJournalAction.ORG_STOCK_BOOK_OUT;
      case Sell _ -> ExchangeJournalAction.ORG_STOCK_SELL;
      case Move move -> move.action();
      case Note _ -> ExchangeJournalAction.ORG_STOCK_NOTE;
      case Stolen _ -> ExchangeJournalAction.ORG_STOCK_STOLEN;
      case Skip _ -> throw new IllegalStateException("A skipped op is not journaled");
    };
  }

  /**
   * Returns the entry whose journal row counts as a removal for the mass-change guard.
   *
   * @param planned the op
   * @return the entry a book-out, a sale or a move into another pool takes stock from, or {@code
   *     null}
   */
  private static @Nullable UUID removedFrom(@NotNull Planned planned) {
    return switch (planned) {
      case BookOut bookOut -> bookOut.request().entryId();
      case Sell sell -> sell.request().take().entryId();
      case Move move ->
          move.request().targetPoolUnitId().equals(move.request().take().poolUnitId())
              ? null
              : move.request().take().entryId();
      case BookIn _, Note _, Stolen _, Skip _ -> null;
    };
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

  /**
   * Counts one op's outcome for the caller's client, unless nothing is written.
   *
   * @param caller the caller, whose client the relay bounded by the registry
   * @param outcome the outcome
   * @param dryRun whether the change set only plans
   */
  private void count(@NotNull ExchangeCaller caller, @NotNull String outcome, boolean dryRun) {
    if (dryRun) {
      return;
    }
    Counter counter =
        meterRegistry.counter(
            MetricNames.EXCHANGE_WRITES,
            MetricNames.TAG_CLIENT_ID,
            caller.clientId(),
            MetricNames.TAG_RESOURCE,
            ExchangeResource.ORG_STOCK.name().toLowerCase(Locale.ROOT),
            MetricNames.TAG_OUTCOME,
            outcome);
    counter.increment();
  }

  /** What one op of a change set will do. */
  private sealed interface Planned permits Skip, BookIn, BookOut, Sell, Move, Note, Stolen {}

  /**
   * An op that is decided without the Lager.
   *
   * @param result its result
   * @param reason its refusal code, or {@code null}
   */
  private record Skip(@NotNull String result, @Nullable String reason) implements Planned {}

  /**
   * A book-in the Lager decides.
   *
   * @param request the book-in
   */
  private record BookIn(@NotNull PoolBookIn request) implements Planned {}

  /**
   * A book-out the Lager decides.
   *
   * @param request the book-out
   */
  private record BookOut(@NotNull PoolBookOut request) implements Planned {}

  /**
   * A sale the Lager decides.
   *
   * @param request the sale
   */
  private record Sell(@NotNull PoolSale request) implements Planned {}

  /**
   * A move to another location or holder the Lager decides.
   *
   * @param request the move
   * @param action {@code ORG_STOCK_MOVE_LOCATION} or {@code ORG_STOCK_MOVE_HOLDER}
   */
  private record Move(@NotNull PoolMove request, @NotNull ExchangeJournalAction action)
      implements Planned {}

  /**
   * A note edit the Lager decides.
   *
   * @param request the note edit
   */
  private record Note(@NotNull PoolNote request) implements Planned {}

  /**
   * A stolen marking the Lager decides.
   *
   * @param request the marking
   */
  private record Stolen(@NotNull PoolStolen request) implements Planned {}
}
