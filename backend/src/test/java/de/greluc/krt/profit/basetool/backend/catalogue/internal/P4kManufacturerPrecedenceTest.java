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

import de.greluc.krt.profit.basetool.backend.catalogue.api.Manufacturer;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

/**
 * The P4K leads a manufacturer's identity, name, abbreviation, German name and descriptions; a name
 * another row holds goes to review; a manufacturer is created only for a row the run seeds
 * (REQ-P4K-020, REQ-P4K-021, REQ-P4K-023).
 */
@SpringBootTest
@Transactional
class P4kManufacturerPrecedenceTest {

  /** Gorgon Defender Industries, code {@code GODI}, referenced by the Shield. */
  private static final UUID GORGON = UUID.fromString("57bfcf3d-13f6-4c2c-b7c7-6bf8eacb8a24");

  /** Kastak Arms, code {@code KSAR}. */
  private static final UUID KASTAK = UUID.fromString("709e8255-a0b1-4c5e-8b0e-28781be25984");

  /** Aegis Dynamics, code {@code AEG}, referenced by the player ship. */
  private static final UUID AEGIS = UUID.fromString("cf4a74bf-eb2c-462a-9b78-f7f2724c31d2");

  /** Anvil Aerospace, referenced only by the AI ship. */
  private static final UUID ANVIL = UUID.fromString("b922abdb-0634-4358-9eee-60118f3cf3a7");

  @Autowired private P4kImportService service;
  @Autowired private P4kImportJobRepository jobRepository;
  @Autowired private ManufacturerRepository manufacturerRepository;
  @Autowired private ShipTypeRepository shipTypeRepository;
  @Autowired private P4kReviewCaseRepository reviewCaseRepository;
  @Autowired private P4kImportChangeRepository changeRepository;

  private P4kImportRuns runs;

  @BeforeEach
  void setUp() {
    runs = new P4kImportRuns(service, jobRepository);
  }

  private Manufacturer manufacturer(String name, String abbreviation) {
    Manufacturer row = new Manufacturer();
    row.setName(name);
    row.setAbbreviation(abbreviation);
    row.setDescription("UEX text");
    return manufacturerRepository.save(row);
  }

  private static ObjectNode named(ObjectNode root, UUID guid, String en, String de) {
    ObjectNode record = P4kTestCatalogues.record(root, "manufacturers", guid);
    ObjectNode name = (ObjectNode) record.get("name");
    name.put("en", en);
    name.put("de", de);
    return record;
  }

  @Test
  void aMatchedManufacturer_takesTheP4kName_codeAndGermanName_andBothDescriptions() {
    Manufacturer row = manufacturer("Gorgon Old Name", "GOR");
    row.setP4kUuid(GORGON);
    ObjectNode root = P4kTestCatalogues.tree();
    ObjectNode record =
        named(root, GORGON, "Gorgon Defender Industries P4K", "Gorgon Verteidigung");
    ObjectNode description = (ObjectNode) record.get("description");
    description.put("en", "English text");
    description.put("de", "Deutscher Text");

    runs.apply(P4kTestCatalogues.bytes(root), false);

    assertThat(row.getName()).isEqualTo("Gorgon Defender Industries P4K");
    assertThat(row.getAbbreviation()).isEqualTo("GODI");
    assertThat(row.getNameDe()).isEqualTo("Gorgon Verteidigung");
    assertThat(row.getDescription()).isEqualTo("English text");
    assertThat(row.getDescriptionDe()).isEqualTo("Deutscher Text");
  }

  @Test
  void aP4kNameAnotherRowHolds_leavesBothRows_andOpensANameConflictCase() {
    Manufacturer known = manufacturer("Gorgon Old Name", "GOR");
    known.setP4kUuid(GORGON);
    Manufacturer holder = manufacturer("Gorgon Name Holder", "GNH");
    ObjectNode root = P4kTestCatalogues.tree();
    named(root, GORGON, "Gorgon Name Holder", "Gorgon Name Holder");

    UUID run = runs.apply(P4kTestCatalogues.bytes(root), false).runId();

    assertThat(known.getName()).isEqualTo("Gorgon Old Name");
    assertThat(holder.getName()).isEqualTo("Gorgon Name Holder");
    assertThat(
            reviewCaseRepository.findByKindAndGuidAndReason(
                P4kKind.MANUFACTURER, GORGON, P4kReviewReason.NAME_CONFLICT))
        .hasValueSatisfying(
            reviewCase -> assertThat(reviewCase.getRowId()).isEqualTo(known.getId()));
    assertThat(P4kImportRuns.report(changeRepository, run))
        .filteredOn(c -> c.getChangeType() == P4kChangeType.REVIEW)
        .extracting(P4kImportChange::getGuid)
        .contains(GORGON);
  }

  @Test
  void anAbbreviationAlone_neverMatches() {
    Manufacturer row = manufacturer("Kastak Local Spelling", "KSAR");
    ObjectNode root = P4kTestCatalogues.tree();
    named(root, KASTAK, "Kastak Unique Test Name", "Kastak Unique Test Name");

    runs.apply(P4kTestCatalogues.bytes(root), false);

    assertThat(row.getP4kUuid()).isNull();
    assertThat(row.getName()).isEqualTo("Kastak Local Spelling");
    assertThat(manufacturerRepository.findByP4kUuid(KASTAK)).isEmpty();
  }

