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

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Manufacturer;
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

/**
 * A brand's sibling records resolve to the brand's row, a record borrowing another brand's name
 * keeps its own, and an APPLY settles the manufacturer cases it no longer raises (REQ-P4K-016,
 * REQ-P4K-058).
 */
@SpringBootTest
@Transactional
class P4kManufacturerFamilyImportTest {

  /** The fixture's Aegis Dynamics record. */
  private static final UUID AEGS = UUID.fromString("cf4a74bf-eb2c-462a-9b78-f7f2724c31d2");

  /** A sibling of Aegis the test adds: same name key, no code. */
  private static final UUID AEGS_ENV = UUID.fromString("0f000000-0000-4000-8000-00000000ae01");

  /** A record the test adds that borrows Aegis's name key under a code of its own. */
  private static final UUID FSKI = UUID.fromString("0f000000-0000-4000-8000-00000000f5c1");

  /** The fixture's Aegis cooler, a player item. */
  private static final UUID BRACER = UUID.fromString("4ec3037b-4b6f-4837-b00d-cd9b4836a82a");

  @Autowired private P4kImportService imports;
  @Autowired private P4kImportJobRepository jobs;
  @Autowired private P4kReviewCaseRepository cases;
  @Autowired private P4kImportChangeRepository changes;
  @Autowired private ManufacturerRepository manufacturers;
  @Autowired private GameItemRepository items;

  private P4kImportRuns runs;

  @BeforeEach
  void setUp() {
    runs = new P4kImportRuns(imports, jobs);
  }

  @Test
  void aSiblingIsCoveredByTheBrandRow_andAnItemOfItResolvesToTheBrand() {
    Manufacturer aegis = manufacturers.saveAndFlush(manufacturer("Aegis Dynamics", AEGS));
    ObjectNode root = withSibling();
    P4kTestCatalogues.record(root, "items", BRACER).put("manufacturerGuid", AEGS_ENV.toString());

    UUID runId = runs.apply(P4kTestCatalogues.bytes(root), true).runId();

    assertThat(openCase(AEGS_ENV)).isFalse();
    assertThat(P4kImportRuns.report(changes, runId))
        .anyMatch(change ->
            change.getChangeType() == P4kChangeType.COVERED
                && change.getKind() == P4kKind.MANUFACTURER
                && AEGS_ENV.equals(change.getGuid()));
    assertThat(items.findByP4kUuid(BRACER))
        .hasValueSatisfying(bracer -> assertThat(bracer.getManufacturer()).isEqualTo(aegis));
  }

  @Test
  void aRowHoldingASiblingMovesToTheFreePrimary() {
    Manufacturer aegis = manufacturers.saveAndFlush(manufacturer("Aegis Dynamics", AEGS_ENV));

    runs.apply(P4kTestCatalogues.bytes(withSibling()), false);

    assertThat(aegis.getP4kUuid()).isEqualTo(AEGS);
    assertThat(aegis.getAbbreviation()).isEqualTo("AEG");
    assertThat(openCase(AEGS)).isFalse();
    assertThat(openCase(AEGS_ENV)).isFalse();
  }

  @Test
  void aRecordBorrowingABrandsNameKeepsItsOwnName_andOpensNoNameConflict() {
    manufacturers.saveAndFlush(manufacturer("Aegis Dynamics", AEGS));
    Manufacturer fishkill = manufacturers.saveAndFlush(manufacturer("Fishkill", FSKI));

    runs.apply(P4kTestCatalogues.bytes(withBorrower()), false);

    assertThat(fishkill.getName()).isEqualTo("Fishkill");
    assertThat(fishkill.getAbbreviation()).isEqualTo("FSKI");
    assertThat(
            cases.findByKindAndGuidAndReason(
                P4kKind.MANUFACTURER, FSKI, P4kReviewReason.NAME_CONFLICT))
        .isEmpty();
  }

  @Test
  void aBorrowerTheMovingRowGivesUpLandsOnItsWikiRow_whicheverRowLoadsFirst() {
    Manufacturer fishkill = manufacturer("Fishkill", null);
    fishkill.setScwikiUuid(FSKI);
    manufacturers.saveAndFlush(fishkill);
    Manufacturer aegis = manufacturers.saveAndFlush(manufacturer("Aegis Dynamics", FSKI));

    runs.apply(P4kTestCatalogues.bytes(withBorrower()), false);
    manufacturers.flush();

    assertThat(aegis.getP4kUuid()).isEqualTo(AEGS);
    assertThat(fishkill.getP4kUuid()).isEqualTo(FSKI);
    assertThat(openCase(AEGS)).isFalse();
    assertThat(openCase(FSKI)).isFalse();
  }

  @Test
  void anApplyClosesTheManufacturerCasesItNoLongerRaises() {
    UUID firstRun = runs.apply(P4kTestCatalogues.fixtureBytes(), false).runId();
    P4kReviewCase stale = new P4kReviewCase();
    stale.setKind(P4kKind.MANUFACTURER);
    stale.setGuid(AEGS);
    stale.setReason(P4kReviewReason.NAME_CONFLICT);
    stale.setFirstRunId(firstRun);
    stale.setLastRunId(firstRun);
    cases.saveAndFlush(stale);

    runs.apply(P4kTestCatalogues.fixtureBytes(), false);

    assertThat(stale.getStatus()).isEqualTo(P4kReviewStatus.REVIEWED);
    assertThat(stale.getResolution()).isEqualTo(P4kReviewResolution.RESOLVED);
  }

  private boolean openCase(UUID guid) {
    return cases
        .findByKindAndGuidAndReason(P4kKind.MANUFACTURER, guid, P4kReviewReason.GUID_CONFLICT)
        .filter(review -> review.getStatus() == P4kReviewStatus.OPEN)
        .isPresent();
  }

  /**
   * Builds the fixture with a sibling of Aegis Dynamics: the same name key, no code.
   *
   * @return the catalogue tree
   */
  private static ObjectNode withSibling() {
    ObjectNode root = P4kTestCatalogues.tree();
    ObjectNode sibling = P4kTestCatalogues.record(root, "manufacturers", AEGS).deepCopy();
    sibling.put("guid", AEGS_ENV.toString());
    sibling.put("className", "AEGS_ENV");
    sibling.put("code", "");
    ((ArrayNode) root.get("manufacturers")).add(sibling);
    return root;
  }

  /**
   * Builds the fixture with {@link #FSKI}, a record borrowing the name key of Aegis Dynamics under
   * a code of its own, listed before every other manufacturer.
   *
   * @return the catalogue tree
   */
  private static ObjectNode withBorrower() {
    ObjectNode root = P4kTestCatalogues.tree();
    ObjectNode borrower = P4kTestCatalogues.record(root, "manufacturers", AEGS).deepCopy();
    borrower.put("guid", FSKI.toString());
    borrower.put("className", "FSKI");
    borrower.put("code", "FSKI");
    ((ArrayNode) root.get("manufacturers")).insert(0, borrower);
    return root;
  }

  private static Manufacturer manufacturer(String name, UUID p4kUuid) {
    Manufacturer manufacturer = new Manufacturer();
    manufacturer.setName(name);
    manufacturer.setAbbreviation(name);
    manufacturer.setP4kUuid(p4kUuid);
    return manufacturer;
  }
}
