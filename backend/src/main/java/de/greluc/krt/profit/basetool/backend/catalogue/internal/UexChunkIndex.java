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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

/** Transaction-local identity and name lookups for one UEX write chunk. */
@RequiredArgsConstructor
public final class UexChunkIndex<T> {

  private final Function<T, Integer> externalId;
  private final Function<T, String> name;
  private final boolean unclaimedNameOnly;
  private final Map<Integer, T> byId = new HashMap<>();
  private final Map<String, Deque<T>> byName = new HashMap<>();

  /**
   * Indexes the rows selected for a chunk, preserving repository ordering for duplicate names.
   *
   * @param rows the existing rows
   * @param externalId reads their UEX identifier
   * @param name reads their display name
   * @param unclaimedNameOnly whether name fallback may adopt only an unclaimed row
   * @param <T> the catalogue row type
   * @return the transaction-local index
   */
  static <T> @NotNull UexChunkIndex<T> of(
      @NotNull List<T> rows,
      @NotNull Function<T, Integer> externalId,
      @NotNull Function<T, String> name,
      boolean unclaimedNameOnly) {
    UexChunkIndex<T> index = new UexChunkIndex<>(externalId, name, unclaimedNameOnly);
    for (T row : rows) {
      Integer id = externalId.apply(row);
      if (id != null) {
        index.byId.put(id, row);
      }
      if (!unclaimedNameOnly || id == null) {
        index.byName.computeIfAbsent(name.apply(row), _ -> new ArrayDeque<>()).addLast(row);
      }
    }
    return index;
  }

  /**
   * Resolves by UEX identity, then by the permitted name fallback, then constructs a row.
   *
   * @param id the incoming UEX identity
   * @param incomingName the incoming display name
   * @param create constructs an unpersisted row
   * @return the row to update in this chunk
   */
  @NotNull
  T resolve(@NotNull Integer id, String incomingName, @NotNull Supplier<T> create) {
    T row = byId.get(id);
    if (row == null) {
      Deque<T> candidates = byName.get(incomingName);
      if (candidates != null) {
        row = candidates.peekFirst();
      }
    }
    if (row == null) {
      row = create.get();
    }
    Integer previousId = externalId.apply(row);
    if (previousId != null && !previousId.equals(id)) {
      byId.remove(previousId, row);
    }
    forgetName(name.apply(row), row);
    byId.put(id, row);
    if (!unclaimedNameOnly) {
      byName.computeIfAbsent(incomingName, _ -> new ArrayDeque<>()).addFirst(row);
    }
    return row;
  }

  private void forgetName(String previousName, T row) {
    Deque<T> candidates = byName.get(previousName);
    if (candidates != null) {
      candidates.removeFirstOccurrence(row);
      if (candidates.isEmpty()) {
        byName.remove(previousName);
      }
    }
  }
}
