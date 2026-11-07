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

/**
 * The P4K leads a commodity's identity, name, density and refined-from link; {@code subtypeGuid} is
 * a join, not a key (REQ-P4K-024, ADR-0339).
 */
@SpringBootTest
@Transactional
class P4kCommodityPrecedenceTest {

  private static final UUID BORASE = UUID.fromString("001c7bc6-2bb6-4f66-a8ef-a1c63e80ffc5");

  /** The fixture's ingredient {@code ResourceType} that no commodity record carries. */
  private static final UUID UNKNOWN_RESOURCE =
      UUID.fromString("7f4599b0-a2b2-4178-8c7e-13292054ab20");

  private static final UUID SHARED_SUBTYPE =
      UUID.fromString("8950d9c5-8a54-4e2c-87a2-c56c1b9edd3c");

  @Autowired private P4kImportService service;
  @Autowired private P4kImportJobRepository jobRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private P4kReviewCaseRepository reviewCaseRepository;
  @Autowired private P4kReviewService reviewService;

  /** The admin who assigns the materials of these tests. */
  private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-00000000a4d3");

  private P4kImportRuns runs;

  @BeforeEach
  void setUp() {
    runs = new P4kImportRuns(service, jobRepository);
  }

  private Material material(String name, UUID p4kUuid, Integer idCommodity) {
    Material material = new Material();
    material.setName(name);
    material.setType(MaterialType.NO_REFINE);
    material.setP4kUuid(p4kUuid);
    material.setIdCommodity(idCommodity);
    return materialRepository.save(material);
  }

  private static UUID addCommodity(ObjectNode root, int n, String name, UUID subtype) {
    ObjectNode copy = P4kTestCatalogues.record(root, "commodities", BORASE).deepCopy();
    UUID guid = UUID.fromString("0f000000-0000-4000-8000-0000000c000" + n);
    copy.put("guid", guid.toString());
    copy.put("className", "TestCommodity" + n);
    ((ObjectNode) copy.get("name")).put("en", name);
    copy.put("subtypeGuid", subtype.toString());
    ((ArrayNode) root.get("commodities")).add(copy);
    return guid;
  }

  private static void addIngredient(ObjectNode root, UUID resource) {
    ArrayNode ingredients = (ArrayNode) root.get("blueprints").get(0).get("ingredients");
    ingredients.addObject().put("resourceGuid", resource.toString());
  }

  private boolean hasCase(UUID guid, P4kReviewReason reason) {
    return reviewCaseRepository
        .findByKindAndGuidAndReason(P4kKind.COMMODITY, guid, reason)
        .isPresent();
  }

  @Test
  void threeCommoditiesSharingOneSubtype_importAsThreeRowsKeyedByTheirGuid() {
    ObjectNode root = P4kTestCatalogues.tree();
    Material[] rows = new Material[3];
    UUID[] guids = new UUID[3];
    for (int n = 0; n < 3; n++) {
      rows[n] = material("Slam Variant " + n, null, null);
      guids[n] = addCommodity(root, n, "Slam Variant " + n, SHARED_SUBTYPE);
    }

    runs.apply(P4kTestCatalogues.bytes(root), false);

    for (int n = 0; n < 3; n++) {
      assertThat(rows[n].getP4kUuid()).isEqualTo(guids[n]);
      assertThat(rows[n].getP4kSubtypeUuid()).isEqualTo(SHARED_SUBTYPE);
    }
  }

  @Test
  void threeRecordsOfOneName_goToReview_andMatchNoRow() {
    ObjectNode root = P4kTestCatalogues.tree();
    Material slam = material("SLAM", null, null);
    UUID[] guids = new UUID[3];
    for (int n = 0; n < 3; n++) {
      guids[n] = addCommodity(root, n, "SLAM", SHARED_SUBTYPE);
    }

    runs.apply(P4kTestCatalogues.bytes(root), false);

    assertThat(slam.getP4kUuid()).isNull();
    for (UUID guid : guids) {
      assertThat(hasCase(guid, P4kReviewReason.GUID_CONFLICT)).isTrue();
    }
  }

