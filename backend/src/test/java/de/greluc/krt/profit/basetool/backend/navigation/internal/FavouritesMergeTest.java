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

package de.greluc.krt.profit.basetool.backend.navigation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests of the favourites rule of an account merge. */
class FavouritesMergeTest {

  @Test
  @DisplayName("the target's favourites come first, the source's follow without duplicates")
  void targetFirstThenSourceDeduplicated() {
    assertThat(
            FavouritesMerge.union(List.of("a.one", "b.two"), List.of("b.two", "c.three", "a.one")))
        .containsExactly("a.one", "b.two", "c.three");
  }

  @Test
  @DisplayName("the union is cut at the cap, dropping the source's surplus")
  void cutAtTheCap() {
    List<String> target = IntStream.range(0, 8).mapToObj(i -> "t.k" + i).toList();
    List<String> source = IntStream.range(0, 8).mapToObj(i -> "s.k" + i).toList();

    List<String> merged = FavouritesMerge.union(target, source);

    assertThat(merged).hasSize(NavigationLimits.MAX_FAVOURITES);
    assertThat(merged).startsWith(target.toArray(String[]::new));
    assertThat(merged).endsWith("s.k3");
  }

  @Test
  @DisplayName("an empty side leaves the other side unchanged")
  void emptySide() {
    assertThat(FavouritesMerge.union(List.of(), List.of("a.one"))).containsExactly("a.one");
    assertThat(FavouritesMerge.union(List.of("a.one"), List.of())).containsExactly("a.one");
  }
}
