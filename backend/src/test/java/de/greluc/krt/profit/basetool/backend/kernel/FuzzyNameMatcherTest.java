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

package de.greluc.krt.profit.basetool.backend.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.greluc.krt.profit.basetool.backend.kernel.FuzzyNameMatcher.Scored;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link FuzzyNameMatcher}. */
class FuzzyNameMatcherTest {

  private final FuzzyNameMatcher matcher = new FuzzyNameMatcher();

  private List<Scored<String>> top(String query, List<String> keys, int limit, double threshold) {
    return matcher.topMatches(
        query, keys, Function.identity(), Comparator.naturalOrder(), limit, threshold);
  }

  @Test
  void topMatches_ranksClosestKeyFirst() {
    List<Scored<String>> out =
        top(
            "calico legs tacticl",
            List.of("calico legs tactical", "calico legs", "arclight pistol"),
            5,
            0.5);

    assertFalse(out.isEmpty());
    assertEquals("calico legs tactical", out.get(0).candidate());
    for (int i = 1; i < out.size(); i++) {
      assertTrue(out.get(i - 1).score() >= out.get(i).score());
    }
  }

  @Test
  void topMatches_tokenReorderMatchesViaJaccard() {
    List<Scored<String>> out = top("calico legs tactical", List.of("tactical calico legs"), 5, 0.5);

    assertEquals(1, out.size());
    assertEquals(1.0, out.get(0).score());
  }

  @Test
  void topMatches_stillCatchesAGermanCapacitySuffixTheV228SeedDidNotCover() {
    List<Scored<String>> out =
        top(
            "s71 rifle magazine (30 schuss)",
            List.of("s71 rifle magazine (30 cap)", "s71 rifle", "arclight pistol battery (30 cap)"),
            5,
            0.5);

    assertFalse(out.isEmpty());
    assertEquals("s71 rifle magazine (30 cap)", out.get(0).candidate());
    assertTrue(out.get(0).score() > 0.7);
  }

  @Test
  void topMatches_dropsCandidatesBelowThreshold() {
    assertTrue(top("medical gown", List.of("arclight pistol"), 5, 0.5).isEmpty());
  }

  @Test
  void topMatches_capsAtLimit() {
    List<Scored<String>> out =
        top(
            "calico legs",
            List.of("calico legs a", "calico legs b", "calico legs c", "calico legs d"),
            2,
            0.5);

    assertEquals(2, out.size());
  }

  @Test
  void topMatches_breaksEqualScoresByTheTieBreak() {
    List<Scored<String>> out =
        top("calico legs", List.of("calico legs b", "calico legs a"), 5, 0.5);

    assertEquals(
        List.of("calico legs a", "calico legs b"), out.stream().map(Scored::candidate).toList());
  }

  @Test
  void topMatches_emptyQueryOrNonPositiveLimitYieldsEmpty() {
    List<String> keys = List.of("calico legs");

    assertTrue(top("", keys, 5, 0.5).isEmpty());
    assertTrue(top("calico legs", keys, 0, 0.5).isEmpty());
  }
}