  @Test
  void anIngredientJoiningSeveralCommodities_orNone_opensOneCaseAcrossRuns_andCreatesNothing() {
    ObjectNode root = P4kTestCatalogues.tree();
    for (int n = 0; n < 2; n++) {
      addCommodity(root, n, "Redfin Energy Modulator " + n, SHARED_SUBTYPE);
    }
    addIngredient(root, SHARED_SUBTYPE);

    UUID first = runs.apply(P4kTestCatalogues.bytes(root), false).runId();
    UUID second = runs.apply(P4kTestCatalogues.bytes(root), false).runId();

    assertThat(materialRepository.findByNameIgnoreCase("Redfin Energy Modulator 0")).isEmpty();
    P4kReviewCase several =
        reviewCaseRepository
            .findByKindAndGuidAndReason(
                P4kKind.COMMODITY, SHARED_SUBTYPE, P4kReviewReason.RESOURCE_JOIN_SEVERAL)
            .orElseThrow();
    assertThat(several.getFirstRunId()).isEqualTo(first);
    assertThat(several.getLastRunId()).isEqualTo(second);
    assertThat(hasCase(UNKNOWN_RESOURCE, P4kReviewReason.RESOURCE_JOIN_NONE)).isTrue();
  }

  @Test
  void theAdminsMaterialForARefinedSubtypeJoiningNoCommodity_becomesTheRefinedMaterial() {
    ObjectNode root = P4kTestCatalogues.tree();
    UUID unjoined = UUID.fromString("0f000000-0000-4000-8000-00000000e011");
    P4kTestCatalogues.record(root, "commodities", BORASE)
        .put("refinedSubtypeGuid", unjoined.toString());
    Material borase = material("Borase", BORASE, null);
    Material assigned = material("Refined Borase", null, null);
    runs.apply(P4kTestCatalogues.bytes(root), false);
    P4kReviewCase open =
        reviewCaseRepository
            .findByKindAndGuidAndReason(
                P4kKind.COMMODITY, unjoined, P4kReviewReason.RESOURCE_JOIN_NONE)
            .orElseThrow();
    assertThat(borase.getRefinedMaterial()).isNull();

    reviewService.resolve(
        open.getId(), P4kReviewResolution.ASSIGN, null, assigned.getId(), open.getVersion(), ADMIN);
    runs.apply(P4kTestCatalogues.bytes(root), false);

    assertThat(borase.getRefinedMaterial()).isEqualTo(assigned);
  }

  @Test
  void theP4kLeadsTheNameAndTheDensityOfAMatchedMaterial() {
    Material borase = material("Old Borase", BORASE, 17);
    borase.setDensityGramPerCc(2.5);

    runs.apply(P4kTestCatalogues.fixtureBytes(), false);

    assertThat(borase.getName()).isEqualTo("Borase");
    assertThat(borase.getDensityGramPerCc()).isEqualTo(1.0);
  }

  @Test
  void aMaterialUexDoesNotKnow_becomesRaw_andItsTargetRefined() {
    ObjectNode root = P4kTestCatalogues.tree();
    P4kTestCatalogues.record(root, "commodities", BORASE)
        .put("refinedSubtypeGuid", P4kTestCatalogues.ASLARITE.toString());
    Material borase = material("Borase", null, null);
    Material aslarite = material("Aslarite", null, null);

    runs.apply(P4kTestCatalogues.bytes(root), false);

    assertThat(borase.getType()).isEqualTo(MaterialType.RAW);
    assertThat(borase.getRefinedMaterial()).isEqualTo(aslarite);
    assertThat(aslarite.getType()).isEqualTo(MaterialType.REFINED);
  }

  @Test
  void aMaterialUexKnows_keepsTheUexType() {
    ObjectNode root = P4kTestCatalogues.tree();
    P4kTestCatalogues.record(root, "commodities", BORASE)
        .put("refinedSubtypeGuid", P4kTestCatalogues.ASLARITE.toString());
    Material borase = material("Borase", null, 17);
    Material aslarite = material("Aslarite", null, 18);

    runs.apply(P4kTestCatalogues.bytes(root), false);

    assertThat(borase.getType()).isEqualTo(MaterialType.NO_REFINE);
    assertThat(aslarite.getType()).isEqualTo(MaterialType.NO_REFINE);
    assertThat(borase.getRefinedMaterial()).isEqualTo(aslarite);
  }
}
