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

package de.greluc.krt.profit.basetool.backend.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToIntFunction;

/**
 * Clears the price rows an upstream feed no longer returns, in batches of at most {@link
 * #CHUNK_SIZE} ids so the bind-parameter count is independent of the feed size (REQ-DATA-014).
 */
public final class StalePriceSweep {

  /**
   * Rows cleared per statement. Small enough that the bind-parameter count stays two orders of
   * magnitude below PostgreSQL's 65 535 ceiling, large enough that a steady-state sweep (which
   * clears a handful of rows, if any) is a single round trip.
   */
  public static final int CHUNK_SIZE = 1000;

  private StalePriceSweep() {}

  /**
   * Clears every id in {@code pricedIds} that is not in {@code seenIds}, one chunk per call to
   * {@code clearChunk}.
   *
   * <p>The caller is responsible for the non-empty-{@code seenIds} gate: this method does not know
   * whether an empty seen-set means "the feed returned nothing" (a total-failure burst, where
   * clearing everything would be the wrong answer) or a legitimately empty feed.
   *
   * @param pricedIds ids of the rows that currently hold a price — the sweep candidates, typically
   *     the repository's {@code findIdsWithLivePrices()}
   * @param seenIds ids the current run upserted; a {@link Set} so the subtraction stays linear
   * @param clearChunk clears one chunk of ids and returns how many rows it changed, typically the
   *     repository's {@code clearPricesByIds}
   * @return the total number of rows cleared across all chunks
   */
  public static int clearStale(
      Collection<UUID> pricedIds, Set<UUID> seenIds, ToIntFunction<List<UUID>> clearChunk) {
    List<UUID> stale = new ArrayList<>(pricedIds);
    stale.removeAll(seenIds);
    int cleared = 0;
    for (int from = 0; from < stale.size(); from += CHUNK_SIZE) {
      int to = Math.min(from + CHUNK_SIZE, stale.size());
      cleared += clearChunk.applyAsInt(stale.subList(from, to));
    }
    return cleared;
  }
}
