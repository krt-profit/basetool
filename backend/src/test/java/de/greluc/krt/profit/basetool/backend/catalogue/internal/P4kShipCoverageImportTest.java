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

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemSourceSystem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** A ship a stored ship type already stands for needs neither a row nor a case (REQ-P4K-050). */
@SpringBootTest
@Transactional
class P4kShipCoverageImportTest {

  private static final UUID HORNET_BASE = UUID.fromString("fb231ba1-dba1-4c9f-823a-746c772ae92f");
  private static final String HORNET_NAME = "Anvil F7C Hornet Mk II";

  @Autowired private P4kImportService imports;
  @Autowired private P4kImportJobRepository jobs;
  @Autowired private P4kReviewCaseRepository cases;
  @Autowired private P4kImportChangeRepository changes;
  @Autowired private ShipTypeRepository ships;

  @Test
  void aShipNoPlayerOwnsIsCoveredByTheShipTypeCarryingItsName() {
    ShipType hornet = ships.saveAndFlush(ship(HORNET_NAME, null));
    P4kImportRuns runs = new P4kImportRuns(imports, jobs);

    UUID runId = runs.apply(P4kTestCatalogues.fixtureBytes(), false).runId();

    assertThat(hornet.getP4kUuid()).isNull();
    assertThat(openCase(P4kTestCatalogues.AI_SHIP)).isFalse();
    assertThat(P4kImportRuns.report(changes, runId))
        .anyMatch(change -> isCovered(change, P4kTestCatalogues.AI_SHIP));
  }

  @Test
  void aPlayerVariantOfAStoredBaseIsCoveredByIt() {
    ships.saveAndFlush(ship(HORNET_NAME, HORNET_BASE));
    P4kImportRuns runs = new P4kImportRuns(imports, jobs);

    UUID runId = runs.apply(P4kTestCatalogues.bytes(hornetGroup()), false).runId();

    assertThat(openCase(P4kTestCatalogues.AI_SHIP)).isFalse();
    assertThat(ships.findByP4kUuid(P4kTestCatalogues.AI_SHIP)).isEmpty();
    assertThat(P4kImportRuns.report(changes, runId))
        .anyMatch(change -> isCovered(change, P4kTestCatalogues.AI_SHIP));
  }

  @Test
  void theBaseOfAGroupIsNotCoveredByAShipTypeHoldingOneOfItsVariants() {
    ships.saveAndFlush(ship(HORNET_NAME, P4kTestCatalogues.AI_SHIP));
    P4kImportRuns runs = new P4kImportRuns(imports, jobs);

    UUID runId = runs.apply(P4kTestCatalogues.bytes(hornetGroup()), false).runId();

    assertThat(openCase(HORNET_BASE)).isTrue();
    assertThat(P4kImportRuns.report(changes, runId))
        .noneMatch(change -> isCovered(change, HORNET_BASE));
  }

  @Test
  void anApplyClosesTheShipCasesItNoLongerFinds() {
    P4kImportRuns runs = new P4kImportRuns(imports, jobs);
    UUID firstRun = runs.apply(P4kTestCatalogues.fixtureBytes(), false).runId();
    UUID gone = UUID.fromString("ab20545b-6dfd-4508-b35c-ff2500b1c0ff");
    P4kReviewCase stale = cases.saveAndFlush(guidConflict(P4kTestCatalogues.PLAYER_SHIP, firstRun));
    P4kReviewCase vanished = cases.saveAndFlush(guidConflict(gone, firstRun));

    runs.apply(P4kTestCatalogues.fixtureBytes(), false);

    for (P4kReviewCase closed : List.of(stale, vanished)) {
      assertThat(closed.getStatus()).isEqualTo(P4kReviewStatus.REVIEWED);
      assertThat(closed.getResolution()).isEqualTo(P4kReviewResolution.RESOLVED);
    }
  }

  private boolean openCase(UUID guid) {
    return cases
        .findByKindAndGuidAndReason(P4kKind.SHIP, guid, P4kReviewReason.GUID_CONFLICT)
        .filter(review -> review.getStatus() == P4kReviewStatus.OPEN)
        .isPresent();
  }

  private static boolean isCovered(P4kImportChange change, UUID guid) {
    return change.getChangeType() == P4kChangeType.COVERED
        && change.getKind() == P4kKind.SHIP
        && guid.equals(change.getGuid());
  }

  /**
   * Builds the fixture with the player base of the AI Hornet's group, of which the AI Hornet is
   * then a player variant of the same name.
   */
  private static ObjectNode hornetGroup() {
    ObjectNode root = P4kTestCatalogues.tree();
    ObjectNode variant = P4kTestCatalogues.record(root, "ships", P4kTestCatalogues.AI_SHIP);
    variant.put("playability", "player");
    ObjectNode base = variant.deepCopy();
    base.put("guid", HORNET_BASE.toString());
    base.put("className", "ANVL_Hornet_F7C_Mk2");
    base.put("recordPath", "libs/foundry/records/entities/spaceships/anvl_hornet_f7c_mk2");
    base.putNull("variantOf");
    ((ArrayNode) root.get("ships")).add(base);
    return root;
  }

  private static ShipType ship(String name, UUID p4kUuid) {
    ShipType ship = new ShipType();
    ship.setName(name);
    ship.setP4kUuid(p4kUuid);
    ship.setSourceSystems(GameItemSourceSystem.BOTH);
    return ship;
  }

  private static P4kReviewCase guidConflict(UUID guid, UUID runId) {
    P4kReviewCase review = new P4kReviewCase();
    review.setKind(P4kKind.SHIP);
    review.setGuid(guid);
    review.setReason(P4kReviewReason.GUID_CONFLICT);
    review.setFirstRunId(runId);
    review.setLastRunId(runId);
    return review;
  }
}
