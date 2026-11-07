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
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** The first version-2 APPLY makes the P4K GUID the identity of every kind (REQ-P4K-007). */
@SpringBootTest
@Transactional
class P4kIdentityCleanupTest {

  @Autowired private P4kImportService service;
  @Autowired private P4kImportJobRepository jobRepository;
  @Autowired private GameItemRepository gameItemRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private P4kReviewCaseRepository reviewCaseRepository;
  @Autowired private P4kImportChangeRepository changeRepository;

  private P4kImportRuns runs;

  @BeforeEach
  void setUp() {
    runs = new P4kImportRuns(service, jobRepository);
  }

  private GameItem item(String name, String className) {
    GameItem item = new GameItem();
    item.setName(name);
    item.setClassName(className);
    return gameItemRepository.save(item);
  }

  private boolean hasConflict(P4kKind kind, UUID guid) {
    return reviewCaseRepository
        .findByKindAndGuidAndReason(kind, guid, P4kReviewReason.GUID_CONFLICT)
        .isPresent();
  }

  @Test
  void aRowMatchedByClassName_takesTheP4kGuid_keepsItsWikiUuid_andReportsTheMismatch() {
    UUID wikiUuid = UUID.randomUUID();
    GameItem shield = item("Identity shield", P4kTestCatalogues.SHIELD_CLASS);
    shield.setExternalUuid(wikiUuid);

    P4kImportResultDto result = runs.apply(P4kTestCatalogues.fixtureBytes(), false);

    assertThat(shield.getP4kUuid()).isEqualTo(P4kTestCatalogues.SHIELD);
    assertThat(shield.getExternalUuid()).isEqualTo(wikiUuid);
    assertThat(result.items().identityMismatches()).isEqualTo(1);
    assertThat(P4kImportRuns.report(changeRepository, result.runId()))
        .filteredOn(c -> c.getChangeType() == P4kChangeType.IDENTITY_MISMATCH)
        .singleElement()
        .satisfies(c -> {
          assertThat(c.getFieldName()).isEqualTo("external_uuid");
          assertThat(c.getOldValue()).isEqualTo(wikiUuid.toString());
          assertThat(c.getNewValue()).isEqualTo(P4kTestCatalogues.SHIELD.toString());
        });
  }

  @Test
  void twoRowsClaimingOneGuid_openAReviewCase_andKeepTheirValues() {
    GameItem first = item("Cooler one", "COOL_JUST_S02_Snowpack_SCItem");
    GameItem second = item("Cooler two", "COOL_JUST_S02_Snowpack_SCItem");

    runs.apply(P4kTestCatalogues.fixtureBytes(), false);

    assertThat(hasConflict(P4kKind.ITEM, P4kTestCatalogues.COOLER)).isTrue();
    assertThat(first.getP4kUuid()).isNull();
    assertThat(second.getP4kUuid()).isNull();
    assertThat(first.getP4kSeenRunId()).isNull();
  }

  @Test
  void aRowHoldingAnotherGuid_isNotReassigned() {
    UUID other = UUID.randomUUID();
    GameItem shield = item("Claimed shield", P4kTestCatalogues.SHIELD_CLASS);
    shield.setP4kUuid(other);

    runs.apply(P4kTestCatalogues.fixtureBytes(), false);

    assertThat(shield.getP4kUuid()).isEqualTo(other);
    assertThat(hasConflict(P4kKind.ITEM, P4kTestCatalogues.SHIELD)).isTrue();
  }

  @Test
  void aUniqueClassName_matches_althoughOtherRecordsShareItAsTheirName() {
    GameItem shield = item("Class-name shield", P4kTestCatalogues.SHIELD_CLASS);
    ObjectNode root = P4kTestCatalogues.tree();
    ObjectNode original = P4kTestCatalogues.record(root, "items", P4kTestCatalogues.SHIELD);
    for (int i = 1; i <= 2; i++) {
      ObjectNode twin = original.deepCopy();
      twin.put("guid", "0f000000-0000-4000-8000-0000000000d" + i);
      twin.put("className", "Shield_Name_Twin_" + i);
      ((ObjectNode) twin.get("name")).put("en", P4kTestCatalogues.SHIELD_CLASS);
      ((ArrayNode) root.get("items")).add(twin);
    }

    runs.apply(P4kTestCatalogues.bytes(root), false);

    assertThat(shield.getP4kUuid()).isEqualTo(P4kTestCatalogues.SHIELD);
    assertThat(hasConflict(P4kKind.ITEM, P4kTestCatalogues.SHIELD)).isFalse();
  }

  @Test
  void aNameSeveralCommoditiesShare_matchesNone_andReviewsEach() {
    Material aslarite =
        materialRepository
            .findByNameIgnoreCase("Aslarite")
            .orElseGet(() -> {
              Material material = new Material();
              material.setName("Aslarite");
              material.setType(MaterialType.NO_REFINE);
              return materialRepository.save(material);
            });
    UUID before = aslarite.getP4kUuid();
    ObjectNode root = P4kTestCatalogues.tree();
    ObjectNode twin =
        P4kTestCatalogues.record(root, "commodities", P4kTestCatalogues.ASLARITE).deepCopy();
    UUID twinGuid = UUID.fromString("0f000000-0000-4000-8000-0000000000c1");
    twin.put("guid", twinGuid.toString());
    twin.put("className", "Aslarite_Twin");
    ((ArrayNode) root.get("commodities")).add(twin);

    runs.apply(P4kTestCatalogues.bytes(root), false);

    assertThat(aslarite.getP4kUuid()).isEqualTo(before);
    assertThat(hasConflict(P4kKind.COMMODITY, P4kTestCatalogues.ASLARITE)).isTrue();
    assertThat(hasConflict(P4kKind.COMMODITY, twinGuid)).isTrue();
  }
}
