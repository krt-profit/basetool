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
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialCategory;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A P4K APPLY keeps a material's resource group and refined material an admin overrides, reports
 * the P4K value it does not write, and leads the field again once the admin hands it back
 * (REQ-P4K-024, REQ-P4K-025).
 */
@SpringBootTest
@Transactional
class P4kMaterialOverrideTest {

  private static final UUID BORASE = UUID.fromString("001c7bc6-2bb6-4f66-a8ef-a1c63e80ffc5");
  private static final UUID MINERAL = UUID.fromString("8e17c99b-1bf6-49cb-838e-e7eb72a9088f");
  private static final UUID METAL = UUID.fromString("8e17c99b-1bf6-49cb-838e-e7eb72a90890");

  @Autowired private P4kImportService service;
  @Autowired private P4kImportJobRepository jobRepository;
  @Autowired private MaterialCategoryRepository categoryRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private P4kReviewCaseRepository reviewCaseRepository;
  @Autowired private ExternalSyncReportRepository syncReportRepository;
  @Autowired private MaterialService materialService;

  private P4kImportRuns runs;

  @BeforeEach
  void setUp() {
    runs = new P4kImportRuns(service, jobRepository);
  }

  private static void addGroup(
      @NotNull ObjectNode root, @NotNull UUID guid, @NotNull String name, @Nullable UUID parent) {
    ObjectNode group = ((ArrayNode) root.get("resourceGroups")).addObject();
    group.put("guid", guid.toString());
    group.put("className", name);
    group.put("recordPath", "libs/foundry/records/resourcetypedatabase/resourcetypedatabase");
    ObjectNode text = group.putObject("name");
    text.put("key", "@items_commodities_type_" + name.toLowerCase(Locale.ROOT));
    text.put("en", name);
    text.put("de", name);
    if (parent == null) {
      group.putNull("parentGuid");
    } else {
      group.put("parentGuid", parent.toString());
    }
  }

  /** The fixture with Borase in the group Mineral, a child of Metal. */
  private static byte[] withGroups() {
    ObjectNode root = P4kTestCatalogues.tree();
    addGroup(root, METAL, "Metal", null);
    addGroup(root, MINERAL, "Mineral", METAL);
    P4kTestCatalogues.record(root, "commodities", BORASE).put("typeGuid", MINERAL.toString());
    return P4kTestCatalogues.bytes(root);
  }

  private Material material(String name, UUID p4kUuid) {
    Material material = new Material();
    material.setName(name);
    material.setType(MaterialType.RAW);
    material.setP4kUuid(p4kUuid);
    return materialRepository.saveAndFlush(material);
  }

  private List<String> disagreements(UUID runId) {
    return syncReportRepository.findAll().stream()
        .filter(r -> runId.equals(r.getRunId()))
        .filter(r -> r.getEventType() == SyncEventType.P4K_DISAGREEMENT)
        .map(ExternalSyncReport::getDetail)
        .toList();
  }

  /** Sets Borase's group to Metal through the admin edit. */
  private void overrideGroup(Material borase) {
    materialRepository.flush();
    MaterialCategory metal = categoryRepository.findByP4kUuid(METAL).orElseThrow();
    Material edit = new Material();
    edit.setName(borase.getName());
    edit.setType(borase.getType());
    edit.setQuantityType(borase.getQuantityType());
    edit.setIsManualRawMaterial(borase.getIsManualRawMaterial());
    edit.setIsJobOrder(borase.getIsJobOrder());
    edit.setCategory(metal);
    edit.setVersion(borase.getVersion());
    materialService.updateMaterial(borase.getId(), edit);
    materialRepository.flush();
  }

  @Test
  void anOverriddenGroup_survivesTheApply_andTheRunReportsTheP4kGroup() {
    Material borase = material("Borase", BORASE);
    runs.apply(withGroups(), false);
    assertThat(borase.getCategory().getP4kUuid()).isEqualTo(MINERAL);

    overrideGroup(borase);
    UUID runId = runs.apply(withGroups(), false).runId();

    assertThat(borase.isResourceGroupManual()).isTrue();
    assertThat(borase.getCategory().getP4kUuid()).isEqualTo(METAL);
    assertThat(disagreements(runId))
        .containsExactly("column=category_id;stored=Metal;source=Mineral");
    assertThat(
            reviewCaseRepository.findByKindAndGuidAndReason(
                P4kKind.COMMODITY,
                P4kMaterialGroupReview.caseGuid(borase.getName()),
                P4kReviewReason.NO_RESOURCE_GROUP))
        .isEmpty();
  }

  @Test
  void aReleasedGroup_followsTheP4kAgainOnTheNextApply() {
    Material borase = material("Borase", BORASE);
    runs.apply(withGroups(), false);
    overrideGroup(borase);

    materialService.releaseP4kOverride(
        borase.getId(), MaterialP4kOverride.RESOURCE_GROUP, borase.getVersion());
    UUID runId = runs.apply(withGroups(), false).runId();

    assertThat(borase.isResourceGroupManual()).isFalse();
    assertThat(borase.getCategory().getP4kUuid()).isEqualTo(MINERAL);
    assertThat(disagreements(runId)).isEmpty();
  }

  @Test
  void anOverriddenRefinedMaterial_survivesTheApply_andTheRunReportsTheP4kLink() {
    ObjectNode root = P4kTestCatalogues.tree();
    P4kTestCatalogues.record(root, "commodities", BORASE)
        .put("refinedSubtypeGuid", P4kTestCatalogues.ASLARITE.toString());
    Material borase = material("Borase", null);
    Material aslarite = material("Aslarite", null);
    Material chosen = material("Chosen By Hand", null);
    borase.setRefinedMaterial(chosen);
    borase.setRefinedMaterialManual(true);
    materialRepository.flush();

    UUID runId = runs.apply(P4kTestCatalogues.bytes(root), false).runId();

    assertThat(borase.getRefinedMaterial()).isEqualTo(chosen);
    assertThat(aslarite.getType()).isEqualTo(MaterialType.REFINED);
    assertThat(disagreements(runId))
        .contains("column=refined_material_id;stored=Chosen By Hand;source=Aslarite");
  }
}
