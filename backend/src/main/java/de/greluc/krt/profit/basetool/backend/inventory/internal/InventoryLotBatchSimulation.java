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

import de.greluc.krt.profit.basetool.backend.inventory.api.RebookCommands.RebookDirection;
import de.greluc.krt.profit.basetool.backend.inventory.api.RebookCommands.RebookRefusal;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLotWritePlans.Intent;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLotWritePlans.RebookLot;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLotWritePlans.SetLotAmount;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Selects all physical prefixes and merges of one action without applying stock or policy changes.
 */
final class InventoryLotBatchSimulation {
  private final InventoryLotProspectiveRows rows;
  private final Set<UUID> memberships;
  private final Map<Integer, BigDecimal> flipped = new HashMap<>();
  private final List<InventoryLotBatchProgram.Mark> prelude = new ArrayList<>();
  private final Map<String, RebookRefusal> refusedLots = new HashMap<>();
  private final Set<Integer> unavailable = new HashSet<>();

  /** Creates independent physical state for one preliminary or final operation sequence. */
  InventoryLotBatchSimulation(InventoryStockPlanningReads.LotRows observed, Set<UUID> memberships) {
    rows = new InventoryLotProspectiveRows(observed);
    this.memberships = memberships;
  }

  /** Returns the complete physical program, including its once-only stolen prelude. */
  InventoryLotBatchProgram simulate(List<Intent> intents) {
    checkObservedTotals(intents);
    markTwins(intents);
    Map<Integer, InventoryLotBatchProgram.Step> steps = new LinkedHashMap<>();
    for (Intent intent : intents) {
      rows.beginStep(intent.step());
      List<InventoryLotBatchProgram.Mutation> mutations =
          switch (intent) {
            case SetLotAmount set -> unavailable.contains(set.step()) ? List.of() : setAmount(set);
            case RebookLot rebook -> List.of(rebook(rebook));
          };
      if (steps.putIfAbsent(intent.step(), new InventoryLotBatchProgram.Step(intent, mutations))
          != null) {
        throw new IllegalArgumentException("Stock action step identities must be unique");
      }
    }
    return new InventoryLotBatchProgram(
        List.copyOf(prelude), Map.copyOf(steps), rows.resources(), Set.copyOf(unavailable));
  }

