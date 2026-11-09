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

import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeChangeSetAdapter.Execution;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeChangeSetAdapter.Outcome;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeChangeSetAdapter.Planned;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeChangeSetAdapter.Write;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeChangeSetAdapter.Written;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeRefineryOrderReferences.Refs;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeRefineryOrderWriteService.Change;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeRefineryOrderChangeSet;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeRefineryOrderChangeSet.Kind;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeRefineryOrderChangeSet.Op;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeRefineryOrderDto;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeEdit;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeOrder;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeStoreLine;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeWriter;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryOrderStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The decisions of one refinery-order change set (REQ-XCH-052, REQ-XCH-053, REQ-XCH-055), op by op
 * against the orders as the earlier ops left them. Every order the set names is locked once, before
 * the first op runs, in ascending id order, after the keys it creates under; a dry run decides
 * every op against the {@link ExchangeRefineryOrderState} the earlier ops would leave and writes
 * nothing.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
final class ExchangeRefineryOrderRun implements Execution<Change> {

  /** The refinery module's writer. */
  private final RefineryExchangeWriter writer;

  /** The client keys of the orders. */
  private final ExchangeRefineryOrderKeys keys;

  /** Journals each write for the undo. */
  private final ExchangeJournalService journalService;

  /** Refreshes the member's open pages after the commit. */
  private final ExchangeLiveSync liveSync;

  /** The caller. */
  private final ExchangeCaller caller;

  /** The change set's id in the journal. */
  private final UUID batch;

  /** Whether nothing is written. */
  private final boolean dryRun;

  /** When the set runs; a timer without a length starts here. */
  private final Instant now;

  /** The orders the set names, by id, locked. */
  private final Map<UUID, Slot> byId = new HashMap<>();

  /** The orders the set names, by the client's key. */
  private final Map<String, Slot> byKey = new HashMap<>();

  /** The keys a live link holds, whether or not its order still exists. */
  private final Set<String> taken = new HashSet<>();

  /** The orders the set wrote and kept. */
  private final Set<UUID> written = new LinkedHashSet<>();

  /** The orders among them whose yield a pickup of the set booked into stock. */
  private final Set<UUID> stored = new LinkedHashSet<>();

  /** The orders the set deleted. */
  private final Set<UUID> deleted = new LinkedHashSet<>();

  /**
   * Starts a run: takes the locks on the keys the set creates under, then locks every order of the
   * member the set names by id or key, in ascending id order.
   *
   * @param service the write service, whose collaborators the run uses
   * @param caller the caller
   * @param batch the change set's id in the journal
   * @param plan the plan
   * @param dryRun whether nothing is written
   * @param now when the set runs
   * @return the run
   */
  static @NotNull ExchangeRefineryOrderRun start(
      @NotNull Collaborators service,
      @NotNull ExchangeCaller caller,
      @NotNull UUID batch,
      @NotNull List<Planned<Change>> plan,
      boolean dryRun,
      @NotNull Instant now) {
    ExchangeRefineryOrderRun run =
        new ExchangeRefineryOrderRun(
            service.writer(),
            service.keys(),
            service.journalService(),
            service.liveSync(),
            caller,
            batch,
            dryRun,
            now);
    run.lockAll(plan);
    return run;
  }

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
  public @NotNull ExchangeChangeSetAdapter.Effects effects() {
    return ExchangeChangeSetAdapter.Effects.NONE;
  }

  @Override
  public void announce() {
    liveSync.refineryOrdersChanged(written, stored, deleted);
    if (!stored.isEmpty()) {
      liveSync.stockChanged(false);
    }
  }

