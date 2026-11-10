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

import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolAllocationTarget;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolRelocation;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockCommands.StockBookInScope;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Collects one org-stock batch's complete material, lot and row lock plan.
 *
 * @param entries explicitly written stock entries
 * @param relocations requested physical placement changes
 * @param allocationTargets requested targets of current stock rows
 * @param bookings exact initial stock destinations
 */
record ExchangeOrgStockLocks(
    Set<UUID> entries,
    List<PoolRelocation> relocations,
    List<PoolAllocationTarget> allocationTargets,
    List<StockBookInScope> bookings) {

  /** Supplies the stock resource scopes of one planned operation. */
  interface Contributor {
    /**
     * Adds the operation's complete current and requested resource set.
     *
     * @param locks the batch's accumulated lock plan
     */
    void lockInto(@NotNull ExchangeOrgStockLocks locks);
  }

  /**
   * Applies one complete batch plan before any of its operations runs.
   *
   * @param commands the Inventory-owned lock seam
   * @param contributors the operations that can write stock
   */
  static void lock(
      @NotNull PoolEntryCommands commands,
      @NotNull Collection<? extends Contributor> contributors) {
    ExchangeOrgStockLocks locks =
        new ExchangeOrgStockLocks(
            new HashSet<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    contributors.forEach(contributor -> contributor.lockInto(locks));
    if (!locks.entries().isEmpty()
        || !locks.relocations().isEmpty()
        || !locks.bookings().isEmpty()) {
      commands.lockLots(
          locks.entries(), locks.relocations(), locks.allocationTargets(), locks.bookings());
    }
  }
}
