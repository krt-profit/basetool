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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialCategory;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Each P4K-matched material takes its group from the catalogue; a material left without one opens a
 * review case the admin resolves with an existing group, and an admin category goes once no
 * material uses it (REQ-P4K-025, owner decision on the category mapping).
 */
@SpringBootTest
@Transactional
class P4kMaterialGroupReviewTest {

  private static final UUID BORASE = UUID.fromString("001c7bc6-2bb6-4f66-a8ef-a1c63e80ffc5");
  private static final UUID MINERAL = UUID.fromString("8e17c99b-1bf6-49cb-838e-e7eb72a9088f");
  private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-00000000a4d1");

  @Autowired private P4kImportService service;
  @Autowired private P4kImportJobRepository jobRepository;
  @Autowired private MaterialCategoryRepository categoryRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private P4kReviewCaseRepository reviewCaseRepository;
  @Autowired private P4kReviewService reviewService;

  private P4kImportRuns runs;

  @BeforeEach
  void setUp() {
    runs = new P4kImportRuns(service, jobRepository);
  }

  private static byte[] withMineral() {
    ObjectNode root = P4kTestCatalogues.tree();
    ObjectNode group = ((ArrayNode) root.get("resourceGroups")).addObject();
    group.put("guid", MINERAL.toString());
    group.put("className", "Mineral");
    group.put("recordPath", "libs/foundry/records/resourcetypedatabase/resourcetypedatabase");
    ObjectNode name = group.putObject("name");
    name.put("key", "@items_commodities_type_mineral");
    name.put("en", "Mineral");
    name.put("de", "Mineral");
    group.putNull("parentGuid");
    P4kTestCatalogues.record(root, "commodities", BORASE).put("typeGuid", MINERAL.toString());
    return P4kTestCatalogues.bytes(root);
  }

  private MaterialCategory adminCategory(String name) {
    MaterialCategory category = new MaterialCategory();
    category.setName(name);
    return categoryRepository.save(category);
  }

  private Material material(String name, MaterialCategory category) {
    Material material = new Material();
    material.setName(name);
    material.setType(MaterialType.RAW);
    material.setCategory(category);
    return materialRepository.save(material);
  }

  private P4kReviewCase groupCase(Material material) {
    return reviewCaseRepository
        .findByKindAndGuidAndReason(
            P4kKind.COMMODITY,
            P4kMaterialGroupReview.caseGuid(material.getName()),
            P4kReviewReason.NO_RESOURCE_GROUP)
        .orElseThrow();
  }

  @Test
  void aMatchedMaterialTakesItsRecordsGroup_andItsAdminCategoryGoesOnceUnused() {
    MaterialCategory erz = adminCategory("Erz");
    Material borase = material("Borase", erz);

    runs.apply(withMineral(), false);

    assertThat(borase.getCategory().getP4kUuid()).isEqualTo(MINERAL);
    assertThat(categoryRepository.findById(erz.getId())).isEmpty();
    assertThat(
            reviewCaseRepository.findByKindAndGuidAndReason(
                P4kKind.COMMODITY,
                P4kMaterialGroupReview.caseGuid(borase.getName()),
                P4kReviewReason.NO_RESOURCE_GROUP))
        .isEmpty();
  }

  @Test
  void aMaterialLeftWithoutAGroup_opensACase_andKeepsItsAdminCategoryReadable() {
    MaterialCategory erz = adminCategory("Raffiniertes Erz");
    Material unknown = material("Not In The Game", erz);

    runs.apply(withMineral(), false);

    assertThat(groupCase(unknown).getStatus()).isEqualTo(P4kReviewStatus.OPEN);
    assertThat(unknown.getCategory()).isEqualTo(erz);
    assertThat(categoryRepository.findById(erz.getId())).isPresent();
  }

  @Test
  void theAdminAssignsAnExistingGroup_andTheNextApplyLeavesTheCaseResolved() {
    Material unknown = material("Not In The Game", null);
    runs.apply(withMineral(), false);
    MaterialCategory mineral = categoryRepository.findByP4kUuid(MINERAL).orElseThrow();
    P4kReviewCase open = groupCase(unknown);

    P4kReviewCaseDto resolved =
        reviewService.resolve(
            open.getId(), P4kReviewResolution.ASSIGN, mineral.getId(), open.getVersion(), ADMIN);
    runs.apply(withMineral(), false);

    assertThat(resolved.status()).isEqualTo(P4kReviewStatus.REVIEWED);
    assertThat(unknown.getCategory()).isEqualTo(mineral);
    assertThat(groupCase(unknown).getResolution()).isEqualTo(P4kReviewResolution.ASSIGN);
  }

  @Test
  void assigningAnAdminCategory_orKeepingAGroupCase_isRefused() {
    MaterialCategory erz = adminCategory("Erz");
    Material unknown = material("Not In The Game", erz);
    runs.apply(withMineral(), false);
    P4kReviewCase open = groupCase(unknown);

    assertThatThrownBy(() ->
            reviewService.resolve(
                open.getId(), P4kReviewResolution.ASSIGN, erz.getId(), open.getVersion(), ADMIN))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() ->
            reviewService.resolve(
                open.getId(), P4kReviewResolution.KEEP, null, open.getVersion(), ADMIN))
        .isInstanceOf(BadRequestException.class);
  }
}
