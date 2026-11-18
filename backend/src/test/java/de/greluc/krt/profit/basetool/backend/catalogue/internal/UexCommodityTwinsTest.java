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

import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialSourceSystem;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The rule that finds a second UEX listing of a commodity (REQ-DATA-046), on the rows of the
 * production pair Party Favors (UEX 54, SC Wiki {@code PartyFavors}) and Fireworks (UEX 30, the P4K
 * GUID of {@code PartyFavors}) and on the duplicates the rule must leave alone.
 */
class UexCommodityTwinsTest {

  private static final UUID PARTY_FAVORS_TYPE =
      UUID.fromString("00f23f09-a8ae-4ccb-845f-accd63dd7906");
  private static final UUID PARTY_FAVORS_RECORD = UUID.randomUUID();
  private static final Set<Integer> LISTED = Set.of(30, 54, 137, 170, 214);

  @Test
  void partyFavorsMergesIntoFireworks() {
    Material fireworks = gameRow("Fireworks", 30, PARTY_FAVORS_RECORD, PARTY_FAVORS_TYPE);
    Material partyFavors = wikiRow("Party Favors", 54, PARTY_FAVORS_TYPE);

    assertThat(UexCommodityTwins.find(List.of(fireworks, partyFavors), LISTED))
        .containsExactly(
            new UexCommodityTwins.Twin(
                partyFavors.getId(), fireworks.getId(), MaterialMergeRule.UEX_WIKI_KEY));
  }

  @Test
  void aSeededRowWithoutAUexListingMergesNothing() {
    UUID type = UUID.randomUUID();
    Material seeded = gameRow("Lastaprene", null, UUID.randomUUID(), type);
    seeded.setSourceSystems(MaterialSourceSystem.P4K);
    Material wiki = wikiRow("Lastaphrene", 137, type);

    assertThat(UexCommodityTwins.find(List.of(seeded, wiki), LISTED)).isEmpty();
  }

  @Test
  void aMaterialHoldingTheGuidAndTheWikiKeyItselfHasNoTwin() {
    UUID type = UUID.randomUUID();
    Material carinite = gameRow("Carinite", 170, UUID.randomUUID(), type);
    carinite.setScwikiUuid(type);
    Material caranite = wikiRow("Caranite", 214, null);

    assertThat(UexCommodityTwins.find(List.of(carinite, caranite), LISTED)).isEmpty();
  }

  @Test
  void aGameRowWithAWikiUuidOfItsOwnMergesNothing() {
    Material fireworks = gameRow("Fireworks", 30, PARTY_FAVORS_RECORD, PARTY_FAVORS_TYPE);
    fireworks.setScwikiUuid(UUID.randomUUID());
    Material partyFavors = wikiRow("Party Favors", 54, PARTY_FAVORS_TYPE);

    assertThat(UexCommodityTwins.find(List.of(fireworks, partyFavors), LISTED)).isEmpty();
  }

  @Test
  void aListingTheFetchLacksMergesNothing() {
    Material fireworks = gameRow("Fireworks", 30, PARTY_FAVORS_RECORD, PARTY_FAVORS_TYPE);
    Material partyFavors = wikiRow("Party Favors", 54, PARTY_FAVORS_TYPE);
    List<Material> rows = List.of(fireworks, partyFavors);

    assertThat(UexCommodityTwins.find(rows, Set.of(54))).isEmpty();
    assertThat(UexCommodityTwins.find(rows, Set.of(30))).isEmpty();
  }

  @Test
  void aResourceTypeTwoMaterialsHoldMergesNothing() {
    Material fireworks = gameRow("Fireworks", 30, PARTY_FAVORS_RECORD, PARTY_FAVORS_TYPE);
    Material packaging = gameRow("Fireworks Crate", 137, UUID.randomUUID(), PARTY_FAVORS_TYPE);
    Material partyFavors = wikiRow("Party Favors", 54, PARTY_FAVORS_TYPE);

    assertThat(UexCommodityTwins.find(List.of(fireworks, packaging, partyFavors), LISTED))
        .isEmpty();
  }

  @Test
  void aGameRowWhoseRecordLeftTheCatalogueMergesNothing() {
    Material fireworks = gameRow("Fireworks", 30, PARTY_FAVORS_RECORD, PARTY_FAVORS_TYPE);
    fireworks.setP4kDeletedAt(Instant.now());
    Material partyFavors = wikiRow("Party Favors", 54, PARTY_FAVORS_TYPE);

    assertThat(UexCommodityTwins.find(List.of(fireworks, partyFavors), LISTED)).isEmpty();
  }

  @Test
  void aTwinHoldingAP4kGuidMergesNothing() {
    Material fireworks = gameRow("Fireworks", 30, PARTY_FAVORS_RECORD, PARTY_FAVORS_TYPE);
    Material partyFavors = wikiRow("Party Favors", 54, PARTY_FAVORS_TYPE);
    partyFavors.setP4kUuid(UUID.randomUUID());

    assertThat(UexCommodityTwins.find(List.of(fireworks, partyFavors), LISTED)).isEmpty();
  }

  private static Material gameRow(
      String name, @Nullable Integer uexId, UUID p4kUuid, UUID resourceType) {
    Material row = row(name, uexId, MaterialSourceSystem.UEX_ONLY);
    row.setP4kUuid(p4kUuid);
    row.setP4kSubtypeUuid(resourceType);
    return row;
  }

  private static Material wikiRow(String name, int uexId, @Nullable UUID wikiUuid) {
    Material row = row(name, uexId, MaterialSourceSystem.BOTH);
    row.setScwikiUuid(wikiUuid);
    return row;
  }

  private static Material row(String name, @Nullable Integer uexId, MaterialSourceSystem source) {
    Material row = new Material();
    row.setId(UUID.randomUUID());
    row.setName(name);
    row.setIdCommodity(uexId);
    row.setSourceSystems(source);
    return row;
  }
}
