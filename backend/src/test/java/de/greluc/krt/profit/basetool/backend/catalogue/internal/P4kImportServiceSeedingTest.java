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

import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemKind;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemSourceSystem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialSourceSystem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Items and ships are seeded only when playable, behind a per-run checkbox, and earlier seeded rows
 * are reviewed; a commodity is created only when a record of the run references it (REQ-P4K-003,
 * REQ-P4K-009, REQ-P4K-010, REQ-P4K-016, REQ-P4K-026); a blueprint is created when its produced
 * item exists or the run creates it (REQ-P4K-028).
 */
@SpringBootTest
@Transactional
class P4kImportServiceSeedingTest {

  /** A ship the test adds to the catalogue with {@code playability} {@code null}. */
  private static final UUID NULL_SHIP = UUID.fromString("0f000000-0000-4000-8000-000000000001");

  @Autowired private P4kImportService service;
  @Autowired private P4kImportJobRepository jobRepository;
  @Autowired private ShipTypeRepository shipTypeRepository;
  @Autowired private P4kReviewCaseRepository reviewCaseRepository;
  @Autowired private P4kImportChangeRepository changeRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private BlueprintRepository blueprintRepository;
  @Autowired private GameItemRepository gameItemRepository;

  private P4kImportRuns runs;

  @BeforeEach
  void setUp() {
    runs = new P4kImportRuns(service, jobRepository);
  }

  private static byte[] catalogueWithANullShip() {
    ObjectNode root = P4kTestCatalogues.tree();
    ObjectNode copy = P4kTestCatalogues.record(root, "ships", P4kTestCatalogues.AI_SHIP).deepCopy();
    copy.put("guid", NULL_SHIP.toString());
    copy.put("className", "TEST_Null_Playability_Ship");
    ((ObjectNode) copy.get("name")).put("en", "Null Playability Ship");
    copy.putNull("playability");
    ((ArrayNode) root.get("ships")).add(copy);
    return P4kTestCatalogues.bytes(root);
  }

  private boolean hasCase(UUID guid, P4kReviewReason reason) {
    return reviewCaseRepository.findByKindAndGuidAndReason(P4kKind.SHIP, guid, reason).isPresent();
  }

  @Test
  void withTheCheckbox_onlyAPlayerShipIsCreated_andUnclearOnesAreReviewed() {
    P4kImportResultDto result = runs.apply(catalogueWithANullShip(), true);

    assertThat(shipTypeRepository.findByP4kUuid(P4kTestCatalogues.PLAYER_SHIP))
        .hasValueSatisfying(ship -> {
          assertThat(ship.getSourceSystems()).isEqualTo(GameItemSourceSystem.P4K);
          assertThat(ship.getP4kSeenRunId()).isEqualTo(result.runId());
        });
    assertThat(shipTypeRepository.findByP4kUuid(P4kTestCatalogues.AI_SHIP)).isEmpty();
    assertThat(shipTypeRepository.findByP4kUuid(P4kTestCatalogues.MISSION_SHIP)).isEmpty();
    assertThat(shipTypeRepository.findByP4kUuid(P4kTestCatalogues.SPECIAL_SHIP)).isEmpty();
    assertThat(hasCase(P4kTestCatalogues.SPECIAL_SHIP, P4kReviewReason.UNCLEAR_PLAYABILITY))
        .isTrue();
    assertThat(hasCase(NULL_SHIP, P4kReviewReason.UNCLEAR_PLAYABILITY)).isTrue();
    assertThat(hasCase(P4kTestCatalogues.AI_SHIP, P4kReviewReason.UNCLEAR_PLAYABILITY)).isFalse();
    assertThat(result.ships().created()).isEqualTo(1);
    assertThat(P4kImportRuns.report(changeRepository, result.runId()))
        .filteredOn(c -> c.getChangeType() == P4kChangeType.ADDED)
        .extracting(P4kImportChange::getGuid)
        .contains(P4kTestCatalogues.PLAYER_SHIP);
  }