  /** Leaves stale total-driven intents unexecuted so the calling policy can decide its refusal. */
  private void checkObservedTotals(List<Intent> intents) {
    Map<String, BigDecimal> quantities = new HashMap<>();
    for (Intent intent : intents) {
      if (intent instanceof SetLotAmount set) {
        BigDecimal current =
            quantities.computeIfAbsent(
                set.lot().key(),
                _ ->
                    rows.lot(set.lot()).stream()
                        .map(InventoryLotProspectiveRows.Row::getAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .setScale(whole(set.lot()) ? 0 : 3, java.math.RoundingMode.HALF_UP));
        if (current.compareTo(set.observedTotal()) != 0) {
          unavailable.add(set.step());
        } else {
          quantities.put(set.lot().key(), set.target());
        }
      }
    }
  }

  /** Selects every stolen twin before the ordinary quantity and rebooking steps. */
  private void markTwins(List<Intent> intents) {
    List<SetLotAmount> rises =
        intents.stream()
            .filter(SetLotAmount.class::isInstance)
            .map(SetLotAmount.class::cast)
            .filter(set ->
                !unavailable.contains(set.step())
                    && set.target().compareTo(set.observedTotal()) > 0)
            .toList();
    Map<Integer, BigDecimal> room = new HashMap<>();
    rises.forEach(set -> room.put(set.step(), set.target().subtract(set.observedTotal())));
    Map<Integer, List<InventoryLotBatchProgram.RowKey>> initial = new HashMap<>();
    for (Intent intent : intents) {
      if (intent instanceof SetLotAmount set) {
        initial.put(
            set.step(),
            rows.lot(set.lot()).stream().map(InventoryLotProspectiveRows.Row::getKey).toList());
      }
    }
    for (Intent intent : intents) {
      if (!(intent instanceof SetLotAmount fall)
          || unavailable.contains(fall.step())
          || fall.target().compareTo(fall.observedTotal()) >= 0) {
        continue;
      }
      BigDecimal left = fall.observedTotal().subtract(fall.target());
      rows.beginStep(fall.step());
      for (SetLotAmount rise : rises) {
        if (left.signum() <= 0) {
          break;
        }
        BigDecimal available = Objects.requireNonNull(room.get(rise.step()));
        if (!twins(fall.lot(), rise.lot()) || available.signum() <= 0) {
          continue;
        }
        Set<InventoryLotBatchProgram.RowKey> original =
            Set.copyOf(Objects.requireNonNull(initial.get(fall.step())));
        var sources =
            rows.lot(fall.lot()).stream()
                .filter(row -> original.contains(row.getKey()))
                .map(InventoryLotProspectiveRows.Row::drawSource)
                .toList();
        var draws =
            InventoryStockDecisions.stolenDraws(
                sources, rise.lot().stolen(), left.min(available), whole(fall.lot()));
        BigDecimal marked = BigDecimal.ZERO;
        for (var draw : draws) {
          var source = rows.require(draw.key());
          InventoryLotProspectiveRows.Row target;
          if (draw.whole()) {
            rows.place(source, rise.lot(), source.isPersonal(), source.getUnit());
            target = source;
          } else {
            target = rows.create(rise.lot(), source.isPersonal(), source.getUnit(), draw.amount());
            rows.lower(source, draw.amount());
          }
          var victims = rows.merge(target, whole(rise.lot()));
          prelude.add(
              new InventoryLotBatchProgram.Mark(
                  source.getKey(),
                  target.getKey(),
                  rise.lot().stolen(),
                  draw.amount(),
                  draw.whole(),
                  victims));
          marked = marked.add(draw.amount());
        }
        left = left.subtract(marked);
        room.put(rise.step(), available.subtract(marked));
        flipped.merge(fall.step(), marked, BigDecimal::add);
        flipped.merge(rise.step(), marked, BigDecimal::add);
      }
    }
  }

  /** Selects the exact creation or free-source prefix left after the stolen prelude. */
  private List<InventoryLotBatchProgram.Mutation> setAmount(SetLotAmount set) {
    BigDecimal delta = set.target().subtract(set.observedTotal());
    BigDecimal booked = delta.abs().subtract(flipped.getOrDefault(set.step(), BigDecimal.ZERO));
    if (booked.signum() <= 0) {
      return List.of();
    }
    if (delta.signum() > 0) {
      var target = rows.create(set.lot(), true, null, booked);
      var victims = rows.merge(target, whole(set.lot()));
      return List.of(
          new InventoryLotBatchProgram.BookIn(set.lot(), booked, target.getKey(), victims));
    }
    var draws =
        InventoryStockDecisions.freeDraws(
            rows.lot(set.lot()).stream().map(InventoryLotProspectiveRows.Row::drawSource).toList(),
            booked,
            whole(set.lot()));
    if (draws.stream()
            .map(InventoryStockDecisions.Draw::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .compareTo(booked)
        != 0) {
      unavailable.add(set.step());
      return List.of();
    }
    List<InventoryLotBatchProgram.Mutation> mutations = new ArrayList<>();
    for (var draw : draws) {
      rows.lower(rows.require(draw.key()), draw.amount());
      mutations.add(new InventoryLotBatchProgram.BookOut(draw.key(), draw.amount()));
    }
    return List.copyOf(mutations);
  }

  /** Simulates the owning canonical rebooking decision and exact destination survivor. */
  private InventoryLotBatchProgram.Rebooking rebook(RebookLot intent) {
    var request = intent.request();
    if (refusedLots.containsKey(request.lot().key())) {
      return new InventoryLotBatchProgram.Rebooking(
          request, refusedLots.get(request.lot().key()), List.of());
    }
    boolean toPool = request.direction() == RebookDirection.TO_POOL;
    if (toPool && !memberships.contains(request.poolUnitId())) {
      refusedLots.put(request.lot().key(), RebookRefusal.NOT_A_MEMBER);
      return new InventoryLotBatchProgram.Rebooking(request, RebookRefusal.NOT_A_MEMBER, List.of());
    }
    var sources =
        rows.rebookSources(request.lot(), request.poolUnitId(), toPool).stream()
            .map(InventoryLotProspectiveRows.Row::rebookSource)
            .toList();
    var selected =
        InventoryStockDecisions.selectRebookSources(
            sources, request.amount(), toPool, whole(request.lot()));
    if (selected.refusal() != null) {
      refusedLots.put(request.lot().key(), selected.refusal());
    }
    List<InventoryLotBatchProgram.Transfer> takes = new ArrayList<>();
    for (var take : selected.takes()) {
      var source = rows.require(take.key());
      BigDecimal amount = BigDecimal.valueOf(take.amount());
      InventoryLotProspectiveRows.Row target;
      if (toPool && !source.isPersonal() && amount.compareTo(source.getAmount()) == 0) {
        rows.place(source, request.lot(), false, request.poolUnitId());
        target = source;
      } else {
        target =
            rows.create(
                request.lot(), !toPool, toPool ? request.poolUnitId() : source.getUnit(), amount);
        rows.lower(source, amount);
      }
      var victims = rows.merge(target, whole(request.lot()));
      takes.add(
          new InventoryLotBatchProgram.Transfer(source.getKey(), target.getKey(), amount, victims));
    }
    return new InventoryLotBatchProgram.Rebooking(request, selected.refusal(), List.copyOf(takes));
  }

  /** Returns the owning whole-unit merge and quantity policy of a physical lot. */
  private static boolean whole(StockLot lot) {
    return InventoryItem.countsWholeUnits(lot.material(), lot.gameItem());
  }

  /** Reports whether two lots differ only in their stolen marker. */
  private static boolean twins(StockLot left, StockLot right) {
    return left.stolen() != right.stolen()
        && Objects.equals(left.material(), right.material())
        && Objects.equals(left.gameItem(), right.gameItem())
        && left.location().getId().equals(right.location().getId())
        && Objects.equals(left.quality(), right.quality());
  }
}
