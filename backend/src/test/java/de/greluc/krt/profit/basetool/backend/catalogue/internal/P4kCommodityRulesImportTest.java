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

import static de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kCommodityFixtures.BORASE;
import static de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kCommodityFixtures.BORASE_TYPE;
import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialCategory;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialSourceSystem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The commodity rules of the P4K match, the groups a material takes from its {@code ResourceType}
 * and the names of namesake groups (REQ-P4K-025, REQ-P4K-070…074, ADR-0396).
 */
@SpringBootTest
@Transactional
class P4kCommodityRulesImportTest {

  private static final UUID TWIN_A = UUID.fromString("0f000000-0000-4000-8000-0000000c0a01");
  private static final UUID TWIN_B = UUID.fromString("0f000000-0000-4000-8000-0000000c0a02");
  private static final UUID TWIN_A_TYPE = UUID.fromString("0f000000-0000-4000-8000-0000000c0b01");
  private static final UUID TWIN_B_TYPE = UUID.fromString("0f000000-0000-4000-8000-0000000c0b02");
  private static final UUID SLAM_TYPE = UUID.fromString("8950d9c5-8a54-4e2c-87a2-c56c1b9edd3c");
  private static final UUID SLAM_GROUP = UUID.fromString("c1a21fd1-16e0-465f-9f51-f5f931de0dea");
  private static final UUID GAS = UUID.fromString("3a31ccbf-bb2d-4a26-9b21-2f7d0222f4a6");
  private static final UUID HELIUM_TYPE = UUID.fromString("00686389-dfb8-49e4-9eea-66a4ebf24db2");
  private static final UUID BULK = UUID.fromString("5ae71dc9-d210-42bc-9f2a-1188c3de0aec");
  private static final UUID AMMO_TYPE = UUID.fromString("1fdb2e84-1622-4685-8194-2d108498d90f");
  private static final UUID SYNTHETIC = UUID.fromString("361e993d-9b2e-450c-9315-0fd1bfd3af20");
  private static final UUID CLEAN_AIR = UUID.fromString("1817e4e3-2b22-4c4a-b2db-c7afdb731e6a");
  private static final UUID MOLD_TYPE = UUID.fromString("0f000000-0000-4000-8000-0000000c0b03");
  private static final String MAN_MADE = "Man-made";
  private static final String MAN_MADE_DE = "Hergestellte Produkte";

  @Autowired private P4kImportService service;
  @Autowired private P4kImportJobRepository jobRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private MaterialCategoryRepository categoryRepository;
  @Autowired private P4kReviewCaseRepository reviewCaseRepository;
  @Autowired private P4kImportChangeRepository changeRepository;

  private P4kImportRuns runs;

  @BeforeEach
  void setUp() {
    runs = new P4kImportRuns(service, jobRepository);
  }

  private Material material(String name, MaterialSourceSystem source) {
    Material material = new Material();
    material.setName(name);
    material.setType(MaterialType.NO_REFINE);
    material.setSourceSystems(source);
    return materialRepository.save(material);
  }

  private boolean hasCase(P4kKind kind, UUID guid, P4kReviewReason reason) {
    return reviewCaseRepository.findByKindAndGuidAndReason(kind, guid, reason).isPresent();
  }

  private List<P4kImportChange> report(byte[] payload) {
    return P4kImportRuns.report(changeRepository, runs.apply(payload, false).runId());
  }

  private static List<P4kImportChange> commodityChanges(
      List<P4kImportChange> changes, P4kChangeType type) {
    return changes.stream()
        .filter(change -> change.getKind() == P4kKind.COMMODITY && change.getChangeType() == type)
        .toList();
  }

  private static ObjectNode withTwins() {
    ObjectNode root = P4kTestCatalogues.tree();
    P4kCommodityFixtures.commodity(root, TWIN_A, "TwinA", "Twin", TWIN_A_TYPE, null);
    P4kCommodityFixtures.commodity(root, TWIN_B, "TwinB", "Twin", TWIN_B_TYPE, null);
    return root;
  }

  @Test
  void aMaterialHoldingTheResourceTypeOfARecord_matchesItByThatWikiKey() {
    Material twin = material("Borase Wiki Twin", MaterialSourceSystem.BOTH);
    twin.setScwikiUuid(BORASE_TYPE);

    List<P4kImportChange> changes = report(P4kTestCatalogues.fixtureBytes());

    assertThat(twin.getP4kUuid()).isEqualTo(BORASE);
    assertThat(twin.getName()).isEqualTo("Borase");
    assertThat(commodityChanges(changes, P4kChangeType.IDENTITY_MISMATCH)).isEmpty();
  }