  @Test
  void withoutTheCheckbox_nothingIsCreated_butThePreviewShowsTheWouldBeCount() {
    P4kImportResultDto preview = runs.preview(catalogueWithANullShip());
    P4kImportResultDto applied = runs.apply(catalogueWithANullShip(), false);

    assertThat(preview.ships().created()).isEqualTo(1);
    assertThat(preview.dryRun()).isTrue();
    assertThat(applied.ships().created()).isZero();
    assertThat(shipTypeRepository.findByP4kUuid(P4kTestCatalogues.PLAYER_SHIP)).isEmpty();
    assertThat(hasCase(P4kTestCatalogues.SPECIAL_SHIP, P4kReviewReason.UNCLEAR_PLAYABILITY))
        .isFalse();
  }

  @Test
  void aPreviouslySeededAiShip_isNotStamped_andOpensOneCaseAcrossRuns() {
    ShipType seeded = new ShipType();
    seeded.setName("Seeded AI Hornet");
    seeded.setClassName(P4kTestCatalogues.AI_SHIP_CLASS);
    seeded.setSourceSystems(GameItemSourceSystem.P4K);
    shipTypeRepository.save(seeded);

    UUID first = runs.apply(P4kTestCatalogues.fixtureBytes(), false).runId();
    UUID second = runs.apply(P4kTestCatalogues.fixtureBytes(), false).runId();

    assertThat(seeded.getP4kUuid()).isNull();
    assertThat(seeded.getP4kSeenRunId()).isNull();
    P4kReviewCase reviewCase =
        reviewCaseRepository
            .findByKindAndGuidAndReason(
                P4kKind.SHIP, P4kTestCatalogues.AI_SHIP, P4kReviewReason.SEEDED_NOT_CREATABLE)
            .orElseThrow();
    assertThat(reviewCase.getRowId()).isEqualTo(seeded.getId());
    assertThat(reviewCase.getFirstRunId()).isEqualTo(first);
    assertThat(reviewCase.getLastRunId()).isEqualTo(second);
    for (UUID run : new UUID[] {first, second}) {
      assertThat(P4kImportRuns.report(changeRepository, run))
          .filteredOn(c -> c.getChangeType() == P4kChangeType.REVIEW)
          .extracting(P4kImportChange::getGuid)
          .contains(P4kTestCatalogues.AI_SHIP);
    }
  }

  @Test
  void anUnknownPlayability_readsAsNull_andIsCounted() {
    ObjectNode root = P4kTestCatalogues.tree();
    P4kTestCatalogues.record(root, "items", P4kTestCatalogues.SPECIAL_ITEM)
        .put("playability", "crew");

    P4kImportResultDto result = runs.apply(P4kTestCatalogues.bytes(root), true);

    assertThat(result.items().unknownValues()).isEqualTo(1);
    assertThat(P4kImportRuns.report(changeRepository, result.runId()))
        .filteredOn(c -> c.getChangeType() == P4kChangeType.UNKNOWN_VALUE)
        .singleElement()
        .satisfies(c -> {
          assertThat(c.getFieldName()).isEqualTo("playability");
          assertThat(c.getNewValue()).isEqualTo("crew");
        });
    assertThat(
            reviewCaseRepository.findByKindAndGuidAndReason(
                P4kKind.ITEM, P4kTestCatalogues.SPECIAL_ITEM, P4kReviewReason.UNCLEAR_PLAYABILITY))
        .isPresent();
  }

  @Test
  void anIngredientNamingAnUnknownResourceType_createsThatMaterialVisible_andNothingElse() {
    P4kImportResultDto result = runs.apply(P4kTestCatalogues.fixtureBytes(), false);

    Material aslarite = materialRepository.findByP4kUuid(P4kTestCatalogues.ASLARITE).orElseThrow();
    assertThat(aslarite.getName()).isEqualTo("Aslarite");
    assertThat(aslarite.getIsVisible()).isTrue();
    assertThat(aslarite.getSourceSystems()).isEqualTo(MaterialSourceSystem.P4K);
    assertThat(aslarite.getType()).isEqualTo(MaterialType.NO_REFINE);
    assertThat(aslarite.getP4kSubtypeUuid()).isEqualTo(P4kTestCatalogues.ASLARITE);
    assertThat(materialRepository.findByNameIgnoreCase("Borase")).isEmpty();
    assertThat(result.commodities().created()).isEqualTo(2);
  }

