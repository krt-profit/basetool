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

package de.greluc.krt.profit.basetool.frontend.navigation.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests of {@link NavigationOrdering}: the stored order and the placement of unknown keys. */
class NavigationOrderingTest {

  private static final List<String> DEFAULTS = List.of("a", "b", "c", "d");

  @Test
  @DisplayName("without a stored order the defaults keep their order")
  void keepsTheDefaults() {
    assertThat(NavigationOrdering.merge(DEFAULTS, List.of())).containsExactlyElementsOf(DEFAULTS);
  }

  @Test
  @DisplayName("a complete stored order wins")
  void appliesTheStoredOrder() {
    assertThat(NavigationOrdering.merge(DEFAULTS, List.of("d", "c", "b", "a")))
        .containsExactly("d", "c", "b", "a");
  }

  @Test
  @DisplayName("a key the store does not know follows its default predecessor")
  void placesAnUnknownKeyAfterItsPredecessor() {
    assertThat(NavigationOrdering.merge(DEFAULTS, List.of("c", "a", "d")))
        .containsExactly("c", "a", "b", "d");
  }

  @Test
  @DisplayName("an unknown first key is placed first")
  void placesAnUnknownFirstKeyFirst() {
    assertThat(NavigationOrdering.merge(DEFAULTS, List.of("c", "b", "d")))
        .containsExactly("a", "c", "b", "d");
  }

  @Test
  @DisplayName("several unknown keys in a row keep their default order")
  void keepsUnknownKeysInDefaultOrder() {
    assertThat(NavigationOrdering.merge(DEFAULTS, List.of("d", "a")))
        .containsExactly("d", "a", "b", "c");
  }

  @Test
  @DisplayName("stored keys the defaults no longer hold, and repeats, are ignored")
  void ignoresStoredKeysThatAreNotDefaults() {
    assertThat(NavigationOrdering.merge(DEFAULTS, List.of("gone", "b", "b", "a")))
        .containsExactly("b", "c", "d", "a");
  }

  @Test
  @DisplayName("positions count only the permitted defaults")
  void countsOnlyThePermittedKeys() {
    assertThat(NavigationOrdering.merge(List.of("a", "c"), List.of("c", "b", "a")))
        .containsExactly("c", "a");
  }
}
