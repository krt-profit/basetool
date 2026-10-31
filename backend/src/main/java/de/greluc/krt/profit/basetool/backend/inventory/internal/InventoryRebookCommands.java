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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import de.greluc.krt.profit.basetool.backend.inventory.api.RebookCommands;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockOfferLookup;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.ScuAmounts;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipQueryService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Lager's rebooking of a member's own lot between personal and one pool (REQ-INV-060), each
 * moved row split off through the web's own Umbuchung ({@link InventoryCheckoutService#rebookRow}),
 * or, for a shared row outside any pool, through {@link
 * InventoryPoolChangeService#moveUnitlessRowIntoPool}.
 */
@Service
@RequiredArgsConstructor
public class InventoryRebookCommands implements RebookCommands {

  private final InventoryItemRepository inventoryRepository;
  private final InventoryCheckoutService checkoutService;
  private final InventoryPoolChangeService poolChangeService;
  private final OrgUnitMembershipQueryService membershipQueryService;
  private final OrgUnitRepository orgUnitRepository;
  private final StockOfferLookup stockOfferLookup;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull RebookOutcome rebook(@NotNull Rebook rebook, boolean dryRun) {
    if (!(rebook.amount() > 0.0) || !Double.isFinite(rebook.amount())) {
      throw new BadRequestException("The amount must be positive");
    }
    StockLot lot = rebook.lot();
    boolean toPool = rebook.direction() == RebookDirection.TO_POOL;
    boolean wholeUnits = countsWholeUnits(lot);
    if (wholeUnits && rebook.amount() % 1 != 0) {
      return RebookOutcome.refused(RebookRefusal.UNIT_MISMATCH);
    }
    OrgUnit pool = null;
    if (toPool) {
      if (!membershipQueryService
          .findDirectMembershipOrgUnitIds(rebook.member())
          .contains(rebook.poolUnitId())) {
        return RebookOutcome.refused(RebookRefusal.NOT_A_MEMBER);
      }
      pool = orgUnitRepository.findById(rebook.poolUnitId()).orElseThrow();
    }
    List<InventoryItem> lotRows = lockLotRows(rebook.member(), lot);
    List<InventoryItem> sources =
        toPool
            ? shareSources(lotRows, rebook.poolUnitId())
            : unshareSources(lotRows, rebook.poolUnitId());
    double amount = ScuAmounts.roundToScuScale(rebook.amount());
    Map<UUID, Double> offered = new LinkedHashMap<>();
    for (InventoryItem source : sources) {
      offered.put(source.getId(), stockOfferLookup.activeOfferedAmount(source.getId()));
    }
    InventoryStockDecisions.LotRebook decision =
        InventoryStockDecisions.rebookLot(sources, offered, amount, toPool, wholeUnits);
    if (decision.refusal() != null) {
      return RebookOutcome.refused(decision.refusal());
    }
    if (dryRun) {
      return RebookOutcome.checked();
    }
    Map<UUID, RebookRowState> before = states(lotRows);
    for (InventoryStockDecisions.Take take : decision.takes()) {
      InventoryItem source = take.row();
      if (toPool && !Boolean.TRUE.equals(source.getPersonal())) {
        poolChangeService.moveUnitlessRowIntoPool(
            source, take.amount(), Objects.requireNonNull(pool));
      } else {
        checkoutService.rebookRow(
            source, take.amount(), !toPool, toPool ? pool : source.getOwningOrgUnit(), false);
      }
    }
    Map<UUID, RebookRowState> after = states(lockLotRows(rebook.member(), lot));
    return RebookOutcome.applied(changes(before, after));
  }

  /**
   * Orders the rows of the lot's personal part for a share: the personal rows stamped with the
   * target unit, then those without a unit, then the other personal rows, then the shared rows
   * outside any pool, each group oldest first.
   *
   * @param lotRows the member's rows of the lot
   * @param poolUnitId the target unit
   * @return the rows in the order a share takes them
   */
  private static @NotNull List<InventoryItem> shareSources(
      @NotNull List<InventoryItem> lotRows, @NotNull UUID poolUnitId) {
    return lotRows.stream()
        .filter(InventoryItem::countsAsPersonal)
        .sorted(
            Comparator.comparingInt((InventoryItem row) -> shareGroup(row, poolUnitId))
                .thenComparing(oldestFirst()))
        .toList();
  }

  /**
   * Returns a row's place in the share order.
   *
   * @param row a row of the lot's personal part
   * @param poolUnitId the target unit
   * @return {@code 0} for a personal row of the target unit, {@code 1} for one without a unit,
   *     {@code 2} for one of another unit, {@code 3} for a shared row outside any pool
   */
  private static int shareGroup(@NotNull InventoryItem row, @NotNull UUID poolUnitId) {
    if (!Boolean.TRUE.equals(row.getPersonal())) {
      return 3;
    }
    OrgUnit unit = row.getOwningOrgUnit();
    if (unit == null) {
      return 1;
    }
    return poolUnitId.equals(unit.getId()) ? 0 : 2;
  }

  /**
   * Picks the member's own shared rows of the lot in the pool, oldest first.
   *
   * @param lotRows the member's rows of the lot
   * @param poolUnitId the pool's unit
   * @return the shared rows in the order an unshare takes them
   */
  private static @NotNull List<InventoryItem> unshareSources(
      @NotNull List<InventoryItem> lotRows, @NotNull UUID poolUnitId) {
    return lotRows.stream()
        .filter(row -> !Boolean.TRUE.equals(row.getPersonal()))
        .filter(row ->
            row.getOwningOrgUnit() != null && poolUnitId.equals(row.getOwningOrgUnit().getId()))
        .sorted(oldestFirst())
        .toList();
  }

  /**
   * Orders rows by creation, the oldest first and the id breaking a tie.
   *
   * @return the comparator
   */
  private static @NotNull Comparator<InventoryItem> oldestFirst() {
    return Comparator.comparing(
            InventoryItem::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
        .thenComparing(InventoryItem::getId);
  }

  /**
   * Locks the member's rows of a lot, personal and shared.
   *
   * @param member the member
   * @param lot the lot
   * @return the rows, locked for this transaction
   */
  private @NotNull List<InventoryItem> lockLotRows(@NotNull UUID member, @NotNull StockLot lot) {
    return lot.material() != null
        ? inventoryRepository.lockMaterialLot(
            member,
            lot.material().getId(),
            lot.location().getId(),
            lot.quality() == null ? 0 : lot.quality(),
            lot.stolen())
        : inventoryRepository.lockItemLot(
            member,
            Objects.requireNonNull(lot.gameItem()).getId(),
            lot.location().getId(),
            lot.stolen());
  }

  /**
   * Tells whether a lot counts whole units: an item, or a piece-counted material.
   *
   * @param lot the lot
   * @return whether only whole amounts may move
   */
  private static boolean countsWholeUnits(@NotNull StockLot lot) {
    return InventoryItem.countsWholeUnits(lot.material(), lot.gameItem());
  }

  /**
   * Captures the rows' states by id, in their given order.
   *
   * @param rows the rows
   * @return the states
   */
  private static @NotNull Map<UUID, RebookRowState> states(@NotNull List<InventoryItem> rows) {
    Map<UUID, RebookRowState> states = new LinkedHashMap<>();
    for (InventoryItem row : rows) {
      OrgUnit unit = row.getOwningOrgUnit();
      states.put(
          row.getId(),
          new RebookRowState(
              row.getId(),
              Boolean.TRUE.equals(row.getPersonal()),
              unit == null ? null : unit.getId(),
              InventoryStockDecisions.amountOf(row),
              row.getVersion() == null ? 0L : row.getVersion(),
              InventoryAllocations.jobOrderEarmarks(row),
              InventoryAllocations.missionEarmarks(row)));
    }
    return states;
  }

  /**
   * Pairs the states before and after for every row whose state differs.
   *
   * @param before the states before
   * @param after the states after
   * @return the changed rows
   */
  private static @NotNull List<RebookedRow> changes(
      @NotNull Map<UUID, RebookRowState> before, @NotNull Map<UUID, RebookRowState> after) {
    Set<UUID> ids = new LinkedHashSet<>(before.keySet());
    ids.addAll(after.keySet());
    List<RebookedRow> changes = new ArrayList<>();
    for (UUID id : ids) {
      RebookRowState was = before.get(id);
      RebookRowState is = after.get(id);
      if (!Objects.equals(was, is)) {
        changes.add(new RebookedRow(was, is));
      }
    }
    return changes;
  }
}
