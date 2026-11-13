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
import de.greluc.krt.profit.basetool.backend.catalogue.api.Manufacturer;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import jakarta.persistence.EntityManager;
import java.time.Instant;
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
  @Autowired private P4kManufacturerReviewActions manufacturerActions;
  @Autowired private ManufacturerP4kAliasRepository p4kAliases;
  @Autowired private ManufacturerUexCompanyRepository uexCompanies;
  @Autowired private ManufacturerScwikiIdentityRepository wikiIdentities;
  @Autowired private GameItemRepository items;
  @Autowired private EntityManager entityManager;

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

  @Test
  void auxiliaryRecordsPreserveKnownShipBrandsAndDoNotClaimCanonicalNames() {
    Manufacturer aegis = manufacturer("Known Aegis Brand", "AEG");
    aegis.setP4kUuid(AEGIS);
    Manufacturer gorgon = manufacturer("Canonical Gorgon Brand", "GODI");
    GameItem shield = new GameItem();
    shield.setName("Shield With Known Manufacturer");
    shield.setP4kUuid(P4kTestCatalogues.SHIELD);
    shield.setManufacturer(gorgon);
    shield.setP4kManufacturer(gorgon);
    items.save(shield);
    ObjectNode root = P4kTestCatalogues.tree();
    P4kTestCatalogues.record(root, "items", P4kTestCatalogues.SHIELD)
        .put("manufacturerGuid", ANVIL.toString());
    named(root, GORGON, gorgon.getName(), gorgon.getName());
    named(root, AEGIS, gorgon.getName(), gorgon.getName())
        .put("recordPath", "libs/foundry/records/scitemmanufacturer/paintcolorlogos/known_logo");
    named(root, ANVIL, gorgon.getName(), gorgon.getName())
        .put("recordPath", "libs/foundry/records/scitemmanufacturer/shop/shopkiosk/new_shop");

    P4kImportResultDto applied = runs.apply(P4kTestCatalogues.bytes(root), true);

    assertThat(aegis.getName()).isEqualTo("Known Aegis Brand");
    assertThat(aegis.getP4kSeenRunId()).isEqualTo(applied.runId());
    assertThat(gorgon.getP4kUuid()).isEqualTo(GORGON);
    assertThat(shield.getManufacturer()).isSameAs(gorgon);
    assertThat(shield.getP4kManufacturer()).isSameAs(gorgon);
    assertThat(manufacturerRepository.findByP4kUuid(ANVIL)).isEmpty();
    assertThat(shipTypeRepository.findByP4kUuid(P4kTestCatalogues.PLAYER_SHIP))
        .hasValueSatisfying(ship -> assertThat(ship.getManufacturer()).isSameAs(aegis));
    assertThat(P4kImportRuns.report(changeRepository, applied.runId()))
        .filteredOn(change -> change.getKind() == P4kKind.MANUFACTURER)
        .satisfies(rows -> {
          assertThat(rows)
              .filteredOn(change -> change.getChangeType() == P4kChangeType.REVIEW)
              .extracting(P4kImportChange::getGuid)
              .doesNotContain(AEGIS, ANVIL, GORGON);
          assertThat(rows)
              .filteredOn(change -> change.getChangeType() == P4kChangeType.UNMATCHED)
              .extracting(P4kImportChange::getGuid)
              .contains(ANVIL);
        });
  }

  @Test
  void aConfirmedWikiGuidAliasSuppliesTheShipManufacturerWithoutLeadingTheBrandFields() {
    Manufacturer brand = manufacturer("Known Aegis Brand", "AEG");
    brand.setP4kUuid(GORGON);
    brand.setScwikiUuid(AEGIS);
    manufacturerRepository.flush();
    P4kReviewCaseDto review =
        manufacturerReview(UUID.randomUUID(), brand, AEGIS, "Untrusted name", "AEGS");
    String path = "libs/foundry/records/scitemmanufacturer/scitemmanufacturer.aegs";
    P4kReviewOptions options = manufacturerActions.options(review, path);
    assertThat(options.readiness()).isEqualTo(P4kReviewReadiness.ACTIONABLE);
    P4kReviewCandidate candidate =
        options.candidates().stream()
            .filter(option -> option.action() == P4kReviewAction.ATTACH_MANUFACTURER_ALIAS)
            .findFirst()
            .orElseThrow();
    assertThat(candidate.evidence())
        .contains(P4kReviewEvidence.EXACT_SCWIKI_UUID, P4kReviewEvidence.OTHER_P4K_GUID);
    manufacturerActions.apply(review, candidate, UUID.randomUUID());

    ObjectNode root = P4kTestCatalogues.tree();
    named(root, GORGON, brand.getName(), brand.getName());
    named(root, AEGIS, "Untrusted name", "Untrusted name");
    runs.apply(P4kTestCatalogues.bytes(root), true);

    assertThat(brand.getName()).isEqualTo("Known Aegis Brand");
    assertThat(brand.getP4kUuid()).isEqualTo(GORGON);
    assertThat(p4kAliases.findById(AEGIS))
        .hasValueSatisfying(alias -> assertThat(alias.getManufacturer()).isSameAs(brand));
    assertThat(shipTypeRepository.findByP4kUuid(P4kTestCatalogues.PLAYER_SHIP))
        .hasValueSatisfying(ship -> assertThat(ship.getManufacturer()).isSameAs(brand));
  }

  @Test
  void applyClosesAnOpenAuxiliaryConflictOnlyAfterSeeingItsRecord() {
    Manufacturer aegis = manufacturer("Canonical Aegis Brand", "AEG");
    aegis.setP4kUuid(AEGIS);
    ObjectNode root = P4kTestCatalogues.tree();
    named(root, AEGIS, aegis.getName(), aegis.getName());
    named(root, ANVIL, aegis.getName(), aegis.getName());
    UUID firstRun = runs.apply(P4kTestCatalogues.bytes(root), false).runId();
    P4kReviewCase open =
        reviewCaseRepository
            .findByKindAndGuidAndReason(P4kKind.MANUFACTURER, ANVIL, P4kReviewReason.GUID_CONFLICT)
            .orElseThrow();
    assertThat(open.getStatus()).isEqualTo(P4kReviewStatus.OPEN);
    named(root, ANVIL, aegis.getName(), aegis.getName())
        .put("recordPath", "libs/foundry/records/scitemmanufacturer/paintcolorlogos/anvil_logo");
    byte[] catalogue = P4kTestCatalogues.bytes(root);

    runs.preview(catalogue);
    assertThat(open.getStatus()).isEqualTo(P4kReviewStatus.OPEN);
    runs.apply(catalogue, false);

    assertThat(open.getStatus()).isEqualTo(P4kReviewStatus.REVIEWED);
    assertThat(open.getResolution()).isEqualTo(P4kReviewResolution.RESOLVED);
    assertThat(open.getLastRunId()).isEqualTo(firstRun);
  }

  @Test
  void aVerifiedUexDuplicateMergeKeepsTheP4kBrandAndRepointsBothItemRolesAndShips() {
    UUID run = runs.apply(P4kTestCatalogues.fixtureBytes(), false).runId();
    UUID reviewedGuid = UUID.randomUUID();
    Manufacturer survivor = manufacturer("Test Musashi & Starflight", "MIS");
    survivor.setP4kUuid(UUID.randomUUID());
    survivor.setScwikiUuid(reviewedGuid);
    survivor.setScwikiCode("MISC");
    Manufacturer duplicate = manufacturer("Test Musashi and Starflight", "MISC");
    duplicate.setUexCompanyId(17991);
    uexCompanies.save(new ManufacturerUexCompany(17991, duplicate));
    wikiIdentities.save(
        new ManufacturerScwikiIdentity(
            reviewedGuid, survivor, survivor.getName(), "MISC", Instant.now()));
    GameItem item = new GameItem();
    item.setName("Manufacturer Merge Item");
    item.setManufacturer(duplicate);
    item.setP4kManufacturer(duplicate);
    UUID itemId = items.saveAndFlush(item).getId();
    ShipType ship = new ShipType();
    ship.setName("Manufacturer Merge Ship");
    ship.setManufacturer(duplicate);
    UUID shipId = shipTypeRepository.saveAndFlush(ship).getId();
    UUID survivorId = survivor.getId();
    UUID duplicateId = duplicate.getId();
    UUID activeId =
        reviewCaseRepository
            .save(manufacturerCase(run, reviewedGuid, survivorId, duplicateId))
            .getId();
    UUID otherId =
        reviewCaseRepository
            .save(manufacturerCase(run, UUID.randomUUID(), duplicateId, survivorId))
            .getId();
    P4kReviewCase historical = manufacturerCase(run, UUID.randomUUID(), duplicateId, survivorId);
    historical.setStatus(P4kReviewStatus.REVIEWED);
    historical.setResolution(P4kReviewResolution.KEEP);
    historical.setResolvedAt(Instant.now());
    historical.setResolvedBy(P4kImportRuns.ADMIN);
    UUID historicalId = reviewCaseRepository.saveAndFlush(historical).getId();
    entityManager.clear();
    P4kReviewCaseDto review =
        manufacturerReview(activeId, survivor, reviewedGuid, survivor.getName(), "MISC");
    String path = "libs/foundry/records/scitemmanufacturer/scitemmanufacturer.misc";
    P4kReviewOptions options = manufacturerActions.options(review, path);
    assertThat(options.readiness()).isEqualTo(P4kReviewReadiness.CHECK_REQUIRED);
    assertThat(options.candidates())
        .extracting(P4kReviewCandidate::action)
        .contains(P4kReviewAction.ATTACH_MANUFACTURER_ALIAS, P4kReviewAction.MERGE_MANUFACTURER);
    P4kReviewCandidate candidate =
        options.candidates().stream()
            .filter(option -> option.action() == P4kReviewAction.MERGE_MANUFACTURER)
            .findFirst()
            .orElseThrow();
    assertThat(candidate.targetId()).isEqualTo(duplicateId);
    assertThat(candidate.uexIds()).containsExactly(17991);

    manufacturerActions.apply(review, candidate, UUID.randomUUID());
    entityManager.flush();
    entityManager.clear();

    assertThat(manufacturerRepository.findById(duplicateId)).isEmpty();
    assertThat(uexCompanies.findManufacturerByUexCompanyId(17991))
        .hasValueSatisfying(brand -> assertThat(brand.getId()).isEqualTo(survivorId));
    assertThat(p4kAliases.findById(reviewedGuid))
        .hasValueSatisfying(
            alias -> assertThat(alias.getManufacturer().getId()).isEqualTo(survivorId));
    assertThat(items.findById(itemId))
        .hasValueSatisfying(saved -> {
          assertThat(saved.getManufacturer().getId()).isEqualTo(survivorId);
          assertThat(saved.getP4kManufacturer().getId()).isEqualTo(survivorId);
        });
    assertThat(shipTypeRepository.findById(shipId))
        .hasValueSatisfying(
            saved -> assertThat(saved.getManufacturer().getId()).isEqualTo(survivorId));
    assertThat(reviewCaseRepository.findById(otherId))
        .hasValueSatisfying(saved -> {
          assertThat(saved.getRowId()).isEqualTo(survivorId);
          assertThat(saved.getOtherRowId()).isNull();
          assertThat(saved.getStatus()).isEqualTo(P4kReviewStatus.OPEN);
        });
    assertThat(reviewCaseRepository.findById(activeId))
        .hasValueSatisfying(saved -> assertThat(saved.getOtherRowId()).isEqualTo(duplicateId));
    assertThat(reviewCaseRepository.findById(historicalId))
        .hasValueSatisfying(saved -> assertThat(saved.getRowId()).isEqualTo(duplicateId));
  }

  private static P4kReviewCaseDto manufacturerReview(
      UUID id, Manufacturer row, UUID guid, String name, String className) {
    UUID run = UUID.randomUUID();
    return new P4kReviewCaseDto(
        id,
        0,
        P4kKind.MANUFACTURER,
        guid,
        P4kReviewReason.GUID_CONFLICT,
        P4kReviewStatus.OPEN,
        className,
        name,
        row.getId(),
        row.getName(),
        null,
        run,
        run,
        Instant.now(),
        null,
        null);
  }

  private static P4kReviewCase manufacturerCase(UUID run, UUID guid, UUID row, UUID other) {
    P4kReviewCase review = new P4kReviewCase();
    review.setKind(P4kKind.MANUFACTURER);
    review.setGuid(guid);
    review.setReason(P4kReviewReason.GUID_CONFLICT);
    review.setFirstRunId(run);
    review.setLastRunId(run);
    review.setRowId(row);
    review.setOtherRowId(other);
    return review;
  }
}
