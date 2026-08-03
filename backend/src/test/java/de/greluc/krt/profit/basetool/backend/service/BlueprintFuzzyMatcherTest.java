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

package de.greluc.krt.profit.basetool.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintImportSuggestionDto;
import de.greluc.krt.profit.basetool.backend.service.BlueprintProductService.ResolvedProduct;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link BlueprintFuzzyMatcher}. */
class BlueprintFuzzyMatcherTest {

  private final BlueprintFuzzyMatcher matcher = new BlueprintFuzzyMatcher();

  private static ResolvedProduct product(String key, String name) {
    return new ResolvedProduct(key, name, null);
  }

  @Test
  void topSuggestions_ranksClosestKeyFirst() {
    List<ResolvedProduct> candidates =
        List.of(
            product("calico legs tactical", "Calico Legs Tactical"),
            product("calico legs", "Calico Legs"),
            product("arclight pistol", "Arclight Pistol"));

    // Query is a one-character typo of the first candidate.
    List<BlueprintImportSuggestionDto> out =
        matcher.topSuggestions("calico legs tacticl", candidates, 5, 0.5);

    assertFalse(out.isEmpty());
    assertEquals("calico legs tactical", out.get(0).productKey());
    // Scores are sorted descending.
    for (int i = 1; i < out.size(); i++) {
      assertTrue(out.get(i - 1).score() >= out.get(i).score());
    }
  }

  @Test
  void topSuggestions_tokenReorderMatchesViaJaccard() {
    List<ResolvedProduct> candidates =
        List.of(product("tactical calico legs", "Tactical Calico Legs"));

    List<BlueprintImportSuggestionDto> out =
        matcher.topSuggestions("calico legs tactical", candidates, 5, 0.5);

    // Same word set, different order — Jaccard = 1.0 carries it despite high edit distance.
    assertEquals(1, out.size());
    assertEquals(1.0, out.get(0).score());
  }

  @Test
  void topSuggestions_stillCatchesAGermanCapacitySuffixTheV228SeedDidNotCover() {
    // The safety net the V228 seed's documented known-limit relies on (#1485, REQ-INV-021): an ammo
    // item added to the catalogue AFTER the one-shot seed gets no alias, so a German client's
    // "(N Schuss)" spelling falls through to the fuzzy matcher. It must still put the right product
    // at rank 1 — otherwise the seed's staleness would degrade into an unmatched row rather than
    // into one manual confirmation.
    List<ResolvedProduct> candidates =
        List.of(
            product("s71 rifle magazine (30 cap)", "S71 Rifle Magazine (30 cap)"),
            product("s71 rifle", "S71 Rifle"),
            product("arclight pistol battery (30 cap)", "Arclight Pistol Battery (30 cap)"));

    List<BlueprintImportSuggestionDto> out =
        matcher.topSuggestions("s71 rifle magazine (30 schuss)", candidates, 5, 0.5);

    assertFalse(out.isEmpty());
    assertEquals("s71 rifle magazine (30 cap)", out.get(0).productKey());
    // Comfortably above the 0.5 threshold, so the suggestion is offered rather than dropped.
    assertTrue(out.get(0).score() > 0.7);
  }

  @Test
  void topSuggestions_dropsCandidatesBelowThreshold() {
    List<ResolvedProduct> candidates = List.of(product("arclight pistol", "Arclight Pistol"));

    List<BlueprintImportSuggestionDto> out =
        matcher.topSuggestions("medical gown", candidates, 5, 0.5);

    assertTrue(out.isEmpty());
  }

  @Test
  void topSuggestions_capsAtLimit() {
    List<ResolvedProduct> candidates =
        List.of(
            product("calico legs a", "Calico Legs A"),
            product("calico legs b", "Calico Legs B"),
            product("calico legs c", "Calico Legs C"),
            product("calico legs d", "Calico Legs D"));

    List<BlueprintImportSuggestionDto> out =
        matcher.topSuggestions("calico legs", candidates, 2, 0.5);

    assertEquals(2, out.size());
  }

  @Test
  void topSuggestions_emptyQueryOrNonPositiveLimitYieldsEmpty() {
    List<ResolvedProduct> candidates = List.of(product("calico legs", "Calico Legs"));

    assertTrue(matcher.topSuggestions("", candidates, 5, 0.5).isEmpty());
    assertTrue(matcher.topSuggestions("calico legs", candidates, 0, 0.5).isEmpty());
  }
}
