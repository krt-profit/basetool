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
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeEdit;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeReader;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeWriter;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
 * cancels, deletes and pickups with or without store lines, each through the refinery module's own
 * writer and journaled for the undo. The {@link ExchangeChangeSetRunner} runs the change set
 * (ADR-0285); an {@link ExchangeRefineryOrderRun} decides its ops; a planned delete of an order
 * that is not stored counts for the mass-change guard.
 *
 * <p>Each op keeps only the fields its kind uses. References resolve exactly in the plan;
 * everything that depends on the order — its address, an earlier refusal, the counters, the goods —
 * is decided when the op runs, so a refusal of one op refuses every later op on the same order
 * ({@code PRECEDING_OP_REJECTED}).
 */
@Service
@RequiredArgsConstructor
public class ExchangeRefineryOrderWriteService
    implements ExchangeChangeSetAdapter<
        ExchangeRefineryOrderChangeSet, ExchangeRefineryOrderWriteService.Change> {

  private final ExchangeRefineryOrderReferences references;
  private final RefineryExchangeWriter writer;
  private final ExchangeRefineryOrderKeys keys;
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
   * @return the counts, the order every applied op but a delete wrote and the detail of every op
   *     that was not applied
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
   * @return the counts, the order every applied op but a delete wrote and the detail of every op
   *     that was not applied
   * @throws BadRequestException when an op lacks a field its kind requires
   */
  @Transactional
  public @NotNull ExchangeChangeResultDto applyConfirmed(
      @NotNull ExchangeCaller caller, @NotNull ExchangeRefineryOrderChangeSet changeSet) {
    return runner.run(caller, this, changeSet, false);
  }

  /**
   * Counts the set's planned removals: the distinct orders of the member, not stored, that a delete
   * names. A delete is the only removal, so a cancel or a goods removal never holds a set back
   * (REQ-XCH-055).
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
    Set<UUID> ids = new HashSet<>();
    Set<String> named = new HashSet<>();
    for (Planned<Change> planned : plan) {
      if (planned instanceof Write<Change>(Change change)
          && change.op().kind() == Kind.DELETE
          && change.refs().refusal() == null) {
        Op op = change.op();
        if (op.id() == null) {
          named.add(op.clientKey());
        } else {
          UUID id = ExchangeIds.parse(op.id());
          if (id != null) {
            ids.add(id);
          }
        }
      }
    }
    if (ids.isEmpty() && named.isEmpty()) {
      return false;
    }
    ids.addAll(keys.named(caller.member(), caller.clientId(), named).values());
    long removals =
        reader.orders(caller.member(), ids).stream()
            .filter(order -> order.storedAt() == null)
            .count();
    return guard.requiresConfirmation(
        caller, ExchangeResource.REFINERY_ORDER, reader.count(caller.member()), removals);
  }

  /**
   * Keeps each op's fields its kind uses, checks them and resolves the references exactly; nothing
   * is locked yet.
   *
   * @param caller the caller
   * @param changeSet the writes
   * @return one write per op, carrying its resolved references or the refusal they lead to
   * @throws BadRequestException when an op lacks a field its kind requires
   */
  @Override
  public @NotNull List<Planned<Change>> plan(
      @NotNull ExchangeCaller caller, @NotNull ExchangeRefineryOrderChangeSet changeSet) {
    List<Op> ops = changeSet.ops().stream().map(Op::used).toList();
    ops.forEach(ExchangeRefineryOrderWriteService::requireFields);
    List<Refs> refs = references.resolve(caller, ops);
    List<Planned<Change>> plan = new ArrayList<>(ops.size());
    for (int i = 0; i < ops.size(); i++) {
      plan.add(ExchangeChangeSetAdapter.write(new Change(ops.get(i), refs.get(i))));
    }
    return plan;
  }

  /**
   * Locks what the set names and decides and writes each op in order; refreshes the open refinery
   * pages of every order it wrote afterwards.
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
    return ExchangeRefineryOrderRun.start(
        new ExchangeRefineryOrderRun.Collaborators(writer, keys, journalService, liveSync),
        caller,
        batch,
        plan,
        dryRun,
        Instant.now());
  }

  /**
   * Refuses an op that lacks a field its kind needs, or a store line that earmarks personal stock;
   * the gateway's schema keeps such an op from arriving.
   *
   * @param op the op, with only the fields its kind uses
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
          case UPDATE -> addressed && versions != null;
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
   * Derives the stored timer from the end a client reports (REQ-XCH-052). The end is taken to the
   * microsecond the database stores.
   *
   * @param endsAt when the run ends
   * @param durationMinutes the run's length, or {@code null} to start the run at the write
   * @param now the time of the write
   * @return {@code startedAt = endsAt - durationMinutes}; without a length the rest until {@code
   *     endsAt} in whole minutes, rounded up, so the stored end stays {@code endsAt}
   */
  static @NotNull RefineryExchangeEdit.Timer timer(
      @NotNull Instant endsAt, @Nullable Long durationMinutes, @NotNull Instant now) {
    Instant end = endsAt.truncatedTo(ChronoUnit.MICROS);
    long minutes =
        durationMinutes != null
            ? durationMinutes
            : Math.max(0L, (Duration.between(now, end).toSeconds() + 59L) / 60L);
    return new RefineryExchangeEdit.Timer(end.minus(Duration.ofMinutes(minutes)), minutes);
  }

  /**
   * One op with its references resolved.
   *
   * @param op the op, with only the fields its kind uses
   * @param refs its resolved references, or the refusal they lead to
   */
  record Change(@NotNull Op op, @NotNull Refs refs) {}
}
