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

import de.greluc.krt.profit.basetool.backend.exchange.api.ExchangeProblemException;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeChangeSetAdapter.Execution;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeChangeSetAdapter.Planned;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeChangeSetAdapter.Write;
import de.greluc.krt.profit.basetool.backend.exchange.internal.OrgStockPlan.PoolWrite;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeChangeResultDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeOrgStockChangeSet;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeOrgStockMembersDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeOrgStockTargetsDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeOrgStockUnitDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeOrgUnitDto;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockReads;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Coordinates org-stock change sets through planning, guarded execution and read lookups. */
@Service
@RequiredArgsConstructor
public class ExchangeOrgStockWriteService
    implements ExchangeChangeSetAdapter<ExchangeOrgStockChangeSet, OrgStockPlan.PoolWrite> {
  private final ExchangeOrgUnitService orgUnitService;
  private final ExchangeOrgStockRoster roster;
  private final ExchangeOrgStockTargets targets;
  private final ExchangeMassChangeGuard guard;
  private final StockReads stockReads;
  private final ExchangeChangeSetRunner runner;
  private final OrgStockPlanner planner;
  private final OrgStockExecutor executor;

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
    return planner.plan(caller, changeSet);
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
    return executor.start(caller, batch, plan, dryRun);
  }
}
