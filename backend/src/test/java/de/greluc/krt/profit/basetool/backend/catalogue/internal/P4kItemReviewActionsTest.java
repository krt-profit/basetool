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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemKind;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemSourceSystem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Manufacturer;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Checks source evidence before the review offers to attach or merge an item. */
class P4kItemReviewActionsTest {

  private static final String NAME = "Review optic";
  private static final String CLASS_NAME = "Review_Optic";

  private final GameItemRepository items = mock(GameItemRepository.class);
  private final P4kItemReviewActions actions =
      new P4kItemReviewActions(items, mock(ItemReconciliationService.class));

  @Test
  void knownDistinctManufacturersKeepTheUexPeerInspectableOnly() {
    GameItem wiki = wiki();
    GameItem uex = uex();
    wiki.setManufacturer(manufacturer());
    uex.setManufacturer(manufacturer());
    mockRows(wiki, uex);

    P4kReviewOptions options = actions.options(review(wiki), null);

    assertThat(options.readiness()).isEqualTo(P4kReviewReadiness.CHECK_REQUIRED);
    assertThat(options.candidates())
        .filteredOn(candidate -> candidate.targetId().equals(uex.getId()))
        .singleElement()
        .satisfies(candidate -> {
          assertThat(candidate.action()).isEqualTo(P4kReviewAction.INSPECT);
          assertThat(candidate.evidence()).contains(P4kReviewEvidence.DIFFERENT_MANUFACTURER);
        });
  }

  @Test
  void anotherKnownWikiVariantPreventsANameBasedMerge() {
    GameItem wiki = wiki();
    GameItem uex = uex();
    GameItem variant = wiki();
    variant.setClassName("Review_Optic_Variant");
    when(items.findByExternalUuid(wiki.getExternalUuid())).thenReturn(Optional.of(wiki));
    when(items.findById(wiki.getId())).thenReturn(Optional.of(wiki));
    when(items.findByNameIgnoreCase(NAME)).thenReturn(List.of(wiki, uex, variant));

    assertThat(actions.options(review(wiki), null).candidates())
        .extracting(P4kReviewCandidate::action)
        .containsOnly(P4kReviewAction.INSPECT);
  }

  @Test
  void uniquelyCompatibleUexPeerIsAnExplicitMergeOption() {
    GameItem wiki = wiki();
    GameItem uex = uex();
    Manufacturer manufacturer = manufacturer();
    wiki.setManufacturer(manufacturer);
    uex.setManufacturer(manufacturer);
    mockRows(wiki, uex);

    assertThat(actions.options(review(wiki), null).candidates())
        .filteredOn(candidate -> candidate.action() == P4kReviewAction.MERGE_ITEM)
        .singleElement()
        .satisfies(candidate -> assertThat(candidate.targetId()).isEqualTo(uex.getId()));
  }

  private void mockRows(GameItem wiki, GameItem uex) {
    when(items.findByExternalUuid(wiki.getExternalUuid())).thenReturn(Optional.of(wiki));
    when(items.findById(wiki.getId())).thenReturn(Optional.of(wiki));
    when(items.findByNameIgnoreCase(NAME)).thenReturn(List.of(wiki, uex));
  }

  private static GameItem wiki() {
    GameItem item = new GameItem();
    item.setId(UUID.randomUUID());
    item.setExternalUuid(UUID.randomUUID());
    item.setP4kUuid(item.getExternalUuid());
    item.setName(NAME);
    item.setClassName(CLASS_NAME);
    item.setScwikiSlug("review-optic");
    item.setKind(GameItemKind.GENERIC);
    item.setSourceSystems(GameItemSourceSystem.WIKI_ONLY);
    return item;
  }

  private static GameItem uex() {
    GameItem item = new GameItem();
    item.setId(UUID.randomUUID());
    item.setName(NAME);
    item.setKind(GameItemKind.GENERIC);
    item.setSourceSystems(GameItemSourceSystem.UEX_ONLY);
    item.setUexItemId(112);
    return item;
  }

  private static Manufacturer manufacturer() {
    Manufacturer manufacturer = new Manufacturer();
    manufacturer.setId(UUID.randomUUID());
    return manufacturer;
  }

  private static P4kReviewCaseDto review(GameItem wiki) {
    UUID run = UUID.randomUUID();
    return new P4kReviewCaseDto(
        UUID.randomUUID(),
        0,
        P4kKind.ITEM,
        wiki.getExternalUuid(),
        P4kReviewReason.GUID_CONFLICT,
        P4kReviewStatus.OPEN,
        CLASS_NAME,
        NAME,
        wiki.getId(),
        NAME,
        null,
        run,
        run,
        Instant.EPOCH,
        null,
        null);
  }
}
