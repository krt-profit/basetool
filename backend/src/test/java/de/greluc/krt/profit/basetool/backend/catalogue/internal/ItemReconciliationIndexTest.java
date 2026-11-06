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

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiItemDto;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Exercises both source cardinalities independently of database traversal and apply order. */
class ItemReconciliationIndexTest {

  @Test
  void aUniqueSourceNameSurvivesCanonicalRenameAndIsConsumedOnlyAfterSuccess() {
    GameItem uex = uex("Source Name", "source-slug");
    uex.setName("Canonical Rename");
    ScWikiItemDto wiki = wiki("Source Name", "source-slug");
    var index = new ItemReconciliationIndex(List.of(uex), List.of(uex));
    index.indexWikiCandidates(List.of(wiki), true, List.of(List.of(wiki)));

    assertThat(index.match(wiki).candidateId()).isEqualTo(uex.getId());
    assertThat(index.match(wiki).ambiguity()).isNull();
    assertThat(index.match(wiki).candidateId()).isEqualTo(uex.getId());
    index.markConsumed(uex.getId());
    assertThat(index.match(wiki).candidateId()).isNull();
    assertThat(index.match(wiki).ambiguity()).isNull();
  }

  @Test
  void aLinkedUexVariantStillMakesTheNameAmbiguous() {
    GameItem eligible = uex("Variant", "variant-a");
    GameItem linked = uex("Variant", "variant-b");
    linked.setExternalUuid(UUID.randomUUID());
    ScWikiItemDto wiki = wiki("Variant", "variant-a");
    var index = new ItemReconciliationIndex(List.of(eligible), List.of(eligible, linked));
    index.indexWikiCandidates(List.of(wiki), true, List.of(List.of(wiki)));

    assertThat(index.match(wiki).candidateId()).isNull();
    assertThat(index.match(wiki).ambiguity()).isEqualTo(ItemReconciliationIndex.Ambiguity.UEX_NAME);
  }

  @Test
  void wikiNamesSharedAcrossDifferentKindPassesNeverClaimTheSameUexIdentity() {
    GameItem uex = uex("Shared", "weapon");
    ScWikiItemDto weapon = wiki("Shared", "weapon");
    ScWikiItemDto armor = wiki("Shared", "armor");
    var index = new ItemReconciliationIndex(List.of(uex), List.of(uex));
    index.indexWikiCandidates(
        List.of(weapon, armor),
        true,
        List.of(List.of(weapon), List.of(armor), List.of(weapon, armor)));

    assertThat(index.match(weapon).candidateId()).isNull();
    assertThat(index.match(armor).candidateId()).isNull();
    assertThat(index.match(weapon).ambiguity())
        .isEqualTo(ItemReconciliationIndex.Ambiguity.WIKI_NAME);
    assertThat(index.match(armor).ambiguity())
        .isEqualTo(ItemReconciliationIndex.Ambiguity.WIKI_NAME);
  }

  @Test
  void anIncompleteOrNonSupersetResidualNeverVouchesForMatching() {
    GameItem uex = uex("Known", "known");
    ScWikiItemDto known = wiki("Known", "known");
    ScWikiItemDto extra = wiki("Other", "other");
    var incomplete = new ItemReconciliationIndex(List.of(uex), List.of(uex));
    incomplete.indexWikiCandidates(List.of(known), false, List.of(List.of(known)));
    var nonSuperset = new ItemReconciliationIndex(List.of(uex), List.of(uex));
    nonSuperset.indexWikiCandidates(List.of(known), true, List.of(List.of(known), List.of(extra)));

    assertThat(incomplete.match(known).candidateId()).isNull();
    assertThat(nonSuperset.match(known).candidateId()).isNull();
    assertThat(incomplete.match(known).ambiguity()).isNull();
    assertThat(nonSuperset.match(known).ambiguity()).isNull();
  }

  @Test
  void aUniqueSlugPointingAtAnotherUexRowContradictsTheNameMatch() {
    GameItem byName = uex("Expected", "first");
    GameItem bySlug = uex("Other", "second");
    ScWikiItemDto wiki = wiki("Expected", "second");
    var index = new ItemReconciliationIndex(List.of(byName, bySlug), List.of(byName, bySlug));
    index.indexWikiCandidates(List.of(wiki), true, List.of(List.of(wiki)));

    assertThat(index.match(wiki).candidateId()).isNull();
    assertThat(index.match(wiki).ambiguity())
        .isEqualTo(ItemReconciliationIndex.Ambiguity.CONFLICTING_SLUG);
  }

  @Test
  void overlappingPassesOfTheSameGuidDoNotManufactureAmbiguity() {
    GameItem uex = uex("Overlap", "overlap");
    ScWikiItemDto wiki = wiki("Overlap", "overlap");
    var index = new ItemReconciliationIndex(List.of(uex), List.of(uex));
    index.indexWikiCandidates(List.of(wiki), true, List.of(List.of(wiki), List.of(wiki)));

    assertThat(index.match(wiki).candidateId()).isEqualTo(uex.getId());
    assertThat(index.match(wiki).ambiguity()).isNull();
  }

  @Test
  void repeatedWikiNamesWithoutAnEligibleUexCandidateAreNotReconciliationConflicts() {
    ScWikiItemDto first = wiki("Shared", "first");
    ScWikiItemDto second = wiki("Shared", "second");
    var index = new ItemReconciliationIndex(List.of(), List.of());
    index.indexWikiCandidates(List.of(first, second), true, List.of(List.of(first, second)));

    assertThat(index.match(first).candidateId()).isNull();
    assertThat(index.match(first).ambiguity()).isNull();
    assertThat(index.match(second).ambiguity()).isNull();
  }

  private static GameItem uex(String sourceName, String slug) {
    GameItem row = new GameItem();
    row.setId(UUID.randomUUID());
    row.setName(sourceName);
    row.setUexName(sourceName);
    row.setUexSlug(slug);
    return row;
  }

  private static ScWikiItemDto wiki(String name, String slug) {
    return JsonMapper.builder()
        .build()
        .readValue(
            "{\"uuid\":\""
                + UUID.randomUUID()
                + "\",\"name\":\""
                + name
                + "\",\"slug\":\""
                + slug
                + "\"}",
            ScWikiItemDto.class);
  }
}