  /**
   * Locks what the set names before any op runs: the keys it creates under, then the member's
   * orders it names by id or by a key, in ascending id order. A dry run locks alike, so it decides
   * on the same state.
   *
   * @param plan the plan
   */
  private void lockAll(@NotNull List<Planned<Change>> plan) {
    List<String> creates = new ArrayList<>();
    Set<String> named = new HashSet<>();
    Set<UUID> ids = new HashSet<>();
    for (Planned<Change> planned : plan) {
      if (!(planned instanceof Write<Change>(Change change))) {
        continue;
      }
      Op op = change.op();
      if (op.kind() == Kind.CREATE) {
        creates.add(Objects.requireNonNull(op.clientKey()));
      } else if (op.id() != null) {
        UUID id = ExchangeIds.parse(op.id());
        if (id != null) {
          ids.add(id);
        }
      } else {
        named.add(Objects.requireNonNull(op.clientKey()));
      }
    }
    keys.lockForCreates(caller.member(), caller.clientId(), creates);
    named.addAll(creates);
    Map<String, UUID> links = keys.named(caller.member(), caller.clientId(), named);
    ids.addAll(links.values());
    for (RefineryExchangeOrder order : writer.lockOwn(caller.member(), ids)) {
      byId.put(order.id(), Slot.loaded(order));
    }
    links.forEach((key, id) -> {
      taken.add(key);
      Slot slot = byId.get(id);
      if (slot != null) {
        byKey.put(key, slot);
      }
    });
  }

