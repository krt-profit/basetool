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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** A link table's change is the set difference, and the removed links go in bounded statements. */
class P4kLinkDiffTest {

  @Test
  void theWantedAndTheStoredTargets_yieldOnlyTheLinksToAddAndToRemove() {
    P4kLinkDiff<String> diff = P4kLinkDiff.of(Set.of("a", "b", "c"), Set.of("b", "c", "d"));

    assertThat(diff.added()).containsExactly("a");
    assertThat(diff.removed()).containsExactly("d");
  }

  @Test
  void unchangedLinks_yieldNothingToWrite() {
    P4kLinkDiff<String> diff = P4kLinkDiff.of(Set.of("a"), Set.of("a"));

    assertThat(diff.added()).isEmpty();
    assertThat(diff.removed()).isEmpty();
  }

  @Test
  void removedLinks_areDeletedInChunksOfTheStatementBound() {
    List<Integer> links = IntStream.range(0, P4kLinkDiff.DELETE_CHUNK * 2 + 1).boxed().toList();
    List<Integer> sizes = new ArrayList<>();

    P4kLinkDiff.deleteInChunks(links, chunk -> sizes.add(chunk.size()));

    assertThat(sizes).containsExactly(P4kLinkDiff.DELETE_CHUNK, P4kLinkDiff.DELETE_CHUNK, 1);
  }

  @Test
  void noRemovedLink_issuesNoStatement() {
    List<Integer> sizes = new ArrayList<>();

    P4kLinkDiff.deleteInChunks(List.of(), chunk -> sizes.add(chunk.size()));

    assertThat(sizes).isEmpty();
  }
}