  @Test
  void aMaterialMatchedByGuidWhoseWikiUuidIsItsResourceType_reportsNoIdentityMismatch() {
    Material borase = material("Borase", MaterialSourceSystem.BOTH);
    borase.setP4kUuid(BORASE);
    borase.setScwikiUuid(BORASE_TYPE);
    Material other = material("Aslarite", MaterialSourceSystem.BOTH);
    other.setScwikiUuid(UUID.fromString("0f000000-0000-4000-8000-0000000c0d01"));

    List<P4kImportChange> changes = report(P4kTestCatalogues.fixtureBytes());

    assertThat(commodityChanges(changes, P4kChangeType.IDENTITY_MISMATCH))
        .extracting(P4kImportChange::getNameEn)
        .containsExactly("Aslarite");
  }

  @Test
  void packagingsOfOneCommodity_matchOneRowThroughTheRecordOfTheTypesClassName() {
    ObjectNode root = P4kTestCatalogues.tree();
    UUID[] guids = {
      UUID.fromString("0f000000-0000-4000-8000-0000000c0e01"),
      UUID.fromString("0f000000-0000-4000-8000-0000000c0e02"),
      UUID.fromString("0f000000-0000-4000-8000-0000000c0e03")
    };
    String[] classes = {"slam_uncut", "slam", "slam_1"};
    for (int n = 0; n < guids.length; n++) {
      P4kCommodityFixtures.commodity(root, guids[n], classes[n], "SLAM", SLAM_TYPE, SLAM_GROUP);
    }
    P4kCommodityFixtures.resourceType(root, SLAM_TYPE, "Slam", "SLAM", SLAM_GROUP);
    Material slam = material("SLAM", MaterialSourceSystem.UEX_ONLY);

    List<P4kImportChange> changes = report(P4kTestCatalogues.bytes(root));

    assertThat(slam.getP4kUuid()).isEqualTo(guids[1]);
    for (UUID guid : guids) {
      assertThat(hasCase(P4kKind.COMMODITY, guid, P4kReviewReason.GUID_CONFLICT)).isFalse();
    }
    assertThat(commodityChanges(changes, P4kChangeType.COVERED))
        .extracting(P4kImportChange::getGuid)
        .containsExactlyInAnyOrder(guids[0], guids[2]);
  }

  @Test
  void aMaterialHoldingAnotherListedCommodity_isNoNameCandidate_andOpensNoConflict() {
    Material twin = material("Twin", MaterialSourceSystem.BOTH);
    twin.setP4kUuid(TWIN_A);

    runs.apply(P4kTestCatalogues.bytes(withTwins()), false);

    assertThat(twin.getP4kUuid()).isEqualTo(TWIN_A);
    assertThat(hasCase(P4kKind.COMMODITY, TWIN_A, P4kReviewReason.GUID_CONFLICT)).isFalse();
    assertThat(hasCase(P4kKind.COMMODITY, TWIN_B, P4kReviewReason.GUID_CONFLICT)).isFalse();
  }

  @Test
  void aGroupHoldingAnotherListedGroup_isNoNameCandidate_andTheNamesakeIsCreated() {
    ObjectNode root = P4kTestCatalogues.tree();
    P4kCommodityFixtures.resourceGroup(root, BULK, "Bulk_Supplies", MAN_MADE, MAN_MADE_DE);
    P4kCommodityFixtures.resourceGroup(
        root, SYNTHETIC, "SyntheticMaterials", MAN_MADE, MAN_MADE_DE);
    P4kTestCatalogues.record(root, "commodities", BORASE).put("typeGuid", BULK.toString());
    MaterialCategory synthetic = new MaterialCategory();
    synthetic.setName(MAN_MADE);
    synthetic.setClassName("SyntheticMaterials");
    synthetic.setP4kUuid(SYNTHETIC);
    categoryRepository.save(synthetic);

    runs.apply(P4kTestCatalogues.bytes(root), false);

    assertThat(hasCase(P4kKind.RESOURCE_GROUP, BULK, P4kReviewReason.GUID_CONFLICT)).isFalse();
    MaterialCategory bulk = categoryRepository.findByP4kUuid(BULK).orElseThrow();
    assertThat(bulk).isNotEqualTo(synthetic);
    assertThat(synthetic.getP4kUuid()).isEqualTo(SYNTHETIC);
    assertThat(synthetic.getName()).isEqualTo(MAN_MADE);
    assertThat(synthetic.getNameDe()).isEqualTo(MAN_MADE_DE);
    assertThat(bulk.getName()).isEqualTo(MAN_MADE + " (Bulk Supplies)");
    assertThat(bulk.getNameDe()).isEqualTo(MAN_MADE_DE + " (Bulk Supplies)");
  }

