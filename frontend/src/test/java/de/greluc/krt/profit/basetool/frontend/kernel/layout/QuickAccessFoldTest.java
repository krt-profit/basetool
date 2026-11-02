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

package de.greluc.krt.profit.basetool.frontend.kernel.layout;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link QuickAccessFold}: it folds like the browser's {@code krtSearchFold}. */
class QuickAccessFoldTest {

  /** Case and diacritics do not matter. */
  @Test
  void dropsCaseAndDiacritics() {
    assertThat(QuickAccessFold.fold("\u00c9clair")).isEqualTo("eclair");
  }

  /** The sharp s folds to two s. */
  @Test
  void foldsTheSharpS() {
    assertThat(QuickAccessFold.fold("Gro\u00dfe")).isEqualTo("grosse");
  }

  /** A spelled-out umlaut collapses like the umlaut letter. */
  @Test
  void collapsesSpelledOutUmlauts() {
    assertThat(QuickAccessFold.fold("M\u00fcller")).isEqualTo(QuickAccessFold.fold("Mueller"));
    assertThat(QuickAccessFold.fold("Soeren")).isEqualTo(QuickAccessFold.fold("S\u00f6ren"));
  }

  /** Containment and prefix tests fold the candidate. */
  @Test
  void matchesAndStartsWithFoldTheCandidate() {
    assertThat(QuickAccessFold.matches("B\u00e4renst\u00e4rke", "renst")).isTrue();
    assertThat(QuickAccessFold.startsWith("B\u00e4renst\u00e4rke", "bare")).isTrue();
    assertThat(QuickAccessFold.startsWith("B\u00e4renst\u00e4rke", "renst")).isFalse();
  }
}
