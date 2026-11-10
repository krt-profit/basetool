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

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.Nullable;

/** Selects canonical oldest-first earmarked item prefixes without physical row locks. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class InventoryItemConsumptionPrefix {
  /** The physical quantity remainder at which a row is depleted. */
  static final double DEPLETION_EPSILON = 1e-4;

  /** Immutable physical source facts captured before a consumption waits for its lot locks. */
  record Source(
      UUID id,
      UUID gameItem,
      double amount,
      double slice,
      @Nullable Long version,
      @Nullable Instant createdAt) {}

  /** One exact positive take from the owning source order. */
  record Take(UUID rowId, double amount) {}

  /** The selected source epochs and takes, both in deterministic physical selection order. */
  record Selection(List<Source> sources, List<Take> takes) {
    /** Retains immutable source epochs and positive takes. */
    Selection {
      sources = List.copyOf(sources);
      takes = List.copyOf(takes);
    }

    /** Returns the exact existing physical rows this selection changes. */
    List<UUID> rowIds() {
      return takes.stream().map(Take::rowId).toList();
    }
  }

  /**
   * Selects each requested item's source prefix from the batch query's owning oldest-first order.
   */
  static Selection select(List<InventoryItemConsumptionSource> facts, Map<UUID, Double> requested) {
    Map<UUID, Double> remaining = new HashMap<>(requested);
    List<Source> selected = new ArrayList<>();
    List<Take> takes = new ArrayList<>();
    for (var fact : facts) {
      double left = remaining.getOrDefault(fact.getGameItemId(), 0.0);
      if (left <= DEPLETION_EPSILON || fact.getSliceAmount() <= DEPLETION_EPSILON) {
        continue;
      }
      double take = Math.min(left, fact.getSliceAmount());
      selected.add(
          new Source(
              fact.getRowId(),
              fact.getGameItemId(),
              fact.getAmount(),
              fact.getSliceAmount(),
              fact.getVersion(),
              fact.getCreatedAt()));
      takes.add(new Take(fact.getRowId(), take));
      remaining.put(fact.getGameItemId(), left - take);
    }
    return new Selection(selected, takes);
  }
}