  @Test
  void aSeededShip_createsItsUnknownManufacturer_andAnUnreferencedOneIsOnlyReported() {
    ObjectNode root = P4kTestCatalogues.tree();
    named(root, AEGIS, "Aegis Seeding Test", "Aegis Seeding Test DE");
    named(root, ANVIL, "Anvil Seeding Test", "Anvil Seeding Test");
    byte[] catalogue = P4kTestCatalogues.bytes(root);

    P4kImportResultDto preview = runs.preview(catalogue);
    P4kImportResultDto applied = runs.apply(catalogue, true);

    Manufacturer aegis = manufacturerRepository.findByP4kUuid(AEGIS).orElseThrow();
    assertThat(aegis.getName()).isEqualTo("Aegis Seeding Test");
    assertThat(aegis.getAbbreviation()).isEqualTo("AEG");
    assertThat(aegis.getNameDe()).isEqualTo("Aegis Seeding Test DE");
    assertThat(shipTypeRepository.findByP4kUuid(P4kTestCatalogues.PLAYER_SHIP))
        .hasValueSatisfying(ship -> assertThat(ship.getManufacturer()).isSameAs(aegis));
    assertThat(manufacturerRepository.findByP4kUuid(ANVIL)).isEmpty();
    assertThat(preview.manufacturers().created()).isEqualTo(applied.manufacturers().created());
    assertThat(P4kImportRuns.report(changeRepository, applied.runId()))
        .filteredOn(c -> c.getKind() == P4kKind.MANUFACTURER)
        .satisfies(rows -> {
          assertThat(rows)
              .filteredOn(c -> c.getChangeType() == P4kChangeType.ADDED)
              .extracting(P4kImportChange::getGuid)
              .contains(AEGIS);
          assertThat(rows)
              .filteredOn(c -> c.getChangeType() == P4kChangeType.UNMATCHED)
              .extracting(P4kImportChange::getGuid)
              .contains(ANVIL)
              .doesNotContain(AEGIS);
        });
  }

  @Test
  void twoSeededManufacturersOfOneName_createTheFirst_andOpenANameConflictCaseForTheSecond() {
    ObjectNode root = P4kTestCatalogues.tree();
    named(root, GORGON, "Shared Seed Name", "Shared Seed Name");
    named(root, AEGIS, "Shared Seed Name", "Shared Seed Name");
    byte[] catalogue = P4kTestCatalogues.bytes(root);

    P4kImportResultDto preview = runs.preview(catalogue);
    P4kImportResultDto applied = runs.apply(catalogue, true);

    Manufacturer created = manufacturerRepository.findByP4kUuid(GORGON).orElseThrow();
    assertThat(created.getName()).isEqualTo("Shared Seed Name");
    assertThat(manufacturerRepository.findByP4kUuid(AEGIS)).isEmpty();
    assertThat(
            reviewCaseRepository.findByKindAndGuidAndReason(
                P4kKind.MANUFACTURER, AEGIS, P4kReviewReason.NAME_CONFLICT))
        .hasValueSatisfying(
            reviewCase -> assertThat(reviewCase.getRowId()).isEqualTo(created.getId()));
    assertThat(preview.manufacturers().created()).isEqualTo(applied.manufacturers().created());
    assertThat(shipTypeRepository.findByP4kUuid(P4kTestCatalogues.PLAYER_SHIP))
        .hasValueSatisfying(ship -> assertThat(ship.getManufacturer()).isNull());
  }

  @Test
  void aSeededManufacturerWhoseNameAnExistingRowHolds_isNotCreated_andOpensANameConflictCase() {
    Manufacturer holder = manufacturer("Kastak Old Spelling", "KSAR");
    holder.setP4kUuid(KASTAK);
    ObjectNode root = P4kTestCatalogues.tree();
    UUID virgil = UUID.fromString("0e6d8f1b-ebb8-4773-8ca4-f25d737c8709");
    named(root, virgil, "Seed Clash Name", "Seed Clash Name");
    named(root, KASTAK, "Seed Clash Name", "Seed Clash Name");

    runs.apply(P4kTestCatalogues.bytes(root), true);

    assertThat(holder.getName()).isEqualTo("Seed Clash Name");
    assertThat(manufacturerRepository.findByP4kUuid(virgil)).isEmpty();
    assertThat(
            reviewCaseRepository.findByKindAndGuidAndReason(
                P4kKind.MANUFACTURER, virgil, P4kReviewReason.NAME_CONFLICT))
        .hasValueSatisfying(
            reviewCase -> assertThat(reviewCase.getRowId()).isEqualTo(holder.getId()));
  }

  @Test
  void withoutTheCheckbox_noManufacturerIsCreated() {
    ObjectNode root = P4kTestCatalogues.tree();
    named(root, AEGIS, "Aegis Seeding Test", "Aegis Seeding Test");

    P4kImportResultDto applied = runs.apply(P4kTestCatalogues.bytes(root), false);

    assertThat(manufacturerRepository.findByP4kUuid(AEGIS)).isEmpty();
    assertThat(applied.manufacturers().created()).isZero();
  }
}