  /**
   * Decides and writes a create: a known key is {@code ALREADY_EXISTS}, naming the order outside a
   * dry run; a create without goods is {@code GOODS_REQUIRED}.
   *
   * @param change the planned create
   * @return the outcome
   */
  private @NotNull Outcome create(@NotNull Change change) {
    Op op = change.op();
    String key = Objects.requireNonNull(op.clientKey());
    Slot known = byKey.get(key);
    if (known != null && !known.deleted) {
      if (known.state == null) {
        return Outcome.rejected(ExchangeRefineryReasons.PRECEDING_OP_REJECTED);
      }
      known.refused = true;
      return new Outcome(
          ExchangeOps.REJECTED,
          ExchangeRefineryReasons.ALREADY_EXISTS,
          known.id == null ? null : new Written(known.id.toString(), null, null));
    }
    Refs refs = change.refs();
    Outcome refusal = refs.refusal();
    if (refusal == null && !refs.locationOffered()) {
      refusal = Outcome.rejected(ExchangeRefineryReasons.NOT_A_REFINERY);
    }
    if (refusal == null && (op.goods() == null || op.goods().isEmpty())) {
      refusal = Outcome.rejected(ExchangeRefineryReasons.GOODS_REQUIRED);
    }
    if (refusal != null) {
      byKey.put(key, Slot.refusedCreate());
      return refusal;
    }
    List<ExchangeRefineryOrderChangeSet.Good> goods = Objects.requireNonNull(op.goods());
    List<RefineryExchangeEdit.Good> rows = new ArrayList<>(goods.size());
    for (int i = 0; i < goods.size(); i++) {
      ExchangeRefineryOrderChangeSet.Good good = goods.get(i);
      rows.add(
          new RefineryExchangeEdit.Good(
              null,
              refs.inputs().get(i),
              good.inputQuantity(),
              good.outputQuantity(),
              good.quality()));
    }
    RefineryExchangeEdit edit =
        new RefineryExchangeEdit(
            refs.location(),
            refs.method() == null ? null : new RefineryExchangeEdit.Method(refs.method()),
            op.endsAt() == null
                ? null
                : ExchangeRefineryOrderWriteService.timer(op.endsAt(), op.durationMinutes(), now),
            op.status() == null
                ? RefineryOrderStatus.OPEN
                : RefineryOrderStatus.valueOf(op.status()),
            finances(op, null),
            List.copyOf(rows));
    if (dryRun) {
      byKey.put(
          key,
          Slot.simulated(
              ExchangeRefineryOrderState.created(
                  edit, Objects.requireNonNull(refs.locationName()), refs.outputs(), now)));
      return Outcome.APPLIED;
    }
    RefineryExchangeOrder order =
        writer.create(
            caller.member(), caller.clientId(), Objects.requireNonNull(refs.orgUnit()), edit);
    keys.link(caller.member(), caller.clientId(), key, order.id(), now, taken.contains(key));
    taken.add(key);
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
    ExchangeRefineryOrderState current = slot.state();
    RefineryExchangeEdit.Finances finances = finances(op, current);
    RefineryExchangeEdit.Timer timer =
        op.endsAt() == null
            ? null
            : ExchangeRefineryOrderWriteService.timer(op.endsAt(), op.durationMinutes(), now);
    RefineryOrderStatus status =
        op.status() == null ? null : RefineryOrderStatus.valueOf(op.status());
    boolean gameData =
        refs.location() != null || refs.method() != null || timer != null || status != null;
    if (!gameData && finances == null) {
      return Outcome.UNCHANGED;
    }
    Outcome conflict = slot.conflict(op, gameData, finances != null);
    if (conflict != null) {
      return conflict;
    }
    UUID location = refs.location();
    if (location != null && !refs.locationOffered() && !location.equals(current.location())) {
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
    return edit(slot, edit, refs, ExchangeJournalAction.REFINERY_UPDATE);
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
    Outcome conflict = slot.conflict(op, true, false);
    if (conflict != null) {
      return conflict;
    }
    ExchangeRefineryOrderChangeSet.Good good = Objects.requireNonNull(op.good());
    List<RefineryExchangeEdit.Good> goods = new ArrayList<>(slot.state().editGoods());
    goods.add(
        new RefineryExchangeEdit.Good(
            null,
            change.refs().inputs().getFirst(),
            good.inputQuantity(),
            good.outputQuantity(),
            good.quality()));
    return edit(slot, goodsEdit(goods), change.refs(), ExchangeJournalAction.REFINERY_GOOD_ADD);
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
    Outcome conflict = slot.conflict(op, true, false);
    if (conflict != null) {
      return conflict;
    }
    UUID goodId = ExchangeIds.parse(op.goodId());
    List<RefineryExchangeEdit.Good> goods = new ArrayList<>();
    boolean found = false;
    boolean changes = false;
    for (RefineryExchangeEdit.Good good : slot.state().editGoods()) {
      if (goodId == null || !goodId.equals(good.id())) {
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
              Objects.requireNonNullElse(op.inputQuantity(), good.inputQuantity()),
              Objects.requireNonNullElse(op.outputQuantity(), good.outputQuantity()),
              Objects.requireNonNullElse(op.quality(), good.quality()));
      changes = !next.equals(good);
      goods.add(next);
    }
    if (!found) {
      return Outcome.rejected(ExchangeRefineryReasons.GOOD_UNKNOWN);
    }
    if (!changes) {
      return Outcome.UNCHANGED;
    }
    return edit(slot, goodsEdit(goods), change.refs(), ExchangeJournalAction.REFINERY_GOOD_UPDATE);
  }

  /**
   * Decides and writes a good-remove; the order's last good stays, {@code GOODS_REQUIRED}.
   *
   * @param slot the order
   * @param change the planned good-remove
   * @return the outcome
   */
  private @NotNull Outcome goodRemove(@NotNull Slot slot, @NotNull Change change) {
    Op op = change.op();
    Outcome conflict = slot.conflict(op, true, false);
    if (conflict != null) {
      return conflict;
    }
    UUID goodId = ExchangeIds.parse(op.goodId());
    List<RefineryExchangeEdit.Good> held = slot.state().editGoods();
    List<RefineryExchangeEdit.Good> goods =
        held.stream().filter(good -> !Objects.equals(goodId, good.id())).toList();
    if (goodId == null || goods.size() == held.size()) {
      return Outcome.rejected(ExchangeRefineryReasons.GOOD_UNKNOWN);
    }
    if (goods.isEmpty()) {
      return Outcome.rejected(ExchangeRefineryReasons.GOODS_REQUIRED);
    }
    return edit(slot, goodsEdit(goods), change.refs(), ExchangeJournalAction.REFINERY_GOOD_REMOVE);
  }

  /**
   * Decides and writes a cancel through the refinery's own cancel (REQ-XCH-055). The status is
   * decided before the counter, so a resent cancel is {@code unchanged}.
   *
   * @param slot the order
   * @param op the cancel
   * @return the outcome; {@code unchanged} for an order already cancelled
   */
  private @NotNull Outcome cancel(@NotNull Slot slot, @NotNull Op op) {
    if (slot.state().status() == RefineryOrderStatus.CANCELED) {
      return Outcome.UNCHANGED;
    }
    Outcome conflict = slot.conflict(op, true, false);
    if (conflict != null) {
      return conflict;
    }
    return commit(
        slot,
        ExchangeJournalAction.REFINERY_CANCEL,
        ExchangeRefineryOrderState::canceled,
        id -> writer.cancel(caller.member(), caller.clientId(), id),
        false);
  }

  /**
   * Decides and writes a pickup (REQ-XCH-053): an open or running order is closed whatever its end,
   * a completed one without store lines is left, a cancelled one is refused, and store lines on a
   * completed or stored order are {@code ORDER_STORED}; store lines book the yield, all or nothing,
   * each of an output material of the order's goods. The status is decided before the counter, so a
   * resent pickup answers as the first one left the order.
   *
   * @param slot the order
   * @param change the planned pickup with its resolved store lines
   * @return the outcome
   */
  private @NotNull Outcome pickup(@NotNull Slot slot, @NotNull Change change) {
    ExchangeRefineryOrderState current = slot.state();
    List<RefineryExchangeStoreLine> lines = change.refs().lines();
    if (current.status() == RefineryOrderStatus.COMPLETED && lines.isEmpty()) {
      return Outcome.UNCHANGED;
    }
    if (current.status() == RefineryOrderStatus.CANCELED) {
      return Outcome.rejected(ExchangeRefineryReasons.ORDER_CANCELED);
    }
    Outcome refusal = ExchangeRefineryStoreLines.refusalFor(current, lines);
    if (refusal == null) {
      refusal = slot.conflict(change.op(), true, false);
    }
    if (refusal != null) {
      return refusal;
    }
    return commit(
        slot,
        ExchangeJournalAction.REFINERY_PICKUP,
        state -> state.pickedUp(lines),
        id -> writer.pickUp(caller.member(), caller.clientId(), id, lines),
        !lines.isEmpty());
  }

  /**
   * Decides and writes a delete of an order that is not stored, journaled as a removal
   * (REQ-XCH-055).
   *
   * @param slot the order
   * @param op the delete
   * @return the outcome
   */
  private @NotNull Outcome delete(@NotNull Slot slot, @NotNull Op op) {
    Outcome conflict = slot.conflict(op, true, true);
    if (conflict != null) {
      return conflict;
    }
    if (slot.state().stored()) {
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
   * Writes an edit through the refinery module, or in a dry run derives the order it would leave.
   *
   * @param slot the order
   * @param edit the edit
   * @param refs the op's references, whose location names its place and whose inputs their outputs
   * @param action the journal action
   * @return the applied outcome
   */
  private @NotNull Outcome edit(
      @NotNull Slot slot,
      @NotNull RefineryExchangeEdit edit,
      @NotNull Refs refs,
      @NotNull ExchangeJournalAction action) {
    return commit(
        slot,
        action,
        state -> state.edited(edit, refs.locationName(), refs.outputs()),
        id -> writer.edit(caller.member(), caller.clientId(), id, edit),
        false);
  }

  /**
   * Applies a decided write to an existing order: in a dry run the state it would leave, else the
   * write itself, journaled, with the goods rows it created.
   *
   * @param slot the order
   * @param action the journal action
   * @param simulated the state the write leaves, as a dry run derives it
   * @param write the write through the refinery module
   * @param booksStock whether the write books the order's yield into stock
   * @return the applied outcome
   */
  private @NotNull Outcome commit(
      @NotNull Slot slot,
      @NotNull ExchangeJournalAction action,
      @NotNull UnaryOperator<ExchangeRefineryOrderState> simulated,
      @NotNull Function<UUID, RefineryExchangeOrder> write,
      boolean booksStock) {
    if (dryRun) {
      slot.state = simulated.apply(slot.state());
      return Outcome.APPLIED;
    }
    UUID id = slot.id();
    RefineryExchangeOrder before = slot.order();
    RefineryExchangeOrder after = write.apply(id);
    journalService.record(
        caller,
        batch,
        action,
        id.toString(),
        false,
        ExchangeRefineryOrderState.of(before).toJournal(),
        ExchangeRefineryOrderState.of(after).toJournal());
    slot.record(after);
    written.add(id);
    if (booksStock) {
      stored.add(id);
    }
    Set<UUID> held = new HashSet<>();
    before.goods().forEach(good -> held.add(good.id()));
    return applied(
        after,
        after.goods().stream()
            .map(RefineryExchangeOrder.Good::id)
            .filter(goodId -> !held.contains(goodId))
            .toList());
  }

  /**
   * Finds the order an op names among the locked ones and the ones the set created.
   *
   * @param op the op
   * @return the order, or {@code null} when the member owns no order of that id or the client gave
   *     none that key
   */
  private @Nullable Slot address(@NotNull Op op) {
    if (op.id() != null) {
      UUID id = ExchangeIds.parse(op.id());
      return id == null ? null : byId.get(id);
    }
    return byKey.get(Objects.requireNonNull(op.clientKey()));
  }

  /**
   * Builds the applied outcome of a written order.
   *
   * @param order the order after the op
   * @param created the goods rows the op created, in the op's order
   * @return the outcome naming the order, its new rows and its counters
   */
  private static @NotNull Outcome applied(
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

  /**
   * The money fields an op sets, the absent ones kept from the order.
   *
   * @param op the op
   * @param current the order, or {@code null} for a create
   * @return the finances, or {@code null} when the op names none
   */
  private static @Nullable RefineryExchangeEdit.Finances finances(
      @NotNull Op op, @Nullable ExchangeRefineryOrderState current) {
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
   * The write service's collaborators a run uses.
   *
   * @param writer the refinery module's writer
   * @param keys the client keys of the orders
   * @param journalService the journal
   * @param liveSync the live refresh of the member's pages
   */
  record Collaborators(
      @NotNull RefineryExchangeWriter writer,
      @NotNull ExchangeRefineryOrderKeys keys,
      @NotNull ExchangeJournalService journalService,
      @NotNull ExchangeLiveSync liveSync) {}

  /**
   * One order as the earlier ops of the set left it. A slot a real run decides on holds the order
   * the refinery module last returned; a dry run's holds only its state.
   */
  @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
  private static final class Slot {

    /** The order's id, or {@code null} for one a dry run creates or a refused create. */
    private final @Nullable UUID id;

    /** The order as the refinery module last returned it, or {@code null} in a dry run's create. */
    private @Nullable RefineryExchangeOrder order;

    /** What the next op is decided against, or {@code null} for a refused create. */
    private @Nullable ExchangeRefineryOrderState state;

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
     * @param state the created order
     * @return the slot
     */
    static @NotNull Slot simulated(@NotNull ExchangeRefineryOrderState state) {
      Slot slot = new Slot(null);
      slot.state = state;
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
      state = ExchangeRefineryOrderState.of(written);
    }

    /**
     * What the next op is decided against.
     *
     * @return the state; never {@code null} for a slot that is not refused
     */
    @NotNull
    ExchangeRefineryOrderState state() {
      return Objects.requireNonNull(state, "state");
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
     * Checks the counters an op echoes for the sections it touches against the ones the earlier ops
     * left.
     *
     * @param op the op
     * @param gameData whether the op touches the game data
     * @param finances whether the op touches the finances
     * @return {@code VERSION_CONFLICT} when an echo is missing or differs, else {@code null}
     */
    @Nullable
    Outcome conflict(@NotNull Op op, boolean gameData, boolean finances) {
      ExchangeRefineryOrderChangeSet.Versions versions = op.versions();
      Long echoedGameData = versions == null ? null : versions.gameData();
      Long echoedFinances = versions == null ? null : versions.finances();
      boolean stale =
          (gameData && !Objects.equals(echoedGameData, state().gameData()))
              || (finances && !Objects.equals(echoedFinances, state().finances()));
      return stale ? Outcome.rejected(ExchangeReasons.VERSION_CONFLICT) : null;
    }
  }
}
