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

import de.greluc.krt.profit.basetool.backend.inventory.api.RebookCommands.Rebook;
import de.greluc.krt.profit.basetool.backend.inventory.api.RebookCommands.RebookRefusal;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLotWritePlans.Intent;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/** The exact prospective physical operations and existing resources of one holder action. */
record InventoryLotBatchProgram(
    List<Mark> prelude, Map<Integer, Step> steps, Set<UUID> resources, Set<Integer> unavailable) {

  /** A real row identity or an action-local prospective result, never a quantity origin. */
  sealed interface RowKey permits Existing, Created {}

  /** An existing physical row. */
  record Existing(UUID id) implements RowKey {}

  /** A prospective row created by one operation of this action. */
  record Created(int step, int sequence) implements RowKey {}

  /** A physical command whose sources and destinations have already been selected. */
  sealed interface Mutation permits BookIn, BookOut, Mark, Rebooking {}

  /** A personal booking with its actual merge victims. */
  record BookIn(StockLot lot, BigDecimal amount, RowKey target, Set<RowKey> victims)
      implements Mutation {}

  /** A free-stock deduction from one selected row. */
  record BookOut(RowKey source, BigDecimal amount) implements Mutation {}

  /** A whole-row marker change or an unearmarked split with its actual merge victims. */
  record Mark(
      RowKey source,
      RowKey target,
      boolean stolen,
      BigDecimal amount,
      boolean whole,
      Set<RowKey> victims)
      implements Mutation {}

  /** One exact owning rebooking take and its resulting target. */
  record Transfer(RowKey source, RowKey target, BigDecimal amount, Set<RowKey> victims) {}

  /** An ordinary rebooking refusal or its complete ordered source selection. */
  record Rebooking(Rebook request, @Nullable RebookRefusal refusal, List<Transfer> takes)
      implements Mutation {}

  /** The immutable command sequence belonging to one final calling-policy operation. */
  record Step(Intent intent, List<Mutation> mutations) {}
}
