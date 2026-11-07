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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** The resource groups are the commodity categories (REQ-P4K-025, ADR-0339). */
@SpringBootTest
@Transactional
class P4kResourceGroupImportTest {

  /** The fixture's Borase, a commodity of group {@link #METAL} in the real export. */
  private static final UUID BORASE = UUID.fromString("001c7bc6-2bb6-4f66-a8ef-a1c63e80ffc5");

  private static final UUID METAL = UUID.fromString("9b28b2e4-7df3-43f3-a69a-d82b119e25bf");
  private static final UUID UNREFINED_ORES =
      UUID.fromString("36623177-cbc4-48a2-b95e-f893a861ca42");
  private static final UUID HEAT_PLACEHOLDER =
      UUID.fromString("1676f718-39c2-45d7-be78-4023db6452ff");
  private static final UUID LOOP_A = UUID.fromString("0f000000-0000-4000-8000-00000000a001");
  private static final UUID LOOP_B = UUID.fromString("0f000000-0000-4000-8000-00000000a002");

  @Autowired private P4kImportService service;
  @Autowired private P4kImportJobRepository jobRepository;
  @Autowired private MaterialCategoryRepository categoryRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private P4kImportChangeRepository changeRepository;

  private P4kImportRuns runs;

  @BeforeEach
  void setUp() {
    runs = new P4kImportRuns(service, jobRepository);
  }

  private static ObjectNode group(
      ObjectNode root, UUID guid, String className, String en, String de, UUID parent) {
    ObjectNode group = ((ArrayNode) root.get("resourceGroups")).addObject();
    group.put("guid", guid.toString());
    group.put("className", className);
    group.put("recordPath", "libs/foundry/records/resourcetypedatabase/resourcetypedatabase");
    ObjectNode name = group.putObject("name");
    name.put("key", en == null ? "@LOC_PLACEHOLDER" : "@items_commodities_type_" + className);
    name.put("en", en);
    name.put("de", de);
    group.put("parentGuid", parent == null ? null : parent.toString());
    return group;
  }

  private static void typeOfBorase(ObjectNode root, UUID group) {
    P4kTestCatalogues.record(root, "commodities", BORASE).put("typeGuid", group.toString());
  }

  private Material borase() {
    Material material = new Material();
    material.setName("Borase");
    material.setType(MaterialType.RAW);
    return materialRepository.save(material);
  }

  @Test
  void aPlaceholderGroupShowsItsClassName_andItsReferencedAncestorIsCreatedWithIt() {
    ObjectNode root = P4kTestCatalogues.tree();
    group(root, METAL, "Metal", "Metal", "Metall", null);
    group(root, UNREFINED_ORES, "UnrefinedOres", null, null, METAL);
    typeOfBorase(root, UNREFINED_ORES);
    Material borase = borase();

    P4kImportResultDto result = runs.apply(P4kTestCatalogues.bytes(root), false);

    MaterialCategory ores = categoryRepository.findByP4kUuid(UNREFINED_ORES).orElseThrow();
    MaterialCategory metal = categoryRepository.findByP4kUuid(METAL).orElseThrow();
    assertThat(ores.getName()).isEqualTo("UnrefinedOres");
    assertThat(ores.getNameDe()).isNull();
    assertThat(ores.getParent()).isEqualTo(metal);
    assertThat(metal.getNameDe()).isEqualTo("Metall");
    assertThat(metal.getP4kSeenRunId()).isEqualTo(result.runId());
    assertThat(borase.getCategory()).isEqualTo(ores);
    assertThat(categoryRepository.findByP4kUuid(HEAT_PLACEHOLDER)).isEmpty();
    assertThat(result.resourceGroups().created()).isEqualTo(2);
    assertThat(changeRepository.findByRunId(result.runId()))
        .filteredOn(c -> c.getKind() == P4kKind.RESOURCE_GROUP)
        .filteredOn(c -> c.getChangeType() == P4kChangeType.UNMATCHED)
        .extracting(P4kImportChange::getGuid)
        .containsExactly(HEAT_PLACEHOLDER);
  }

  @Test
  void twoGroupsListingEachOther_areImportedWithoutACycle() {
    ObjectNode root = P4kTestCatalogues.tree();
    group(root, LOOP_A, "LoopA", "Loop A", null, LOOP_B);
    group(root, LOOP_B, "LoopB", "Loop B", null, LOOP_A);
    typeOfBorase(root, LOOP_A);

    runs.apply(P4kTestCatalogues.bytes(root), false);

    MaterialCategory a = categoryRepository.findByP4kUuid(LOOP_A).orElseThrow();
    MaterialCategory b = categoryRepository.findByP4kUuid(LOOP_B).orElseThrow();
    assertThat(a.getParent()).isEqualTo(b);
    assertThat(b.getParent()).isNull();
  }

  @Test
  void anAdminCategoryOfTheGroupsName_becomesTheGroup_andKeepsItsMaterials() {
    MaterialCategory admin = new MaterialCategory();
    admin.setName("Metal");
    categoryRepository.save(admin);
    Material borase = borase();
    borase.setCategory(admin);
    ObjectNode root = P4kTestCatalogues.tree();
    group(root, METAL, "Metal", "Metal", "Metall", null);
    typeOfBorase(root, METAL);

    P4kImportResultDto result = runs.apply(P4kTestCatalogues.bytes(root), false);

    assertThat(admin.getP4kUuid()).isEqualTo(METAL);
    assertThat(admin.getClassName()).isEqualTo("Metal");
    assertThat(borase.getCategory()).isEqualTo(admin);
    assertThat(result.resourceGroups().created()).isZero();
    assertThat(result.resourceGroups().matched()).isEqualTo(1);
  }

  @Test
  void aGroupThatVanishesFromACompleteKind_isStamped() {
    ObjectNode root = P4kTestCatalogues.tree();
    group(root, METAL, "Metal", "Metal", "Metall", null);
    typeOfBorase(root, METAL);
    runs.apply(P4kTestCatalogues.bytes(root), false);
    MaterialCategory metal = categoryRepository.findByP4kUuid(METAL).orElseThrow();
    ObjectNode later = P4kTestCatalogues.tree();
    group(later, LOOP_A, "Other", "Other", null, null);
    P4kTestCatalogues.record(later, "commodities", BORASE).put("typeGuid", LOOP_A.toString());

    runs.apply(P4kTestCatalogues.bytes(later), false, true);

    assertThat(metal.getP4kDeletedAt()).isNotNull();
  }
}
