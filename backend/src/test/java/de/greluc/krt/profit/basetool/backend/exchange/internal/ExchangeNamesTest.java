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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

/**
 * The cut of a display name to the length the published v1 schemas allow, and what a cut name sent
 * back stands for (REQ-XCH-026).
 */
class ExchangeNamesTest {

  /** A character outside the Basic Multilingual Plane: two UTF-16 units, one code point. */
  private static final String ROCKET = Character.toString(0x1F680);

  @Test
  void aNameWithinTheLimitStaysAsItIs() {
    String name = "n".repeat(ExchangeNames.MAX_NAME);

    assertThat(ExchangeNames.cut(name)).isSameAs(name);
  }

  @Test
  void aLongerNameIsCutToTheLimit() {
    assertThat(ExchangeNames.cut("n".repeat(255))).isEqualTo("n".repeat(ExchangeNames.MAX_NAME));
  }

  @Test
  void theCutNeverSplitsASurrogatePair() {
    String prefix = "n".repeat(ExchangeNames.MAX_NAME - 1);

    assertThat(ExchangeNames.cut(prefix + ROCKET + "tail")).isEqualTo(prefix);
  }

  @Test
  void aSurrogatePairEndingAtTheLimitIsKept() {
    String kept = "n".repeat(ExchangeNames.MAX_NAME - 2) + ROCKET;

    assertThat(ExchangeNames.cut(kept + "tail")).isEqualTo(kept);
  }

  @Test
  void aCutNameStandsForEveryLongerNameThatCutsToItButNotForItself() {
    String sent = "n".repeat(ExchangeNames.MAX_NAME);
    List<String> stored = List.of(sent, sent + " first", sent + " second", "m" + sent);

    assertThat(ExchangeNames.cutTo(sent, startingWith(stored), Function.identity()))
        .containsExactly(sent + " first", sent + " second");
  }

  @Test
  void aCutNameEndingBeforeASurrogatePairStandsForTheNameItCameFrom() {
    String sent = "n".repeat(ExchangeNames.MAX_NAME - 1);
    String stored = sent + ROCKET + "tail";

    assertThat(ExchangeNames.cutTo(sent, startingWith(List.of(stored)), Function.identity()))
        .containsExactly(stored);
  }

  @Test
  void aNameNoCutProducesStandsForNothingAndReadsNothing() {
    List<String> asked = new ArrayList<>();
    Function<String, List<String>> reader =
        prefix -> {
          asked.add(prefix);
          return List.of(prefix + "x".repeat(ExchangeNames.MAX_NAME));
        };

    assertThat(ExchangeNames.cutTo("short", reader, Function.identity())).isEmpty();
    assertThat(asked).isEmpty();
  }

  /**
   * Reads the stored names that start with a prefix, as a repository's prefix query does.
   *
   * @param stored the stored names
   * @return the reader
   */
  private static @NotNull Function<String, List<String>> startingWith(
      @NotNull List<String> stored) {
    return prefix -> stored.stream().filter(name -> name.startsWith(prefix)).toList();
  }
}
