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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StalePriceSweep}.
 *
 * <p>The sweep replaced a single {@code WHERE id NOT IN :seenIds} bulk update whose bind-parameter
 * count scaled with the feed — 23 770 rows today, padded to 32 768, against PostgreSQL's 65 535
 * ceiling (REQ-DATA-014). These tests pin the two properties that make the replacement safe: the
 * same rows are cleared as before, and the per-statement parameter count stays bounded however
 * large the matrix grows.
 */
class StalePriceSweepTest {

  @Test
  @DisplayName("clears exactly the priced rows the run did not see")
  void clearsTheComplementOfTheSeenSet() {
    UUID seen = UUID.randomUUID();
    UUID stale = UUID.randomUUID();
    UUID alsoStale = UUID.randomUUID();
    List<List<UUID>> chunks = new ArrayList<>();

    int cleared =
        StalePriceSweep.clearStale(
            List.of(seen, stale, alsoStale),
            Set.of(seen),
            chunk -> {
              chunks.add(List.copyOf(chunk));
              return chunk.size();
            });

    assertEquals(2, cleared);
    assertEquals(List.of(List.of(stale, alsoStale)), chunks, "the seen row must be spared");
  }

  @Test
  @DisplayName("a run that saw every priced row clears nothing and issues no statement")
  void steadyStateIssuesNoStatement() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    List<List<UUID>> chunks = new ArrayList<>();

    int cleared =
        StalePriceSweep.clearStale(
            List.of(a, b),
            Set.of(a, b),
            chunk -> {
              chunks.add(List.copyOf(chunk));
              return chunk.size();
            });

    assertEquals(0, cleared);
    assertTrue(chunks.isEmpty(), "nothing stale must mean no round trip at all");
  }

  @Test
  @DisplayName("the per-statement id count stays bounded no matter how large the matrix is")
  void splitsIntoBoundedChunks() {
    // The whole point: 2.5 chunks' worth of stale rows must never become one statement with 2 500
    // bind parameters, because that is the shape that ends at the 65 535 protocol limit.
    int staleCount = StalePriceSweep.CHUNK_SIZE * 2 + 500;
    Set<UUID> priced = new LinkedHashSet<>();
    for (int i = 0; i < staleCount; i++) {
      priced.add(UUID.randomUUID());
    }
    List<Integer> chunkSizes = new ArrayList<>();

    int cleared =
        StalePriceSweep.clearStale(
            priced,
            Set.of(),
            chunk -> {
              chunkSizes.add(chunk.size());
              return chunk.size();
            });

    assertEquals(staleCount, cleared, "every stale row is still cleared");
    assertEquals(
        List.of(StalePriceSweep.CHUNK_SIZE, StalePriceSweep.CHUNK_SIZE, 500),
        chunkSizes,
        "the ids must arrive in bounded batches, not as one statement");
  }
}
