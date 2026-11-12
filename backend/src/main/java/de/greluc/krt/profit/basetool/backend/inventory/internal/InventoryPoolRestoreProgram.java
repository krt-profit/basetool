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

import de.greluc.krt.profit.basetool.backend.inventory.api.OrderMaterialScope;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolEntryState;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolRestore;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolRestoreResult;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLotWritePlans.EntryRestore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Selects actual historical-entry mutation resources independently of read-only named observations.
 */
record InventoryPoolRestoreProgram(
    List<Step> steps,
    Set<UUID> resources,
    Set<Long> lotKeys,
    Set<OrderMaterialScope> targetScopes,
    Set<UUID> targetOrders) {

  InventoryPoolRestoreProgram {
    steps = List.copyOf(steps);
    resources = Set.copyOf(resources);
    lotKeys = Set.copyOf(lotKeys);
    targetScopes = Set.copyOf(targetScopes);
    targetOrders = Set.copyOf(targetOrders);
  }

  /** One immutable named-row decision with its explicit pre-mutation physical presence. */
  record Step(
      EntryRestore intent,
      InventoryPoolRestoreDecisions.Decision decision,
      Optional<InventoryPoolRestoreFacts.Entry> before) {

    /**
     * Compares policy and physical facts without comparing JPA reference object representations.
     */
    boolean sameSelection(Step other) {
      return intent.equals(other.intent)
          && decision.result() == other.decision.result()
          && decision.changes() == other.decision.changes()
          && Objects.equals(decision.targetState(), other.decision.targetState())
          && (!decision.changes() || before.equals(other.before));
    }
  }

  /** Plans each unique named entry using the shared owning expected-state and target decision. */
  static InventoryPoolRestoreProgram plan(
      List<EntryRestore> requests,
      Map<UUID, Optional<InventoryPoolRestoreFacts.Entry>> current,
      InventoryPoolRestoreDecisions decisions) {
    List<PoolRestore> restores = requests.stream().map(EntryRestore::request).toList();
    var targets = decisions.targets(restores, current);
    List<Step> steps = new ArrayList<>();
    Set<UUID> resources = new HashSet<>();
    Set<Long> lots = new HashSet<>();
    Set<OrderMaterialScope> scopes = new HashSet<>();
    Set<UUID> orders = new HashSet<>();
    for (EntryRestore request : requests) {
      UUID id = request.request().entryId();
      Optional<InventoryPoolRestoreFacts.Entry> before = Objects.requireNonNull(current.get(id));
      before.ifPresent(entry -> lots.add(currentLotKey(entry.row())));
      PoolEntryState expected = request.request().expected();
      if (expected != null) {
        lots.add(InventoryLotLockRepository.lotKey(expected.holder(), expected.lotKey()));
      }
      PoolEntryState proposed = request.request().target();
      if (proposed != null) {
        lots.add(InventoryLotLockRepository.lotKey(proposed.holder(), proposed.lotKey()));
      }
      var decision = decisions.decide(request, before, targets);
      steps.add(new Step(request, decision, before));
      if (!decision.changes()) {
        continue;
      }
      resources.add(id);
      PoolEntryState target = decision.targetState();
      if (target != null) {
        var resolved = Objects.requireNonNull(decision.target());
        lots.add(InventoryLotLockRepository.lotKey(target.holder(), target.lotKey()));
        orders.addAll(target.jobOrders().keySet());
        var material = resolved.material();
        if (material != null) {
          target
              .jobOrders()
              .keySet()
              .forEach(order -> scopes.add(new OrderMaterialScope(order, material.getId())));
        }
      }
    }
    return new InventoryPoolRestoreProgram(steps, resources, lots, scopes, orders);
  }

  /** Returns the fixed owning result of every historical operation in declared prefix order. */
  List<PoolRestoreResult> results() {
    return steps.stream().map(step -> step.decision().result()).toList();
  }

  /** Returns the immutable fixed historical requests in declared prefix order. */
  List<PoolRestore> requests() {
    return steps.stream().map(step -> step.intent().request()).toList();
  }

  /** Reads the current physical holder/lot key without attaching mutation authority to the row. */
  private static long currentLotKey(InventoryStockRowSnapshot row) {
    return InventoryLotLockRepository.lotKey(
        row.getUser().getId(),
        StockLot.key(
            row.getMaterial() == null ? null : row.getMaterial().getId(),
            row.getGameItem() == null ? null : row.getGameItem().getId(),
            row.getLocation().getId(),
            row.getQuality(),
            row.getStolen()));
  }
}
