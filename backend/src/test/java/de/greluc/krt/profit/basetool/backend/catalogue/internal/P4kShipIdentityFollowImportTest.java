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
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** A ship type's P4K identity follows the catalogue's base and successor (REQ-P4K-051). */
@SpringBootTest
@Transactional
class P4kShipIdentityFollowImportTest {

  private static final UUID BASE = UUID.fromString("ab20545b-6dfd-4508-b35c-ff2500b1d001");
  private static final UUID EDITION = UUID.fromString("ab20545b-6dfd-4508-b35c-ff2500b1d002");
  private static final UUID DEPARTED = UUID.fromString("ab20545b-6dfd-4508-b35c-ff2500b1d0ff");
  private static final UUID ITEMLESS_PAINT =
      UUID.fromString("ab20545b-6dfd-4508-b35c-ff2500b1d0aa");
  private static final String WIKI_TEXT_EN = "A Wiki text about the ship.";
  private static final String WIKI_TEXT_DE = "Ein Wiki-Text zum Schiff.";
  private static final String BASE_CLASS = "TEST_Follow_Base";
  private static final String NAME = "Follow Test Ship";

  @Autowired private P4kImportService imports;
  @Autowired private P4kImportJobRepository jobs;
  @Autowired private P4kImportChangeRepository changes;
  @Autowired private P4kReviewCaseRepository cases;
  @Autowired private ShipTypeRepository ships;

  @Test
  void aShipTypeStampedWithAnEditionMovesToItsBaseWithoutAReview() {
    ShipType ship = ships.saveAndFlush(ship(NAME, EDITION, "TEST_Follow_Edition"));
    P4kImportRuns runs = new P4kImportRuns(imports, jobs);

    UUID runId = runs.apply(P4kTestCatalogues.bytes(catalogue()), false).runId();

    assertThat(ship.getP4kUuid()).isEqualTo(BASE);
    assertThat(ship.getClassName()).isEqualTo(BASE_CLASS);
    assertThat(cases.findByKindAndGuidAndReason(P4kKind.SHIP, BASE, P4kReviewReason.GUID_CONFLICT))
        .isEmpty();
    assertThat(P4kImportRuns.report(changes, runId))
        .anyMatch(change -> movedTo(change, EDITION, BASE));
  }

  @Test
  void aShipTypeWhoseGuidLeftTheCatalogueFollowsTheRecordOfItsName() {
    ShipType ship = ships.saveAndFlush(ship(NAME, DEPARTED, "TEST_Follow_Old"));
    P4kImportRuns runs = new P4kImportRuns(imports, jobs);

    UUID runId = runs.apply(P4kTestCatalogues.bytes(catalogue()), false).runId();

    assertThat(ship.getP4kUuid()).isEqualTo(BASE);
    assertThat(ship.getP4kDeletedAt()).isNull();
    assertThat(P4kImportRuns.report(changes, runId))
        .anyMatch(change -> movedTo(change, DEPARTED, BASE));
  }

  @Test
  void aShipTypeWhoseGuidLeftFollowsItsSuccessorThoughAPaintOfItHasNoItem() {
    ShipType ship = ships.saveAndFlush(ship(NAME, DEPARTED, "TEST_Follow_Old"));
    P4kImportRuns runs = new P4kImportRuns(imports, jobs);

    UUID runId = runs.apply(P4kTestCatalogues.bytes(withItemlessPaintOfBase()), false).runId();

    assertThat(ship.getP4kUuid()).isEqualTo(BASE);
    assertThat(ship.getP4kDeletedAt()).isNull();
    assertThat(P4kImportRuns.report(changes, runId))
        .anyMatch(change -> movedTo(change, DEPARTED, BASE));
  }

  @Test
  void anEditionWaitsWhileAPaintOfItsBaseHasNoItem() {
    ShipType ship = ships.saveAndFlush(ship(NAME, EDITION, "TEST_Follow_Edition"));
    P4kImportRuns runs = new P4kImportRuns(imports, jobs);

    runs.apply(P4kTestCatalogues.bytes(withItemlessPaintOfBase()), false);

    assertThat(ship.getP4kUuid()).isEqualTo(EDITION);
  }

  @Test
  void aShipTypeWhoseGuidLeftKeepsItsOwnDescriptionsWhenItFollowsItsSuccessor() {
    ShipType departed = ship(NAME, DEPARTED, "TEST_Follow_Old");
    departed.setDescriptionEn(WIKI_TEXT_EN);
    departed.setDescriptionDe(WIKI_TEXT_DE);
    ShipType ship = ships.saveAndFlush(departed);
    P4kImportRuns runs = new P4kImportRuns(imports, jobs);

    runs.apply(P4kTestCatalogues.bytes(catalogue()), false);

    assertThat(ship.getP4kUuid()).isEqualTo(BASE);
    assertThat(ship.getDescriptionEn()).isEqualTo(WIKI_TEXT_EN);
    assertThat(ship.getDescriptionDe()).isEqualTo(WIKI_TEXT_DE);
  }

  @Test
  void anEditionWaitsWhileItsDescriptionDiffersFromItsBase() {
    ShipType edition = ship(NAME, EDITION, "TEST_Follow_Edition");
    edition.setDescriptionEn(WIKI_TEXT_EN);
    ShipType ship = ships.saveAndFlush(edition);
    P4kImportRuns runs = new P4kImportRuns(imports, jobs);

    runs.apply(P4kTestCatalogues.bytes(catalogue()), false);

    assertThat(ship.getP4kUuid()).isEqualTo(EDITION);
  }

  @Test
  void aSuccessorTwoPlayerRecordsShareFollowsNeither() {
    ShipType ship = ships.saveAndFlush(ship(NAME, DEPARTED, "TEST_Follow_Old"));
    ObjectNode ambiguous = catalogue();
    ObjectNode edition = P4kTestCatalogues.record(ambiguous, "ships", EDITION);
    edition.putNull("variantOf").put("playability", "player");
    P4kImportRuns runs = new P4kImportRuns(imports, jobs);

    runs.apply(P4kTestCatalogues.bytes(ambiguous), false, true);

    assertThat(ship.getP4kUuid()).isEqualTo(DEPARTED);
    assertThat(ship.getP4kDeletedAt()).isNotNull();
  }

  private static boolean movedTo(P4kImportChange change, UUID from, UUID to) {
    return change.getKind() == P4kKind.SHIP
        && change.getChangeType() == P4kChangeType.CHANGED
        && "p4k_uuid".equals(change.getFieldName())
        && from.toString().equals(change.getOldValue())
        && to.toString().equals(change.getNewValue());
  }

  private static ShipType ship(String name, UUID p4kUuid, String className) {
    ShipType ship = new ShipType();
    ship.setName(name);
    ship.setP4kUuid(p4kUuid);
    ship.setClassName(className);
    ship.setSourceSystems(GameItemSourceSystem.BOTH);
    return ship;
  }

  /** Builds the fixture with a player base and a same-name player edition of it. */
  private static ObjectNode catalogue() {
    return P4kTestCatalogues.withEdition(BASE, BASE_CLASS, EDITION, "TEST_Follow_Edition", NAME);
  }

  /**
   * Builds the fixture with one more paint, naming the base, whose item the catalogue and the
   * Basetool lack.
   *
   * @return the tree
   */
  private static ObjectNode withItemlessPaintOfBase() {
    ObjectNode root = catalogue();
    ObjectNode paint = ((ArrayNode) root.get("paints")).addObject();
    paint.put("itemGuid", ITEMLESS_PAINT.toString());
    paint.putArray("shipGuids").add(BASE.toString());
    return root;
  }
}
