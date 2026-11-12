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

import de.greluc.krt.profit.basetool.backend.inventory.api.StockLotWritePlans;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockQuantityComposition;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Attributes actual physical evidence to the admitted logical operation order. */
final class InventoryLotQuantityLedger {
  private final Map<String, StockQuantityComposition> quantities;
  private final List<InventoryLotBatchProgram.Mark> prelude;

  /** Starts with the complete locked quantity after the accepted historical entry prefix. */
  InventoryLotQuantityLedger(
      Map<String, StockQuantityComposition> quantities,
      List<InventoryLotBatchProgram.Mark> prelude) {
    this.quantities = new HashMap<>(quantities);
    this.prelude = List.copyOf(prelude);
  }

  /** Returns the current complete logical quantity of a declared physical lot. */
  StockQuantityComposition quantity(String lot) {
    return Objects.requireNonNull(quantities.get(lot));
  }

  /** Attributes the already executed physical marker transfers to their admitted steps. */
  void begin(StockLotWritePlans.Intent intent) {
    String lot = key(intent);
    for (var mark : prelude) {
      if (mark.fallStep() == intent.step()) {
        remove(lot, mark.quantity());
      }
      if (mark.riseStep() == intent.step()) {
        add(lot, mark.quantity());
      }
    }
  }

  /** Removes only parts proved to be present in the current complete logical quantity. */
  void remove(String lot, StockQuantityComposition removed) {
    StockQuantityComposition before = quantity(lot);
    StockQuantityComposition after = InventoryQuantityComposition.subtract(before, removed);
    if (before.totalQuanta() - after.totalQuanta() != removed.totalQuanta()) {
      throw new BusinessConflictException("error.inventory.concurrent_scope_changed");
    }
    quantities.put(lot, after);
  }

  /** Adds evidence proved by the actual owning command without regrouping mixed proof kinds. */
  void add(String lot, StockQuantityComposition added) {
    var parts = new ArrayList<>(quantity(lot).parts());
    parts.addAll(added.parts());
    quantities.put(lot, InventoryQuantityComposition.canonical(parts));
  }

  /** Resolves the physical lot of an admitted operation. */
  static String key(StockLotWritePlans.Intent intent) {
    return switch (intent) {
      case StockLotWritePlans.SetLotAmount set -> set.lot().key();
      case StockLotWritePlans.RebookLot rebook -> rebook.request().lot().key();
      case StockLotWritePlans.RestoreLot restore -> restore.lot().key();
    };
  }
}