  @Test
  void aMaterialAnEarlierSeedingCreatedInvisible_turnsVisibleWhenAnIngredientReferencesIt() {
    Material seeded = new Material();
    seeded.setName("Aslarite");
    seeded.setType(MaterialType.NO_REFINE);
    seeded.setSourceSystems(MaterialSourceSystem.P4K);
    seeded.setIsVisible(false);
    materialRepository.save(seeded);

    runs.apply(P4kTestCatalogues.fixtureBytes(), false);

    assertThat(seeded.getP4kUuid()).isEqualTo(P4kTestCatalogues.ASLARITE);
    assertThat(seeded.getIsVisible()).isTrue();
  }

  /** The fixture's light-armour blueprint and the item it produces. */
  private static final UUID ARMS_BLUEPRINT =
      UUID.fromString("0037f308-adfc-4c4e-994f-cd477e895aab");

  private static final UUID ARMS_ITEM = UUID.fromString("de20695c-eb2f-4b68-be6f-9806cbd205a7");

  /** The fixture's heavy-armour blueprint. */
  private static final UUID HEAVY_BLUEPRINT =
      UUID.fromString("005d95db-96ca-45b7-9647-7e7537b8fac8");

  @Test
  void withTheCheckbox_aBlueprintOfAnExistingItemIsCreated_andOneOfAnUnknownItemIsUnmatched() {
    GameItem arms = new GameItem();
    arms.setName("TrueDef-Pro Arms Silver/Gold/Grey");
    arms.setKind(GameItemKind.GENERIC);
    arms.setP4kUuid(ARMS_ITEM);
    gameItemRepository.save(arms);
    ObjectNode root = P4kTestCatalogues.tree();
    P4kTestCatalogues.record(root, "blueprints", HEAVY_BLUEPRINT)
        .put("producedItemGuid", "0f000000-0000-4000-8000-0000000b1e00");

    P4kImportResultDto preview = runs.preview(P4kTestCatalogues.bytes(root));
    P4kImportResultDto result = runs.apply(P4kTestCatalogues.bytes(root), true);

    assertThat(blueprintRepository.findByP4kUuid(ARMS_BLUEPRINT))
        .hasValueSatisfying(blueprint -> {
          assertThat(blueprint.getScwikiUuid()).isNull();
          assertThat(blueprint.getOutputItem()).isEqualTo(arms);
          assertThat(blueprint.getOutputName()).isEqualTo(arms.getName());
          assertThat(blueprint.getProducedItemGuid()).isEqualTo(ARMS_ITEM);
          assertThat(blueprint.getRequirementGroups()).hasSize(2);
        });
    assertThat(blueprintRepository.findByP4kUuid(HEAVY_BLUEPRINT)).isEmpty();
    assertThat(preview.blueprints().created()).isEqualTo(1);
    assertThat(result.blueprints().created()).isEqualTo(1);
    assertThat(P4kImportRuns.report(changeRepository, result.runId()))
        .filteredOn(c -> c.getChangeType() == P4kChangeType.UNMATCHED)
        .extracting(P4kImportChange::getGuid)
        .contains(HEAVY_BLUEPRINT);
  }

  @Test
  void withTheCheckbox_aBlueprintOfAnItemTheRunCreatesIsCreated_andWithoutItNoBlueprintIs() {
    P4kImportResultDto without = runs.apply(P4kTestCatalogues.fixtureBytes(), false);

    assertThat(without.blueprints().created()).isZero();
    assertThat(blueprintRepository.findByP4kUuid(ARMS_BLUEPRINT)).isEmpty();

    P4kImportResultDto with = runs.apply(P4kTestCatalogues.fixtureBytes(), true);

    assertThat(with.blueprints().created()).isEqualTo(2);
    assertThat(blueprintRepository.findByP4kUuid(ARMS_BLUEPRINT))
        .hasValueSatisfying(
            blueprint -> assertThat(blueprint.getOutputItem().getP4kUuid()).isEqualTo(ARMS_ITEM));
  }
}
