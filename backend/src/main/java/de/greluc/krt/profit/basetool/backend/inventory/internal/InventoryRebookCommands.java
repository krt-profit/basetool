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

import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
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
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Lager's rebooking of a member's own lot between personal and one pool (REQ-INV-060), each
 * moved row split off through the web's own Umbuchung ({@link InventoryCheckoutService#rebookRow}).
 */
@Service
@RequiredArgsConstructor
public class InventoryRebookCommands implements RebookCommands {

  /** The tolerance of an amount comparison, which only absorbs floating-point noise. */
  private static final double EPSILON = AllocationReductions.REDUCTION_EPSILON;

  private final InventoryItemRepository inventoryRepository;
  private final InventoryCheckoutService checkoutService;
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
    RebookRefusal refusal = check(sources, offered, amount, toPool, wholeUnits);
    if (refusal != null) {
      return RebookOutcome.refused(refusal);
    }
    if (dryRun) {
      return RebookOutcome.checked();
    }
    Map<UUID, RebookRowState> before = states(lotRows);
    double left = amount;
    for (InventoryItem source : sources) {
      if (left <= EPSILON) {
        break;
      }
      double take =
          Math.min(
              left, movable(source, offered.getOrDefault(source.getId(), 0.0), toPool, wholeUnits));
      if (take <= EPSILON) {
        continue;
      }
      checkoutService.rebookRow(
          source, take, !toPool, toPool ? pool : source.getOwningOrgUnit(), false);
      left = ScuAmounts.roundToScuScale(left - take);
    }
    Map<UUID, RebookRowState> after = states(lockLotRows(rebook.member(), lot));
    return RebookOutcome.applied(changes(before, after));
  }

  /**
   * Decides a rebooking before anything is written: enough stock at the source, then enough of it
   * free of earmarks, then enough left above the Materialbörse offers.
   *
   * @param sources the rows the rebooking may take from
   * @param offered the amount each source offers on the Materialbörse, by row
   * @param amount the amount to move
   * @param toPool whether the stock moves into the pool
   * @param wholeUnits whether the lot counts whole units
   * @return the refusal, or {@code null} when the rebooking may happen
   */
  private static @Nullable RebookRefusal check(
      @NotNull List<InventoryItem> sources,
      @NotNull Map<UUID, Double> offered,
      double amount,
      boolean toPool,
      boolean wholeUnits) {
    double held = 0.0;
    double unreserved = 0.0;
    double movable = 0.0;
    for (InventoryItem source : sources) {
      held += amountOf(source);
      unreserved += unreserved(source, toPool, wholeUnits);
      movable += movable(source, offered.getOrDefault(source.getId(), 0.0), toPool, wholeUnits);
    }
    if (amount > held + EPSILON) {
      return RebookRefusal.INSUFFICIENT_STOCK;
    }
    if (amount > unreserved + EPSILON) {
      return RebookRefusal.STOCK_EARMARKED;
    }
    if (amount > movable + EPSILON) {
      return RebookRefusal.STOCK_OFFERED;
    }
    return null;
  }

  /**
   * Returns the part of a row no earmark holds. A shared row earmarked to a job order or mission
   * cannot become personal at all, as in the web.
   *
   * @param row the row
   * @param toPool whether the stock moves into the pool
   * @param wholeUnits whether the lot counts whole units
   * @return the unreserved amount, never negative
   */
  private static double unreserved(@NotNull InventoryItem row, boolean toPool, boolean wholeUnits) {
    double reserved;
    if (toPool) {
      reserved =
          Math.max(Math.max(row.jobOrderReserved(), row.missionReserved()), row.donationReserved());
    } else if (!row.getJobOrderAllocations().isEmpty() || !row.getMissionAllocations().isEmpty()) {
      return 0.0;
    } else {
      reserved = row.donationReserved();
    }
    return free(amountOf(row), reserved, wholeUnits);
  }

  /**
   * Returns the part of a row a rebooking may take: unreserved and above its Materialbörse offer.
   *
   * @param row the row
   * @param offered the amount the row offers on the Materialbörse
   * @param toPool whether the stock moves into the pool
   * @param wholeUnits whether the lot counts whole units
   * @return the movable amount, never negative
   */
  private static double movable(
      @NotNull InventoryItem row, double offered, boolean toPool, boolean wholeUnits) {
    return Math.min(unreserved(row, toPool, wholeUnits), free(amountOf(row), offered, wholeUnits));
  }

  /**
   * Returns what is left of an amount above a held part, in whole units where the lot counts them.
   *
   * @param amount the row's amount
   * @param held the part that must stay
   * @param wholeUnits whether the lot counts whole units
   * @return the free amount, never negative
   */
  private static double free(double amount, double held, boolean wholeUnits) {
    double free = Math.max(0.0, ScuAmounts.roundToScuScale(amount - held));
    return wholeUnits ? Math.floor(free + EPSILON) : free;
  }

  /**
   * Returns a row's amount.
   *
   * @param row the row
   * @return the amount, {@code 0} when unset
   */
  private static double amountOf(@NotNull InventoryItem row) {
    return row.getAmount() == null ? 0.0 : row.getAmount();
  }

  /**
   * Orders the member's personal rows of the lot for a share: the rows stamped with the target
   * unit, then the rows without a unit, then the rest, each group oldest first.
   *
   * @param lotRows the member's rows of the lot
   * @param poolUnitId the target unit
   * @return the personal rows in the order a share takes them
   */
  private static @NotNull List<InventoryItem> shareSources(
      @NotNull List<InventoryItem> lotRows, @NotNull UUID poolUnitId) {
    return lotRows.stream()
        .filter(row -> Boolean.TRUE.equals(row.getPersonal()))
        .sorted(
            Comparator.comparingInt((InventoryItem row) -> shareGroup(row, poolUnitId))
                .thenComparing(oldestFirst()))
        .toList();
  }

  /**
   * Returns a personal row's place in the share order.
   *
   * @param row the row
   * @param poolUnitId the target unit
   * @return {@code 0} for the target unit, {@code 1} for no unit, {@code 2} for another unit
   */
  private static int shareGroup(@NotNull InventoryItem row, @NotNull UUID poolUnitId) {
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
        .filter(
            row ->
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
    return lot.gameItem() != null
        || (lot.material() != null && lot.material().getQuantityType() == QuantityType.PIECE);
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
              amountOf(row),
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
