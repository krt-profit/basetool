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
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolOutcome;
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
 * Lager's own create and book-out, each op on its own, journaled under the writer (REQ-XCH-045,
 * REQ-XCH-046, ADR-0257); and lists the roster a book-in names its holder from.
 *
 * <p>An op on a pool whose unit the writer is not a Logistician and direct member of is refused
 * {@code NOT_PERMITTED}, and so is a {@code book-out} of an entry that is personal or lies in
 * another pool. Every holder lot the batch touches is locked before any op runs (ADR-0229).
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
   * @return the counts, the offers the book-outs lowered or removed, and the detail of every op
   *     that was not applied
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
          ExchangeOrgStockChangeSet.BOOK_IN.equals(op.op())
              ? planBookIn(caller, op, pools, Objects.requireNonNull(catalogue.get(i)), rosters)
              : planBookOut(caller, op, pools));
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
      PoolOutcome outcome;
      ExchangeJournalAction action;
      switch (plan.get(i)) {
        case Skip skip -> {
          if (UNCHANGED.equals(skip.result())) {
            unchanged++;
          }
          count(caller, skip.result(), dryRun);
          results.add(new ExchangeChangeResultDto.OpResult(i, opId, skip.result(), skip.reason()));
          continue;
        }
        case BookIn bookIn -> {
          outcome = poolEntryCommands.bookIn(bookIn.request(), dryRun);
          action = ExchangeJournalAction.ORG_STOCK_BOOK_IN;
        }
        case BookOut bookOut -> {
          outcome = poolEntryCommands.bookOut(bookOut.request(), dryRun);
          action = ExchangeJournalAction.ORG_STOCK_BOOK_OUT;
        }
      }
      if (outcome.refusal() != null) {
        count(caller, REJECTED, dryRun);
        results.add(
            new ExchangeChangeResultDto.OpResult(i, opId, REJECTED, outcome.refusal().name()));
        continue;
      }
      applied++;
      count(caller, APPLIED, dryRun);
      reduced += outcome.offers().reduced();
      removed += outcome.offers().removed();
      if (!dryRun) {
        journal(caller, batch, action, outcome);
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
    boolean complete =
        ExchangeOrgStockChangeSet.BOOK_IN.equals(op.op())
            ? op.holder() != null
                && op.material() != null
                && op.location() != null
                && op.quality() != null
                && op.stolen() != null
            : op.entry() != null && op.version() != null;
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
    UUID holder =
        rosters
            .computeIfAbsent(op.unit(), unit -> rosterOf(caller, unit))
            .get(Objects.requireNonNull(op.holder()));
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
    if (!sameUnit(unit, op.quantity(), op.jobOrders(), op.missions())) {
      return new Skip(REJECTED, UNIT_MISMATCH);
    }
    BigDecimal amount = round(op.quantity().amount(), unit);
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
    if (!pools.contains(op.unit())) {
      return new Skip(REJECTED, NOT_PERMITTED);
    }
    String unit = op.quantity().unit();
    if (!sameUnit(unit, op.quantity(), op.fromJobOrders(), op.fromMissions())) {
      return new Skip(REJECTED, UNIT_MISMATCH);
    }
    BigDecimal amount = round(op.quantity().amount(), unit);
    if (amount.signum() == 0) {
      return new Skip(UNCHANGED, null);
    }
    return new BookOut(
        new PoolBookOut(
            caller.member(),
            Objects.requireNonNull(op.entry()),
            op.unit(),
            Objects.requireNonNull(op.version()),
            amount.doubleValue(),
            unit,
            jobOrderEarmarks(op.fromJobOrders(), unit),
            missionEarmarks(op.fromMissions(), unit)));
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
    for (Planned planned : plan) {
      switch (planned) {
        case BookIn bookIn ->
            holderLots
                .computeIfAbsent(bookIn.request().holder(), _ -> new ArrayList<>())
                .add(bookIn.request().lot());
        case BookOut bookOut -> entries.add(bookOut.request().entryId());
        case Skip _ -> {}
      }
    }
    if (!holderLots.isEmpty() || !entries.isEmpty()) {
      poolEntryCommands.lockLots(holderLots, entries);
    }
  }

  /**
   * Journals one applied op under the writer, one entry per Lager entry it changed, keyed by that
   * entry.
   *
   * @param caller the writer
   * @param batch the change set's id
   * @param action what the op did
   * @param outcome its outcome
   */
  private void journal(
      @NotNull ExchangeCaller caller,
      @NotNull UUID batch,
      @NotNull ExchangeJournalAction action,
      @NotNull PoolOutcome outcome) {
    for (PoolEntryCommands.PoolEntryChange change : outcome.changes()) {
      PoolEntryCommands.PoolEntryState key =
          change.after() != null ? change.after() : Objects.requireNonNull(change.before());
      journalService.record(
          caller,
          batch,
          action,
          key.id().toString(),
          action == ExchangeJournalAction.ORG_STOCK_BOOK_OUT,
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
  private sealed interface Planned permits Skip, BookIn, BookOut {}

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
}
