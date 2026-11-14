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

import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryItemDto;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockRelocationLock;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

/**
 * Marks the Lager rows and stacks of other holders that a pending relocation locks, for the „Umzug
 * ausstehend" chip and the disabled row actions (REQ-PATCH-023): a row from before the open
 * home-location phase's live time, or without a creation time, of a holder who has not chosen yet.
 */
@Component
@RequiredArgsConstructor
class InventoryRelocationMarks {

  private final StockRelocationLock lock;

  /**
   * Marks the locked rows of a page; a row the lock refuses the caller is also marked locked.
   *
   * @param rows the page of rows, their holders and creation times set
   * @return the page with the locked rows marked, the same page while nothing is locked
   */
  @NotNull
  Page<InventoryItemDto> markRows(@NotNull Page<InventoryItemDto> rows) {
    Optional<Instant> liveAt = lock.openPhaseLiveAt();
    if (rows.isEmpty() || liveAt.isEmpty()) {
      return rows;
    }
    Set<UUID> pending =
        lock.pendingAmong(
            rows.getContent().stream()
                .map(InventoryRelocationMarks::holderId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet()));
    if (pending.isEmpty()) {
      return rows;
    }
    Set<UUID> refused = lock.refusedAmong(pending);
    Instant cutoff = liveAt.get();
    return rows.map(row -> {
      UUID holder = holderId(row);
      if (holder == null || !pending.contains(holder) || !fromBefore(row, cutoff)) {
        return row;
      }
      return row.withRelocation(true, refused.contains(holder));
    });
  }

  /**
   * Returns the holders among the given ones who have not chosen yet, for the marker on a stack.
   *
   * @param holders the stacks' holders
   * @return the pending holders, empty while no phase is open
   */
  @NotNull
  @Unmodifiable
  Set<UUID> pendingHolders(@NotNull Collection<UUID> holders) {
    return holders.isEmpty() ? Set.of() : lock.pendingAmong(holders);
  }

  /**
   * Tells whether a row is from before a cutoff; a row without a creation time counts as such.
   *
   * @param row the row
   * @param cutoff the cutoff
   * @return {@code true} when the row was created before the cutoff or has no creation time
   */
  private static boolean fromBefore(@NotNull InventoryItemDto row, @NotNull Instant cutoff) {
    return row.createdAt() == null || row.createdAt().isBefore(cutoff);
  }

  /**
   * Reads a row's holder id.
   *
   * @param row the row
   * @return the holder's id, or {@code null} for a row without one
   */
  private static @Nullable UUID holderId(@NotNull InventoryItemDto row) {
    return row.user() == null ? null : row.user().id();
  }
}
