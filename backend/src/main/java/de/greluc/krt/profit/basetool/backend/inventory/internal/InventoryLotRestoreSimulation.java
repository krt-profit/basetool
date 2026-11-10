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
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLotWritePlans.ExactQuantity;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLotWritePlans.LegacyQuantity;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLotWritePlans.RebookLot;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLotWritePlans.RestoreLot;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockQuantityComposition;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/** Simulates one historical lot group atomically through the shared owning stock decisions. */
final class InventoryLotRestoreSimulation {
  private InventoryLotRestoreSimulation() {}

  /** An effect-free refused or unavailable group, or its fully accepted exact mutation program. */
  record Result(List<InventoryLotBatchProgram.Mutation> mutations, boolean unavailable) {}

  /**
   * Prechecks every inverse against the same initial state before adopting a complete candidate.
   */
  static Result select(
      @NotNull RestoreLot intent,
      @NotNull InventoryLotProspectiveRows rows,
      @NotNull Set<UUID> memberships) {
    for (var request : intent.inversePoolMoves()) {
      var checked =
          InventoryLotRebookSimulation.select(
              new RebookLot(intent.step(), request), rows.fork(), memberships);
      if (checked.refusal() != null) {
        return new Result(List.of(checked), false);
      }
    }
    var candidate = rows.fork();
    candidate.beginStep(intent.step());
    List<InventoryLotBatchProgram.Mutation> mutations = new ArrayList<>();
    var ordered =
        intent.inversePoolMoves().stream()
            .sorted(
                Comparator.comparingInt(
                    request -> request.direction() == RebookDirection.TO_PERSONAL ? 0 : 1))
            .toList();
    for (var request : ordered) {
      var moved =
          InventoryLotRebookSimulation.select(
              new RebookLot(intent.step(), request), candidate, memberships);
      if (moved.refusal() != null) {
        return new Result(
            List.of(new InventoryLotBatchProgram.Rebooking(request, moved.refusal(), List.of())),
            false);
      }
      mutations.add(moved);
    }
    StockQuantityComposition target = target(intent, candidate);
    boolean whole =
        InventoryItem.countsWholeUnits(intent.lot().material(), intent.lot().gameItem());
    for (var source : candidate.lot(intent.lot())) {
      var removed =
          InventoryQuantityComposition.subtract(candidate.requireQuantity(source), target);
      if (removed.parts().isEmpty()) {
        continue;
      }
      BigDecimal amount = BigDecimal.valueOf(removed.totalQuanta(), 3);
      var permitted =
          InventoryStockDecisions.freeDraws(List.of(source.drawSource()), amount, whole);
      if (permitted.stream()
              .map(InventoryStockDecisions.Draw::amount)
              .reduce(BigDecimal.ZERO, BigDecimal::add)
              .compareTo(amount)
          != 0) {
        return new Result(List.of(), true);
      }
      candidate.lowerExact(source, removed);
      mutations.add(new InventoryLotBatchProgram.ExactBookOut(source.getKey(), removed));
    }
    StockQuantityComposition present = quantity(intent, candidate);
    StockQuantityComposition missing = InventoryQuantityComposition.subtract(target, present);
    if (!missing.parts().isEmpty()) {
      var booked =
          candidate.create(
              intent.lot(),
              true,
              null,
              BigDecimal.valueOf(missing.totalQuanta(), 3),
              missing,
              Set.of());
      var victims = candidate.merge(booked, whole);
      mutations.add(
          new InventoryLotBatchProgram.RestoredBookIn(
              intent.lot(), missing, booked.getKey(), victims));
    }
    rows.adopt(candidate);
    return new Result(List.copyOf(mutations), false);
  }

  /**
   * Resolves only explicit original proof or an actual before-boundary plus old journal reference.
   */
  private static StockQuantityComposition target(
      RestoreLot intent, InventoryLotProspectiveRows rows) {
    long amount = intent.targetTotal().movePointRight(3).longValueExact();
    StockQuantityComposition target =
        switch (intent.targetQuantity()) {
          case ExactQuantity exact -> exact.quantity();
          case LegacyQuantity legacy ->
              InventoryQuantityComposition.restoredLegacy(
                  legacy.firstKnownBeforeBoundary() == null
                      ? quantity(intent, rows)
                      : legacy.firstKnownBeforeBoundary(),
                  amount,
                  legacy.journalReference());
        };
    if (target.totalQuanta() != amount) {
      throw new IllegalArgumentException(
          "The restore target must describe its exact physical amount");
    }
    return InventoryQuantityComposition.validated(target);
  }

  /** Concatenates actual current row proof without dropping either evidence kind. */
  private static StockQuantityComposition quantity(
      RestoreLot intent, InventoryLotProspectiveRows rows) {
    return InventoryQuantityComposition.canonical(
        rows.lot(intent.lot()).stream()
            .flatMap(row -> rows.requireQuantity(row).parts().stream())
            .toList());
  }
}