  @Test
  void aMaterialWhoseTypeOnlyAPlaceholderGroupLists_staysUngrouped_andOpensNoCase() {
    ObjectNode root = P4kTestCatalogues.tree();
    P4kCommodityFixtures.resourceGroup(root, CLEAN_AIR, "CleanAir", null, null);
    P4kCommodityFixtures.resourceType(root, MOLD_TYPE, "MolinaMold", "Molina Mold", CLEAN_AIR);
    Material mold = material("Molina Mold", MaterialSourceSystem.WIKI_ONLY);
    mold.setScwikiUuid(MOLD_TYPE);

    runs.apply(P4kTestCatalogues.bytes(root), false);

    assertThat(mold.getCategory()).isNull();
    assertThat(categoryRepository.findByP4kUuid(CLEAN_AIR)).isEmpty();
    assertThat(
            hasCase(
                P4kKind.COMMODITY,
                P4kMaterialGroupReview.caseGuid(mold.getName()),
                P4kReviewReason.NO_RESOURCE_GROUP))
        .isFalse();
  }

  @Test
  void aMaterialWithoutAGroup_takesTheGroupOfTheResourceTypeItsWikiUuidNames() {
    ObjectNode root = P4kTestCatalogues.tree();
    P4kCommodityFixtures.resourceGroup(root, GAS, "Gas", "Gas");
    P4kCommodityFixtures.resourceType(root, HELIUM_TYPE, "Helium", "Helium", GAS);
    Material helium = material("Helium", MaterialSourceSystem.WIKI_ONLY);
    helium.setScwikiUuid(HELIUM_TYPE);

    runs.apply(P4kTestCatalogues.bytes(root), false);

    MaterialCategory gas = categoryRepository.findByP4kUuid(GAS).orElseThrow();
    assertThat(helium.getCategory()).isEqualTo(gas);
    assertThat(helium.isResourceGroupManual()).isFalse();
    assertThat(
            hasCase(
                P4kKind.COMMODITY,
                P4kMaterialGroupReview.caseGuid(helium.getName()),
                P4kReviewReason.NO_RESOURCE_GROUP))
        .isFalse();
  }

  @Test
  void aMaterialWithoutAnIdentity_takesTheGroupOfTheOnlyResourceTypeOfItsName() {
    ObjectNode root = P4kTestCatalogues.tree();
    P4kCommodityFixtures.resourceGroup(root, BULK, "Bulk_Supplies", "Man-made");
    P4kCommodityFixtures.resourceType(
        root, AMMO_TYPE, "ShipAmmoSize1", "Ship Ammunition - Size 1", BULK);
    Material ammo = material("Ship Ammunition – Size 1", MaterialSourceSystem.UEX_ONLY);
    ammo.setPriceBuy(6868.0);

    runs.apply(P4kTestCatalogues.bytes(root), false);

    assertThat(ammo.getCategory()).isEqualTo(categoryRepository.findByP4kUuid(BULK).orElseThrow());
  }

  @Test
  void aGroupAnAdminOverrides_staysWhenTheResourceTypeNamesAnother() {
    ObjectNode root = P4kTestCatalogues.tree();
    P4kCommodityFixtures.resourceGroup(root, GAS, "Gas", "Gas");
    P4kCommodityFixtures.resourceType(root, HELIUM_TYPE, "Helium", "Helium", GAS);
    MaterialCategory erz = new MaterialCategory();
    erz.setName("Erz");
    categoryRepository.save(erz);
    Material helium = material("Helium", MaterialSourceSystem.WIKI_ONLY);
    helium.setScwikiUuid(HELIUM_TYPE);
    helium.setCategory(erz);
    helium.setResourceGroupManual(true);

    runs.apply(P4kTestCatalogues.bytes(root), false);

    assertThat(helium.getCategory()).isEqualTo(erz);
  }

  @Test
  void aCommodityConflictTheNextApplyNoLongerRaises_closesAsResolved() {
    material("Twin", MaterialSourceSystem.BOTH);
    ObjectNode both = withTwins();
    runs.apply(P4kTestCatalogues.bytes(both), false);
    assertThat(hasCase(P4kKind.COMMODITY, TWIN_A, P4kReviewReason.GUID_CONFLICT)).isTrue();
    ArrayNode commodities = (ArrayNode) both.get("commodities");
    for (int n = commodities.size() - 1; n >= 0; n--) {
      JsonNode record = commodities.get(n);
      if (TWIN_B.toString().equals(record.get("guid").asString())) {
        commodities.remove(n);
      }
    }

    runs.apply(P4kTestCatalogues.bytes(both), false);

    for (UUID guid : List.of(TWIN_A, TWIN_B)) {
      P4kReviewCase closed =
          reviewCaseRepository
              .findByKindAndGuidAndReason(P4kKind.COMMODITY, guid, P4kReviewReason.GUID_CONFLICT)
              .orElseThrow();
      assertThat(closed.getStatus()).isEqualTo(P4kReviewStatus.REVIEWED);
      assertThat(closed.getResolution()).isEqualTo(P4kReviewResolution.RESOLVED);
    }
  }
}
