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

import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeRefineryOrderReferences.Refs;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeChangeResultDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeRefineryOrderChangeSet;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeRefineryOrderChangeSet.Kind;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeRefineryOrderChangeSet.Op;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeRefineryOrderDto;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeEdit;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeOrder;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeReader;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeStoreLine;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeWriter;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryOrderStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies a client's writes to the member's own refinery orders (REQ-XCH-052, REQ-XCH-053,
 * REQ-XCH-055, REQ-XCH-056, ADR-0351): creates under the client's key, partial updates, goods ops,
 * cancels, deletes and pickups with or without store lines, each through the refinery module's own
 * writer, decided in order against the order as the earlier ops left it, and journaled for the
 * undo. The {@link ExchangeChangeSetRunner} runs the change set (ADR-0285); a delete counts for the
 * mass-change guard.
 *
 * <p>References resolve exactly in the plan; everything that depends on the order — its address, an
 * earlier refusal, the counters, the goods — is decided when the op runs, so a refusal of one op
 * refuses every later op on the same order ({@code PRECEDING_OP_REJECTED}). A dry run decides every
 * op against the {@link ExchangeRefineryOrderView} the earlier ops would leave and writes nothing.
 */
@Service
@RequiredArgsConstructor
public class ExchangeRefineryOrderWriteService
    implements ExchangeChangeSetAdapter<
        ExchangeRefineryOrderChangeSet, ExchangeRefineryOrderWriteService.Change> {

  private final ExchangeRefineryOrderReferences references;
  private final RefineryExchangeWriter writer;
  private final ExchangeRefineryOrderLinkRepository linkRepository;
  private final ExchangeJournalService journalService;
  private final ExchangeChangeSetRunner runner;
  private final ExchangeLiveSync liveSync;
  private final ExchangeMassChangeGuard guard;
  private final RefineryExchangeReader reader;

  /**
   * Plans and applies one change set in one transaction.
   *
   * @param caller the client, installation and member
   * @param changeSet the writes
   * @return the counts, the order every applied op wrote and the detail of every op that was not
   *     applied
   * @throws BadRequestException when an op lacks a field its kind requires
   */
  @Transactional
  public @NotNull ExchangeChangeResultDto apply(
      @NotNull ExchangeCaller caller, @NotNull ExchangeRefineryOrderChangeSet changeSet) {
    return runner.run(caller, this, changeSet, true);
  }

  @Override
  public @NotNull ExchangeResource resource() {
    return ExchangeResource.REFINERY_ORDER;
  }

  @Override
  public boolean dryRun(@NotNull ExchangeRefineryOrderChangeSet changeSet) {
    return changeSet.dryRun();
  }

  @Override
  public @Nullable String opId(@NotNull ExchangeRefineryOrderChangeSet changeSet, int index) {
    return changeSet.ops().get(index).opId();
  }

  /**
   * Applies a change set the member confirmed after the mass-change guard held it back, without
   * asking the guard again (REQ-XCH-021).
   *
   * @param caller the client, installation and member the change set was staged for
   * @param changeSet the writes
   * @return the counts, the order every applied op wrote and the detail of every op that was not
   *     applied
   * @throws BadRequestException when an op lacks a field its kind requires
   */
  @Transactional
  public @NotNull ExchangeChangeResultDto applyConfirmed(
      @NotNull ExchangeCaller caller, @NotNull ExchangeRefineryOrderChangeSet changeSet) {
    return runner.run(caller, this, changeSet, false);
  }

  /**
   * Counts the set's deletes against the member's orders; a delete is the only removal, so a cancel
   * or a goods removal never holds a set back (REQ-XCH-055).
   *
   * @param caller the caller
   * @param changeSet the writes
   * @param plan the plan
   * @return {@code true} when the member must confirm the set
   */
  @Override
  public boolean holdsBack(
      @NotNull ExchangeCaller caller,
      @NotNull ExchangeRefineryOrderChangeSet changeSet,
      @NotNull List<Planned<Change>> plan) {
    long removals =
        plan.stream()
            .filter(
                p -> p instanceof Write<Change>(Change change) && change.op().kind() == Kind.DELETE)
            .count();
    return guard.requiresConfirmation(
        caller, ExchangeResource.REFINERY_ORDER, reader.count(caller.member()), removals);
  }

  /**
   * Checks every op's fields and resolves its references exactly; nothing is locked yet.
   *
   * @param caller the caller
   * @param changeSet the writes
   * @return one write per op, carrying its resolved references or the refusal they lead to
   * @throws BadRequestException when an op lacks a field its kind requires
   */
  @Override
  public @NotNull List<Planned<Change>> plan(
      @NotNull ExchangeCaller caller, @NotNull ExchangeRefineryOrderChangeSet changeSet) {
    List<Op> ops = changeSet.ops();
    ops.forEach(ExchangeRefineryOrderWriteService::requireFields);
    List<Refs> refs = references.resolve(caller, ops);
    List<Planned<Change>> plan = new ArrayList<>(ops.size());
    for (int i = 0; i < ops.size(); i++) {
      plan.add(ExchangeChangeSetAdapter.write(new Change(ops.get(i), refs.get(i))));
    }
    return plan;
  }

  /**
   * Decides and writes each op in order against the orders as the earlier ops left them, and
   * refreshes the open refinery pages of every order it wrote afterwards.
   *
   * @param caller the caller
   * @param batch the change set's id in the journal
   * @param plan the plan
   * @param dryRun whether nothing is written
   * @return the execution
   */
  @Override
  public @NotNull Execution<Change> start(
      @NotNull ExchangeCaller caller,
      @NotNull UUID batch,
      @NotNull List<Planned<Change>> plan,
      boolean dryRun) {
    return new Run(caller, batch, dryRun);
  }

  /**
   * Refuses an op that lacks a field its kind needs, or a store line that earmarks personal stock;
   * the gateway's schema keeps such an op from arriving.
   *
   * @param op the op
   * @throws BadRequestException when a field is missing
   */
  private static void requireFields(@NotNull Op op) {
    boolean addressed = op.id() != null || op.clientKey() != null;
    ExchangeRefineryOrderChangeSet.Versions versions = op.versions();
    boolean gameDataEcho = versions != null && versions.gameData() != null;
    boolean financesEcho = versions != null && versions.finances() != null;
    boolean complete =
        switch (op.kind()) {
          case CREATE -> op.clientKey() != null && op.orgUnit() != null && op.location() != null;
          case UPDATE -> addressed && op.versions() != null;
          case GOOD_ADD -> addressed && gameDataEcho && op.good() != null;
          case GOOD_UPDATE, GOOD_REMOVE -> addressed && gameDataEcho && op.goodId() != null;
          case CANCEL, PICKUP -> addressed && gameDataEcho;
          case DELETE -> addressed && gameDataEcho && financesEcho;
        };
    boolean personalEarmark =
        op.lines() != null
            && op.lines().stream()
                .anyMatch(line -> Boolean.TRUE.equals(line.personal()) && line.jobOrder() != null);
    if (!complete || personalEarmark || (op.durationMinutes() != null && op.endsAt() == null)) {
      throw new BadRequestException("A refinery-order op lacks a field its kind needs");
    }
  }

  /**
   * Derives the stored timer from the end a client reports (REQ-XCH-052).
   *
   * @param endsAt when the run ends
   * @param durationMinutes the run's length, or {@code null} to start the run at the write
   * @param now the time of the write
   * @return {@code startedAt = endsAt - durationMinutes}; without a length the rest until {@code
   *     endsAt} in whole minutes, rounded up, so the stored end stays {@code endsAt}
   */
  static @NotNull RefineryExchangeEdit.Timer timer(
      @NotNull Instant endsAt, @Nullable Long durationMinutes, @NotNull Instant now) {
    long minutes =
        durationMinutes != null
            ? durationMinutes
            : Math.max(0L, (Duration.between(now, endsAt).toSeconds() + 59L) / 60L);
    return new RefineryExchangeEdit.Timer(endsAt.minus(Duration.ofMinutes(minutes)), minutes);
  }

  /** The decisions of one change set, op by op, against the orders as the earlier ops left them. */
  @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
  private final class Run implements Execution<Change> {

    /** The caller. */
    private final ExchangeCaller caller;

    /** The change set's id in the journal. */
    private final UUID batch;

    /** Whether nothing is written. */
    private final boolean dryRun;

    /** When the set runs; a timer without a length starts here. */
    private final Instant now = Instant.now();

    /** The orders the set has met, by id. */
    private final Map<UUID, Slot> byId = new HashMap<>();

    /** The orders the set has met, by the client's key. */
    private final Map<String, Slot> byKey = new HashMap<>();

    /** The orders the set wrote and kept. */
    private final Set<UUID> written = new LinkedHashSet<>();

    /** The orders among them whose yield a pickup of the set booked into stock. */
    private final Set<UUID> stored = new LinkedHashSet<>();

    /** The orders the set deleted. */
    private final Set<UUID> deleted = new LinkedHashSet<>();

    @Override
    public @NotNull Outcome run(@NotNull Change change) {
      Op op = change.op();
      Kind kind = op.kind();
      if (kind == Kind.CREATE) {
        return create(change);
      }
      Slot slot = address(op);
      if (slot == null || slot.deleted) {
        return Outcome.rejected(ExchangeRefineryReasons.ORDER_UNKNOWN);
      }
      if (slot.refused) {
        return Outcome.rejected(ExchangeRefineryReasons.PRECEDING_OP_REJECTED);
      }
      Outcome outcome = change.refs().refusal();
      if (outcome == null) {
        outcome =
            switch (kind) {
              case CREATE -> throw new IllegalStateException("A create has no order to address");
              case UPDATE -> update(slot, change);
              case GOOD_ADD -> goodAdd(slot, change);
              case GOOD_UPDATE -> goodUpdate(slot, change);
              case GOOD_REMOVE -> goodRemove(slot, change);
              case CANCEL -> cancel(slot, op);
              case DELETE -> delete(slot, op);
              case PICKUP -> pickup(slot, change);
            };
      }
      if (!ExchangeOps.APPLIED.equals(outcome.result())
          && !ExchangeOps.UNCHANGED.equals(outcome.result())) {
        slot.refused = true;
      }
      return outcome;
    }

    @Override
    public @NotNull Effects effects() {
      return Effects.NONE;
    }

    @Override
    public void announce() {
      liveSync.refineryOrdersChanged(written, stored, deleted);
      if (!stored.isEmpty()) {
        liveSync.stockChanged(false);
      }
    }

    /**
     * Decides and writes a create.
     *
     * @param change the planned create
     * @return the outcome
     */
    private @NotNull Outcome create(@NotNull Change change) {
      Op op = change.op();
      String key = Objects.requireNonNull(op.clientKey());
      Slot known = byKey.get(key);
      if (known == null) {
        known = linked(key);
      }
      if (known != null && !known.deleted) {
        known.refused = true;
        return new Outcome(
            ExchangeOps.REJECTED,
            ExchangeRefineryReasons.ALREADY_EXISTS,
            known.id == null ? null : new Written(known.id.toString(), null, null));
      }
      Refs refs = change.refs();
      Outcome refusal =
          refs.refusal() == null && !refs.locationOffered()
              ? Outcome.rejected(ExchangeRefineryReasons.NOT_A_REFINERY)
              : refs.refusal();
      if (refusal != null) {
        byKey.put(key, Slot.refusedCreate());
        return refusal;
      }
      List<RefineryExchangeEdit.Good> goods = new ArrayList<>();
      if (op.goods() != null) {
        for (int i = 0; i < op.goods().size(); i++) {
          ExchangeRefineryOrderChangeSet.Good good = op.goods().get(i);
          goods.add(
              new RefineryExchangeEdit.Good(
                  null,
                  refs.inputs().get(i),
                  good.inputQuantity(),
                  good.outputQuantity(),
                  good.quality()));
        }
      }
      RefineryExchangeEdit edit =
          new RefineryExchangeEdit(
              refs.location(),
              refs.method() == null ? null : new RefineryExchangeEdit.Method(refs.method()),
              op.endsAt() == null ? null : timer(op.endsAt(), op.durationMinutes(), now),
              op.status() == null
                  ? RefineryOrderStatus.OPEN
                  : RefineryOrderStatus.valueOf(op.status()),
              finances(op, null),
              List.copyOf(goods));
      if (dryRun) {
        byKey.put(
            key, Slot.simulated(ExchangeRefineryOrderView.created(edit, refs.outputs(), now)));
        return Outcome.APPLIED;
      }
      linkRepository.retireKey(caller.member(), caller.clientId(), key, now);
      RefineryExchangeOrder order =
          writer.create(
              caller.member(), caller.clientId(), Objects.requireNonNull(refs.orgUnit()), edit);
      linkRepository.save(
          ExchangeRefineryOrderLink.builder()
              .id(UUID.randomUUID())
              .userId(caller.member())
              .clientId(caller.clientId())
              .clientKey(key)
              .refineryOrderId(order.id())
              .createdAt(now)
              .build());
      journalService.record(
          caller,
          batch,
          ExchangeJournalAction.REFINERY_CREATE,
          order.id().toString(),
          false,
          null,
          ExchangeRefineryOrderState.of(order).toJournal());
      Slot slot = Slot.loaded(order);
      byKey.put(key, slot);
      byId.put(order.id(), slot);
      written.add(order.id());
      return applied(order, order.goods().stream().map(RefineryExchangeOrder.Good::id).toList());
    }

    /**
     * Decides and writes a partial update.
     *
     * @param slot the order
     * @param change the planned update
     * @return the outcome
     */
    private @NotNull Outcome update(@NotNull Slot slot, @NotNull Change change) {
      Op op = change.op();
      Refs refs = change.refs();
      ExchangeRefineryOrderView current = slot.view();
      RefineryExchangeEdit.Finances finances = finances(op, current);
      RefineryExchangeEdit.Timer timer =
          op.endsAt() == null ? null : timer(op.endsAt(), op.durationMinutes(), now);
      RefineryOrderStatus status =
          op.status() == null ? null : RefineryOrderStatus.valueOf(op.status());
      boolean gameData =
          refs.location() != null || refs.method() != null || timer != null || status != null;
      if (!gameData && finances == null) {
        return Outcome.UNCHANGED;
      }
      ExchangeRefineryOrderChangeSet.Versions versions = Objects.requireNonNull(op.versions());
      if ((gameData && !slot.gameDataIs(versions.gameData()))
          || (finances != null && !slot.financesAre(versions.finances()))) {
        return Outcome.rejected(ExchangeRefineryReasons.VERSION_CONFLICT);
      }
      UUID location = refs.location();
      if (location != null && !refs.locationOffered() && !location.equals(current.locationId())) {
        return Outcome.rejected(ExchangeRefineryReasons.NOT_A_REFINERY);
      }
      if (current.stored() && status != null && status != current.status()) {
        return Outcome.rejected(ExchangeRefineryReasons.ORDER_STORED);
      }
      RefineryExchangeEdit edit =
          new RefineryExchangeEdit(
              location,
              refs.method() == null ? null : new RefineryExchangeEdit.Method(refs.method()),
              timer,
              status,
              finances,
              null);
      if (current.unchangedBy(edit)) {
        return Outcome.UNCHANGED;
      }
      return write(slot, edit, refs, ExchangeJournalAction.REFINERY_UPDATE);
    }

    /**
     * Decides and writes a good-add.
     *
     * @param slot the order
     * @param change the planned good-add
     * @return the outcome
     */
    private @NotNull Outcome goodAdd(@NotNull Slot slot, @NotNull Change change) {
      Op op = change.op();
      if (!slot.gameDataIs(Objects.requireNonNull(op.versions()).gameData())) {
        return Outcome.rejected(ExchangeRefineryReasons.VERSION_CONFLICT);
      }
      ExchangeRefineryOrderChangeSet.Good good = Objects.requireNonNull(op.good());
      List<RefineryExchangeEdit.Good> goods = new ArrayList<>(slot.view().editGoods());
      goods.add(
          new RefineryExchangeEdit.Good(
              null,
              change.refs().inputs().getFirst(),
              good.inputQuantity(),
              good.outputQuantity(),
              good.quality()));
      return write(slot, goodsEdit(goods), change.refs(), ExchangeJournalAction.REFINERY_GOOD_ADD);
    }

    /**
     * Decides and writes a good-update.
     *
     * @param slot the order
     * @param change the planned good-update
     * @return the outcome
     */
    private @NotNull Outcome goodUpdate(@NotNull Slot slot, @NotNull Change change) {
      Op op = change.op();
      if (!slot.gameDataIs(Objects.requireNonNull(op.versions()).gameData())) {
        return Outcome.rejected(ExchangeRefineryReasons.VERSION_CONFLICT);
      }
      UUID goodId = ExchangeIds.parse(op.goodId());
      if (goodId == null) {
        return Outcome.rejected(ExchangeRefineryReasons.GOOD_UNKNOWN);
      }
      List<RefineryExchangeEdit.Good> goods = new ArrayList<>();
      boolean found = false;
      boolean changes = false;
      for (RefineryExchangeEdit.Good good : slot.view().editGoods()) {
        if (!goodId.equals(good.id())) {
          goods.add(good);
          continue;
        }
        found = true;
        RefineryExchangeEdit.Good next =
            new RefineryExchangeEdit.Good(
                goodId,
                change.refs().inputs().isEmpty()
                    ? good.inputMaterialId()
                    : change.refs().inputs().getFirst(),
                op.inputQuantity() == null ? good.inputQuantity() : op.inputQuantity(),
                op.outputQuantity() == null ? good.outputQuantity() : op.outputQuantity(),
                op.quality() == null ? good.quality() : op.quality());
        changes = !next.equals(good);
        goods.add(next);
      }
      if (!found) {
        return Outcome.rejected(ExchangeRefineryReasons.GOOD_UNKNOWN);
      }
      if (!changes) {
        return Outcome.UNCHANGED;
      }
      return write(
          slot, goodsEdit(goods), change.refs(), ExchangeJournalAction.REFINERY_GOOD_UPDATE);
    }

    /**
     * Decides and writes a good-remove.
     *
     * @param slot the order
     * @param change the planned good-remove
     * @return the outcome
     */
    private @NotNull Outcome goodRemove(@NotNull Slot slot, @NotNull Change change) {
      Op op = change.op();
      if (!slot.gameDataIs(Objects.requireNonNull(op.versions()).gameData())) {
        return Outcome.rejected(ExchangeRefineryReasons.VERSION_CONFLICT);
      }
      UUID goodId = ExchangeIds.parse(op.goodId());
      List<RefineryExchangeEdit.Good> held = slot.view().editGoods();
      List<RefineryExchangeEdit.Good> goods =
          held.stream().filter(good -> !Objects.equals(goodId, good.id())).toList();
      if (goodId == null || goods.size() == held.size()) {
        return Outcome.rejected(ExchangeRefineryReasons.GOOD_UNKNOWN);
      }
      return write(
          slot, goodsEdit(goods), change.refs(), ExchangeJournalAction.REFINERY_GOOD_REMOVE);
    }

    /**
     * Decides and writes a cancel through the refinery's own cancel (REQ-XCH-055).
     *
     * @param slot the order
     * @param op the cancel
     * @return the outcome; {@code unchanged} for an order already cancelled
     */
    private @NotNull Outcome cancel(@NotNull Slot slot, @NotNull Op op) {
      if (!slot.gameDataIs(Objects.requireNonNull(op.versions()).gameData())) {
        return Outcome.rejected(ExchangeRefineryReasons.VERSION_CONFLICT);
      }
      if (slot.view().status() == RefineryOrderStatus.CANCELED) {
        return Outcome.UNCHANGED;
      }
      if (dryRun) {
        slot.simulate(slot.view().canceled());
        return Outcome.APPLIED;
      }
      UUID id = slot.id();
      RefineryExchangeOrder before = slot.order();
      RefineryExchangeOrder after = writer.cancel(caller.member(), caller.clientId(), id);
      journal(id, ExchangeJournalAction.REFINERY_CANCEL, before, after);
      slot.record(after);
      written.add(id);
      return applied(after, List.of());
    }

    /**
     * Decides and writes a pickup (REQ-XCH-053): an open or running order is closed whatever its
     * end, a completed one is left, a cancelled one is refused; store lines book the yield, all or
     * nothing, and each must name an output material of the order's goods.
     *
     * @param slot the order
     * @param change the planned pickup with its resolved store lines
     * @return the outcome
     */
    private @NotNull Outcome pickup(@NotNull Slot slot, @NotNull Change change) {
      Op op = change.op();
      ExchangeRefineryOrderView current = slot.view();
      if (current.status() == RefineryOrderStatus.COMPLETED) {
        return Outcome.UNCHANGED;
      }
      if (current.status() == RefineryOrderStatus.CANCELED) {
        return Outcome.rejected(ExchangeRefineryReasons.ORDER_CANCELED);
      }
      if (!slot.gameDataIs(Objects.requireNonNull(op.versions()).gameData())) {
        return Outcome.rejected(ExchangeRefineryReasons.VERSION_CONFLICT);
      }
      List<RefineryExchangeStoreLine> lines = change.refs().lines();
      Outcome refusal = ExchangeRefineryStoreLines.refusalFor(current, lines);
      if (refusal != null) {
        return refusal;
      }
      if (dryRun) {
        slot.simulate(current.pickedUp(!lines.isEmpty()));
        return Outcome.APPLIED;
      }
      UUID id = slot.id();
      RefineryExchangeOrder before = slot.order();
      RefineryExchangeOrder after = writer.pickUp(caller.member(), caller.clientId(), id, lines);
      journal(id, ExchangeJournalAction.REFINERY_PICKUP, before, after);
      slot.record(after);
      written.add(id);
      if (!lines.isEmpty()) {
        stored.add(id);
      }
      return applied(after, List.of());
    }

    /**
     * Decides and writes a delete of an order that is not stored, journaled as a removal for the
     * mass-change guard (REQ-XCH-055).
     *
     * @param slot the order
     * @param op the delete
     * @return the outcome
     */
    private @NotNull Outcome delete(@NotNull Slot slot, @NotNull Op op) {
      ExchangeRefineryOrderChangeSet.Versions versions = Objects.requireNonNull(op.versions());
      if (!slot.gameDataIs(versions.gameData()) || !slot.financesAre(versions.finances())) {
        return Outcome.rejected(ExchangeRefineryReasons.VERSION_CONFLICT);
      }
      if (slot.view().stored()) {
        return Outcome.rejected(ExchangeRefineryReasons.ORDER_STORED);
      }
      slot.deleted = true;
      if (dryRun) {
        return Outcome.APPLIED;
      }
      UUID id = slot.id();
      ExchangeRefineryOrderState removed =
          ExchangeRefineryOrderState.of(slot.order()).deleted(writer.delete(caller.member(), id));
      journalService.record(
          caller,
          batch,
          ExchangeJournalAction.REFINERY_DELETE,
          id.toString(),
          true,
          removed.toJournal(),
          null);
      written.remove(id);
      deleted.add(id);
      return Outcome.APPLIED;
    }

    /**
     * Journals one write of an order for the undo.
     *
     * @param id the order
     * @param action the journal action
     * @param before the order before the write
     * @param after the order after it
     */
    private void journal(
        @NotNull UUID id,
        @NotNull ExchangeJournalAction action,
        @NotNull RefineryExchangeOrder before,
        @NotNull RefineryExchangeOrder after) {
      journalService.record(
          caller,
          batch,
          action,
          id.toString(),
          false,
          ExchangeRefineryOrderState.of(before).toJournal(),
          ExchangeRefineryOrderState.of(after).toJournal());
    }

    /**
     * Writes an edit through the refinery module, or in a dry run derives the order it would leave.
     *
     * @param slot the order
     * @param edit the edit
     * @param refs the op's references, whose inputs name their outputs
     * @param action the journal action
     * @return the applied outcome
     */
    private @NotNull Outcome write(
        @NotNull Slot slot,
        @NotNull RefineryExchangeEdit edit,
        @NotNull Refs refs,
        @NotNull ExchangeJournalAction action) {
      if (dryRun) {
        slot.simulate(slot.view().edited(edit, refs.outputs()));
        return Outcome.APPLIED;
      }
      UUID id = slot.id();
      RefineryExchangeOrder before = slot.order();
      RefineryExchangeOrder after = writer.edit(caller.member(), caller.clientId(), id, edit);
      journal(id, action, before, after);
      slot.record(after);
      written.add(id);
      Set<UUID> held = new LinkedHashSet<>();
      before.goods().forEach(good -> held.add(good.id()));
      return applied(
          after,
          after.goods().stream()
              .map(RefineryExchangeOrder.Good::id)
              .filter(goodId -> !held.contains(goodId))
              .toList());
    }

    /**
     * Finds the order an op names, locking it on first sight.
     *
     * @param op the op
     * @return the order, or {@code null} when the member owns no order of that id or the client
     *     gave none that key
     */
    private @Nullable Slot address(@NotNull Op op) {
      if (op.id() != null) {
        UUID id = ExchangeIds.parse(op.id());
        return id == null ? null : locked(id);
      }
      String key = Objects.requireNonNull(op.clientKey());
      Slot slot = byKey.get(key);
      return slot != null ? slot : linked(key);
    }

    /**
     * Looks a client key up in the links that still name an order and locks the order.
     *
     * @param key the client's key
     * @return the order, or {@code null} when the key names none the member owns now
     */
    private @Nullable Slot linked(@NotNull String key) {
      Optional<ExchangeRefineryOrderLink> link =
          linkRepository.findByUserIdAndClientIdAndClientKeyAndRetiredAtIsNull(
              caller.member(), caller.clientId(), key);
      Slot slot = link.isEmpty() ? null : locked(link.get().getRefineryOrderId());
      if (slot != null) {
        byKey.put(key, slot);
      }
      return slot;
    }

    /**
     * Finds an order by id, locking it on first sight.
     *
     * @param id the order
     * @return the order, or {@code null} when the member owns none of that id
     */
    private @Nullable Slot locked(@NotNull UUID id) {
      Slot slot = byId.get(id);
      if (slot != null) {
        return slot;
      }
      Optional<RefineryExchangeOrder> order = writer.lockOwn(caller.member(), id);
      if (order.isEmpty()) {
        return null;
      }
      Slot loaded = Slot.loaded(order.get());
      byId.put(id, loaded);
      return loaded;
    }

    /**
     * Builds the applied outcome of a written order.
     *
     * @param order the order after the op
     * @param created the goods rows the op created
     * @return the outcome naming the order, its new rows and its counters
     */
    private @NotNull Outcome applied(
        @NotNull RefineryExchangeOrder order, @NotNull List<UUID> created) {
      return new Outcome(
          ExchangeOps.APPLIED,
          null,
          new Written(
              order.id().toString(),
              created.isEmpty() ? null : created.stream().map(UUID::toString).toList(),
              new ExchangeRefineryOrderDto.Versions(
                  order.gameDataVersion(), order.financesVersion())));
    }
  }

  /**
   * The money fields an op sets, the absent ones kept from the order.
   *
   * @param op the op
   * @param current the order, or {@code null} for a create
   * @return the finances, or {@code null} when the op names none
   */
  private static @Nullable RefineryExchangeEdit.Finances finances(
      @NotNull Op op, @Nullable ExchangeRefineryOrderView current) {
    if (op.expenses() == null && op.otherExpenses() == null && op.oreSales() == null) {
      return null;
    }
    return new RefineryExchangeEdit.Finances(
        op.expenses() != null ? op.expenses() : current == null ? null : current.expenses(),
        op.otherExpenses() != null
            ? op.otherExpenses()
            : current == null ? null : current.otherExpenses(),
        op.oreSales() != null ? op.oreSales() : current == null ? null : current.oreSales());
  }

  /**
   * An edit that sets only the goods.
   *
   * @param goods every good the order holds afterwards
   * @return the edit
   */
  private static @NotNull RefineryExchangeEdit goodsEdit(
      @NotNull List<RefineryExchangeEdit.Good> goods) {
    return new RefineryExchangeEdit(null, null, null, null, null, List.copyOf(goods));
  }

  /**
   * One order as the earlier ops of the set left it. A slot a real run decides on always holds the
   * order the refinery module last returned; a dry run's holds only its view.
   */
  @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
  private static final class Slot {

    /** The order's id, or {@code null} for one a dry run creates or a refused create. */
    private final @Nullable UUID id;

    /** The order as the refinery module last returned it, or {@code null} in a dry run's create. */
    private @Nullable RefineryExchangeOrder order;

    /** What the next op is decided against, or {@code null} for a refused create. */
    private @Nullable ExchangeRefineryOrderView view;

    /** Whether an op on the order was refused. */
    private boolean refused;

    /** Whether an op of the set deleted the order, or would in a dry run. */
    private boolean deleted;

    /**
     * A slot of an order read or written.
     *
     * @param order the order
     * @return the slot
     */
    static @NotNull Slot loaded(@NotNull RefineryExchangeOrder order) {
      Slot slot = new Slot(order.id());
      slot.record(order);
      return slot;
    }

    /**
     * A slot of an order a dry run creates.
     *
     * @param view the created order
     * @return the slot
     */
    static @NotNull Slot simulated(@NotNull ExchangeRefineryOrderView view) {
      Slot slot = new Slot(null);
      slot.view = view;
      return slot;
    }

    /**
     * A slot of a refused create, on which every later op is refused.
     *
     * @return the slot
     */
    static @NotNull Slot refusedCreate() {
      Slot slot = new Slot(null);
      slot.refused = true;
      return slot;
    }

    /**
     * Takes the order the refinery module returned after a write.
     *
     * @param written the order
     */
    void record(@NotNull RefineryExchangeOrder written) {
      order = written;
      view = ExchangeRefineryOrderView.of(written);
    }

    /**
     * Takes the order a dry-run op would leave.
     *
     * @param next the order
     */
    void simulate(@NotNull ExchangeRefineryOrderView next) {
      view = next;
    }

    /**
     * What the next op is decided against.
     *
     * @return the view; never {@code null} for a slot that is not refused
     */
    @NotNull
    ExchangeRefineryOrderView view() {
      return Objects.requireNonNull(view, "view");
    }

    /**
     * The id of an order a real run writes.
     *
     * @return the id
     */
    @NotNull
    UUID id() {
      return Objects.requireNonNull(id, "id");
    }

    /**
     * The order a real run writes, as last returned.
     *
     * @return the order
     */
    @NotNull
    RefineryExchangeOrder order() {
      return Objects.requireNonNull(order, "order");
    }

    /**
     * Whether the client's echo matches the game-data counter as the earlier ops left it.
     *
     * @param echoed the echo, or {@code null}
     * @return {@code true} when it matches
     */
    boolean gameDataIs(@Nullable Long echoed) {
      return echoed != null && echoed == view().gameDataVersion();
    }

    /**
     * Whether the client's echo matches the finances counter as the earlier ops left it.
     *
     * @param echoed the echo, or {@code null}
     * @return {@code true} when it matches
     */
    boolean financesAre(@Nullable Long echoed) {
      return echoed != null && echoed == view().financesVersion();
    }
  }

  /**
   * One op with its references resolved.
   *
   * @param op the op
   * @param refs its resolved references, or the refusal they lead to
   */
  record Change(@NotNull Op op, @NotNull Refs refs) {}
}
