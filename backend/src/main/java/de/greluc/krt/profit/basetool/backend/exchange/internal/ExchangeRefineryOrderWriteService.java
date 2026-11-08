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

import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeRefineryOrderJournal.State;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeRefineryOrderReferences.Refs;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeChangeResultDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeRefineryOrderChangeSet;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeRefineryOrderChangeSet.Op;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeRefineryOrderDto;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeEdit;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeOrder;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeReader;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeWriter;
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
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies a client's writes to the member's own refinery orders (REQ-XCH-052, REQ-XCH-053,
 * REQ-XCH-055, REQ-XCH-056, ADR-0351): creates under the client's key, partial updates, goods ops,
 * cancels, deletes and pickups without store lines, each through the refinery module's own writer,
 * decided in order against the section counters the earlier ops left, and journaled for the undo.
 * The {@link ExchangeChangeSetRunner} runs the change set (ADR-0285); a delete counts for the
 * mass-change guard.
 *
 * <p>References resolve exactly in the plan; everything that depends on the order — its address, an
 * earlier refusal, the counters, the goods — is decided when the op runs, so a refusal of one op
 * refuses every later op on the same order ({@code PRECEDING_OP_REJECTED}).
 */
@Service
@RequiredArgsConstructor
public class ExchangeRefineryOrderWriteService
    implements ExchangeChangeSetAdapter<
        ExchangeRefineryOrderChangeSet, ExchangeRefineryOrderWriteService.Change> {

  /** The refusal of an order the member does not own or the client's key does not name. */
  static final String ORDER_UNKNOWN = "ORDER_UNKNOWN";

  /** The refusal of a good the order does not hold. */
  static final String GOOD_UNKNOWN = "GOOD_UNKNOWN";

  /** The refusal of a create whose key already names an order. */
  static final String ALREADY_EXISTS = "ALREADY_EXISTS";

  /** The refusal of a section counter the order no longer has. */
  static final String VERSION_CONFLICT = "VERSION_CONFLICT";

  /** The refusal of an op on an order an earlier op of the set was refused on. */
  static final String PRECEDING_OP_REJECTED = "PRECEDING_OP_REJECTED";

  /** The refusal of a status change on a stored order. */
  static final String ORDER_STORED = "ORDER_STORED";

  /** The refusal of a pickup of a cancelled order. */
  static final String ORDER_CANCELED = "ORDER_CANCELED";

  /** The status a create starts in when it names none. */
  private static final String OPEN = "OPEN";

  /** The status of a picked-up order. */
  private static final String COMPLETED = "COMPLETED";

  /** The status of a cancelled order. */
  private static final String CANCELED = "CANCELED";

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
            .filter(p ->
                p instanceof Write<Change>(Change change)
                    && ExchangeRefineryOrderChangeSet.DELETE.equals(change.op().op()))
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
   * Refuses an op that lacks a field its kind needs; the gateway's schema keeps such an op from
   * arriving.
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
        switch (op.op()) {
          case ExchangeRefineryOrderChangeSet.CREATE ->
              op.clientKey() != null && op.orgUnit() != null && op.location() != null;
          case ExchangeRefineryOrderChangeSet.UPDATE -> addressed && op.versions() != null;
          case ExchangeRefineryOrderChangeSet.GOOD_ADD ->
              addressed && gameDataEcho && op.good() != null;
          case ExchangeRefineryOrderChangeSet.GOOD_UPDATE,
              ExchangeRefineryOrderChangeSet.GOOD_REMOVE ->
              addressed && gameDataEcho && op.goodId() != null;
          case ExchangeRefineryOrderChangeSet.CANCEL -> addressed && gameDataEcho;
          case ExchangeRefineryOrderChangeSet.DELETE -> addressed && gameDataEcho && financesEcho;
          case ExchangeRefineryOrderChangeSet.PICKUP -> addressed && gameDataEcho;
          default -> false;
        };
    if (!complete || (op.durationMinutes() != null && op.endsAt() == null)) {
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

  /**
   * The goods an order holds, as the rows an edit writes back.
   *
   * @param order the order
   * @return its goods with their row ids
   */
  private static @NotNull List<RefineryExchangeEdit.Good> goodsOf(
      @NotNull RefineryExchangeOrder order) {
    return order.goods().stream()
        .map(good ->
            new RefineryExchangeEdit.Good(
                good.id(),
                good.inputMaterialId(),
                good.inputQuantity(),
                good.outputQuantity(),
                good.quality()))
        .toList();
  }

  /** The decisions of one change set, op by op, against the orders as the earlier ops left them. */
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

    /** The orders the set deleted. */
    private final Set<UUID> deleted = new LinkedHashSet<>();

    /**
     * Starts a run.
     *
     * @param caller the caller
     * @param batch the change set's id
     * @param dryRun whether nothing is written
     */
    private Run(@NotNull ExchangeCaller caller, @NotNull UUID batch, boolean dryRun) {
      this.caller = caller;
      this.batch = batch;
      this.dryRun = dryRun;
    }

    @Override
    public @NotNull Outcome run(@NotNull Change change) {
      Op op = change.op();
      if (ExchangeRefineryOrderChangeSet.CREATE.equals(op.op())) {
        return create(change);
      }
      Slot slot = address(op);
      if (slot == null || slot.deleted) {
        return Outcome.rejected(ORDER_UNKNOWN);
      }
      if (slot.refused) {
        return Outcome.rejected(PRECEDING_OP_REJECTED);
      }
      Outcome outcome = change.refs().refusal();
      if (outcome == null) {
        outcome =
            switch (op.op()) {
              case ExchangeRefineryOrderChangeSet.UPDATE -> update(slot, change);
              case ExchangeRefineryOrderChangeSet.GOOD_ADD -> goodAdd(slot, change);
              case ExchangeRefineryOrderChangeSet.GOOD_UPDATE -> goodUpdate(slot, change);
              case ExchangeRefineryOrderChangeSet.GOOD_REMOVE -> goodRemove(slot, op);
              case ExchangeRefineryOrderChangeSet.CANCEL -> cancel(slot, op);
              case ExchangeRefineryOrderChangeSet.DELETE -> delete(slot, op);
              case ExchangeRefineryOrderChangeSet.PICKUP -> pickup(slot, op);
              default -> throw new BadRequestException("Unknown refinery-order op");
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
      liveSync.refineryOrdersChanged(written, deleted);
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
      if (known != null && known.deleted) {
        known = null;
      }
      if (known != null) {
        known.refused = true;
        return new Outcome(
            ExchangeOps.REJECTED,
            ALREADY_EXISTS,
            known.id == null ? null : new Written(known.id.toString(), null, null));
      }
      Refs refs = change.refs();
      Outcome refusal =
          refs.refusal() == null && !refs.locationOffered()
              ? Outcome.rejected(ExchangeRefineryOrderReferences.NOT_A_REFINERY)
              : refs.refusal();
      if (refusal != null) {
        Slot refused = new Slot(null, null);
        refused.refused = true;
        byKey.put(key, refused);
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
              op.status() == null ? OPEN : op.status(),
              finances(op, null),
              List.copyOf(goods));
      if (dryRun) {
        byKey.put(key, new Slot(null, null));
        return Outcome.APPLIED;
      }
      linkRepository.deleteKey(caller.member(), caller.clientId(), key);
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
          State.of(order).toJournal());
      Slot slot = new Slot(order.id(), order);
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
      RefineryExchangeOrder current = slot.current;
      RefineryExchangeEdit.Finances finances = finances(op, current);
      RefineryExchangeEdit.Timer timer =
          op.endsAt() == null ? null : timer(op.endsAt(), op.durationMinutes(), now);
      boolean gameData =
          refs.location() != null || refs.method() != null || timer != null || op.status() != null;
      if (!gameData && finances == null) {
        return Outcome.UNCHANGED;
      }
      ExchangeRefineryOrderChangeSet.Versions versions = Objects.requireNonNull(op.versions());
      if ((gameData && !slot.gameDataIs(versions.gameData()))
          || (finances != null && !slot.financesAre(versions.finances()))) {
        return Outcome.rejected(VERSION_CONFLICT);
      }
      UUID location = refs.location();
      if (location != null
          && !refs.locationOffered()
          && (current == null || !location.equals(current.location().id()))) {
        return Outcome.rejected(ExchangeRefineryOrderReferences.NOT_A_REFINERY);
      }
      if (current != null
          && current.storedAt() != null
          && op.status() != null
          && !op.status().equals(current.status())) {
        return Outcome.rejected(ORDER_STORED);
      }
      RefineryExchangeEdit edit =
          new RefineryExchangeEdit(
              location,
              refs.method() == null ? null : new RefineryExchangeEdit.Method(refs.method()),
              timer,
              op.status(),
              finances,
              null);
      if (current != null && unchanged(current, edit)) {
        return Outcome.UNCHANGED;
      }
      return write(slot, edit, ExchangeJournalAction.REFINERY_UPDATE, gameData, finances != null);
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
        return Outcome.rejected(VERSION_CONFLICT);
      }
      ExchangeRefineryOrderChangeSet.Good good = Objects.requireNonNull(op.good());
      List<RefineryExchangeEdit.Good> goods =
          new ArrayList<>(slot.current == null ? List.of() : goodsOf(slot.current));
      goods.add(
          new RefineryExchangeEdit.Good(
              null,
              change.refs().inputs().getFirst(),
              good.inputQuantity(),
              good.outputQuantity(),
              good.quality()));
      return write(slot, goodsEdit(goods), ExchangeJournalAction.REFINERY_GOOD_ADD, true, false);
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
        return Outcome.rejected(VERSION_CONFLICT);
      }
      UUID goodId = ExchangeRefineryOrderReferences.parse(Objects.requireNonNull(op.goodId()));
      RefineryExchangeOrder current = slot.current;
      if (goodId == null || current == null) {
        return Outcome.rejected(GOOD_UNKNOWN);
      }
      List<RefineryExchangeEdit.Good> goods = new ArrayList<>();
      boolean found = false;
      boolean changes = false;
      for (RefineryExchangeEdit.Good good : goodsOf(current)) {
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
        return Outcome.rejected(GOOD_UNKNOWN);
      }
      if (!changes) {
        return Outcome.UNCHANGED;
      }
      return write(slot, goodsEdit(goods), ExchangeJournalAction.REFINERY_GOOD_UPDATE, true, false);
    }

    /**
     * Decides and writes a good-remove.
     *
     * @param slot the order
     * @param op the good-remove
     * @return the outcome
     */
    private @NotNull Outcome goodRemove(@NotNull Slot slot, @NotNull Op op) {
      if (!slot.gameDataIs(Objects.requireNonNull(op.versions()).gameData())) {
        return Outcome.rejected(VERSION_CONFLICT);
      }
      UUID goodId = ExchangeRefineryOrderReferences.parse(Objects.requireNonNull(op.goodId()));
      RefineryExchangeOrder current = slot.current;
      if (goodId == null || current == null) {
        return Outcome.rejected(GOOD_UNKNOWN);
      }
      List<RefineryExchangeEdit.Good> goods =
          goodsOf(current).stream().filter(good -> !goodId.equals(good.id())).toList();
      if (goods.size() == current.goods().size()) {
        return Outcome.rejected(GOOD_UNKNOWN);
      }
      return write(slot, goodsEdit(goods), ExchangeJournalAction.REFINERY_GOOD_REMOVE, true, false);
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
        return Outcome.rejected(VERSION_CONFLICT);
      }
      RefineryExchangeOrder current = slot.current;
      if (current != null && CANCELED.equals(current.status())) {
        return Outcome.UNCHANGED;
      }
      if (dryRun || slot.id == null) {
        slot.advance(true, false);
        return Outcome.APPLIED;
      }
      RefineryExchangeOrder before = Objects.requireNonNull(current);
      RefineryExchangeOrder after = writer.cancel(caller.member(), caller.clientId(), slot.id);
      journal(slot.id, ExchangeJournalAction.REFINERY_CANCEL, before, after);
      slot.current = after;
      written.add(slot.id);
      return applied(after, List.of());
    }

    /**
     * Decides and writes a pickup without store lines (REQ-XCH-053): an open or running order is
     * closed whatever its end, a completed one is left, a cancelled one is refused.
     *
     * @param slot the order
     * @param op the pickup
     * @return the outcome
     */
    private @NotNull Outcome pickup(@NotNull Slot slot, @NotNull Op op) {
      RefineryExchangeOrder current = slot.current;
      String status = current == null ? null : current.status();
      if (COMPLETED.equals(status)) {
        return Outcome.UNCHANGED;
      }
      if (CANCELED.equals(status)) {
        return Outcome.rejected(ORDER_CANCELED);
      }
      if (!slot.gameDataIs(Objects.requireNonNull(op.versions()).gameData())) {
        return Outcome.rejected(VERSION_CONFLICT);
      }
      if (dryRun || slot.id == null || current == null) {
        slot.advance(true, false);
        return Outcome.APPLIED;
      }
      RefineryExchangeOrder after = writer.pickUp(caller.member(), caller.clientId(), slot.id);
      journal(slot.id, ExchangeJournalAction.REFINERY_PICKUP, current, after);
      slot.current = after;
      written.add(slot.id);
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
        return Outcome.rejected(VERSION_CONFLICT);
      }
      RefineryExchangeOrder current = slot.current;
      if (current != null && current.storedAt() != null) {
        return Outcome.rejected(ORDER_STORED);
      }
      slot.deleted = true;
      if (dryRun || slot.id == null) {
        return Outcome.APPLIED;
      }
      State removed =
          State.of(Objects.requireNonNull(current))
              .deleted(writer.delete(caller.member(), slot.id));
      journalService.record(
          caller,
          batch,
          ExchangeJournalAction.REFINERY_DELETE,
          slot.id.toString(),
          true,
          removed.toJournal(),
          null);
      slot.current = null;
      written.remove(slot.id);
      deleted.add(slot.id);
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
          State.of(before).toJournal(),
          State.of(after).toJournal());
    }

    /**
     * Writes an edit through the refinery module, or in a dry run only advances the counters.
     *
     * @param slot the order
     * @param edit the edit
     * @param action the journal action
     * @param gameData whether the edit writes the game-data section
     * @param finances whether the edit writes the finances section
     * @return the applied outcome
     */
    private @NotNull Outcome write(
        @NotNull Slot slot,
        @NotNull RefineryExchangeEdit edit,
        @NotNull ExchangeJournalAction action,
        boolean gameData,
        boolean finances) {
      if (dryRun || slot.id == null) {
        slot.advance(gameData, finances);
        return Outcome.APPLIED;
      }
      RefineryExchangeOrder before = Objects.requireNonNull(slot.current);
      RefineryExchangeOrder after = writer.edit(caller.member(), caller.clientId(), slot.id, edit);
      journal(slot.id, action, before, after);
      slot.current = after;
      written.add(slot.id);
      Set<UUID> held = new LinkedHashSet<>();
      before.goods().forEach(good -> held.add(good.id()));
      return applied(
          after,
          after.goods().stream()
              .map(RefineryExchangeOrder.Good::id)
              .filter(id -> !held.contains(id))
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
        UUID id = ExchangeRefineryOrderReferences.parse(op.id());
        if (id == null) {
          return null;
        }
        Slot slot = byId.get(id);
        if (slot != null) {
          return slot;
        }
        Optional<RefineryExchangeOrder> order = writer.lockOwn(caller.member(), id);
        if (order.isEmpty()) {
          return null;
        }
        Slot loaded = new Slot(id, order.get());
        byId.put(id, loaded);
        return loaded;
      }
      String key = Objects.requireNonNull(op.clientKey());
      Slot slot = byKey.get(key);
      return slot != null ? slot : linked(key);
    }

    /**
     * Looks a client key up in the stored links and locks the order it names.
     *
     * @param key the client's key
     * @return the order, or {@code null} when the key names none the member owns now
     */
    private @Nullable Slot linked(@NotNull String key) {
      Optional<ExchangeRefineryOrderLink> link =
          linkRepository.findByUserIdAndClientIdAndClientKey(
              caller.member(), caller.clientId(), key);
      if (link.isEmpty()) {
        return null;
      }
      UUID id = link.get().getRefineryOrderId();
      Slot slot = byId.get(id);
      if (slot == null) {
        Optional<RefineryExchangeOrder> order = writer.lockOwn(caller.member(), id);
        if (order.isEmpty()) {
          return null;
        }
        slot = new Slot(id, order.get());
        byId.put(id, slot);
      }
      byKey.put(key, slot);
      return slot;
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
      @NotNull Op op, @Nullable RefineryExchangeOrder current) {
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
   * Whether an update would leave the order as it is.
   *
   * @param order the order
   * @param edit the update
   * @return {@code true} when every part it carries equals the order's
   */
  private static boolean unchanged(
      @NotNull RefineryExchangeOrder order, @NotNull RefineryExchangeEdit edit) {
    RefineryExchangeEdit.Finances finances = edit.finances();
    RefineryExchangeEdit.Timer timer = edit.timer();
    UUID method = order.method() == null ? null : order.method().id();
    return (edit.locationId() == null || edit.locationId().equals(order.location().id()))
        && (edit.method() == null || Objects.equals(edit.method().id(), method))
        && (edit.status() == null || edit.status().equals(order.status()))
        && (timer == null
            || (Objects.equals(timer.startedAt(), order.startedAt())
                && Objects.equals(timer.durationMinutes(), order.durationMinutes())))
        && (finances == null
            || (money(finances.expenses(), order.expenses())
                && money(finances.otherExpenses(), order.otherExpenses())
                && money(finances.oreSales(), order.oreSales())));
  }

  /**
   * Whether a requested money value stores as the order's.
   *
   * @param requested the requested value, 0 and {@code null} meaning none
   * @param stored the stored value
   * @return {@code true} when both store the same
   */
  private static boolean money(@Nullable Double requested, @Nullable Double stored) {
    Double normalised = requested == null || requested == 0.0 ? null : requested;
    return Objects.equals(normalised, stored);
  }

  /** One order as the earlier ops of the set left it. */
  private static final class Slot {

    /** The order's id, or {@code null} for one a dry run would create or a refused create. */
    private final @Nullable UUID id;

    /** The order as last read or written, or {@code null} when {@link #id} is. */
    private @Nullable RefineryExchangeOrder current;

    /** Whether an op on the order was refused. */
    private boolean refused;

    /** Whether an op of the set deleted the order, or would in a dry run. */
    private boolean deleted;

    /** The game-data counter a dry run advanced past the stored one. */
    private long gameDataSteps;

    /** The finances counter a dry run advanced past the stored one. */
    private long financesSteps;

    /**
     * Creates a slot.
     *
     * @param id the order's id, or {@code null}
     * @param current the order, or {@code null}
     */
    private Slot(@Nullable UUID id, @Nullable RefineryExchangeOrder current) {
      this.id = id;
      this.current = current;
    }

    /**
     * Whether the client's echo matches the game-data counter as the earlier ops left it.
     *
     * @param echoed the echo, or {@code null}
     * @return {@code true} when it matches
     */
    private boolean gameDataIs(@Nullable Long echoed) {
      long base = current == null ? 0L : current.gameDataVersion();
      return echoed != null && echoed == base + gameDataSteps;
    }

    /**
     * Whether the client's echo matches the finances counter as the earlier ops left it.
     *
     * @param echoed the echo, or {@code null}
     * @return {@code true} when it matches
     */
    private boolean financesAre(@Nullable Long echoed) {
      long base = current == null ? 0L : current.financesVersion();
      return echoed != null && echoed == base + financesSteps;
    }

    /**
     * Advances the counters a dry-run op would raise.
     *
     * @param gameData whether it raises game data
     * @param finances whether it raises the finances
     */
    private void advance(boolean gameData, boolean finances) {
      gameDataSteps += gameData ? 1 : 0;
      financesSteps += finances ? 1 : 0;
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
