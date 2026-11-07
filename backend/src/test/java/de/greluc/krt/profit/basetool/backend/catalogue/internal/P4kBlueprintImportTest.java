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

import de.greluc.krt.profit.basetool.backend.catalogue.api.Blueprint;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintIngredient;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintModifierValueKind;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintRequirementGroup;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintRequirementModifier;
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
 * The P4K leads a blueprint's recipe, category, craft time and dismantle, and its blueprint
 * categories are the categories (REQ-P4K-027, ADR-0340); an admin's material for a resource that
 * joins no commodity survives the next APPLY (REQ-P4K-024).
 */
@SpringBootTest
@Transactional
class P4kBlueprintImportTest {

  /** The fixture's light-armour blueprint: CASING (unknown resource) and INSULATIVE LINER. */
  private static final UUID ARMS = UUID.fromString("0037f308-adfc-4c4e-994f-cd477e895aab");

  private static final String ARMS_KEY = "BP_CRAFT_vgl_armor_light_arms_01_01_20";

  /** The {@code ResourceType} of the CASING line, which no commodity record carries. */
  private static final UUID CASING_RESOURCE =
      UUID.fromString("7f4599b0-a2b2-4178-8c7e-13292054ab20");

  private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-00000000a4d1");

  @Autowired private P4kImportService service;
  @Autowired private P4kImportJobRepository jobRepository;
  @Autowired private BlueprintRepository blueprintRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private P4kReviewCaseRepository reviewCaseRepository;
  @Autowired private P4kReviewService reviewService;

  private P4kImportRuns runs;

  @BeforeEach
  void setUp() {
    runs = new P4kImportRuns(service, jobRepository);
  }

  private Blueprint wikiBlueprint() {
    Blueprint blueprint = new Blueprint();
    blueprint.setScwikiUuid(UUID.randomUUID());
    blueprint.setScwikiKey(ARMS_KEY);
    blueprint.setCraftTimeSeconds(999);
    return blueprintRepository.save(blueprint);
  }

  private Material material(String name) {
    Material material = new Material();
    material.setName(name);
    material.setType(MaterialType.RAW);
    return materialRepository.save(material);
  }

  private static ObjectNode root(ObjectNode root) {
    return (ObjectNode)
        P4kTestCatalogues.record(root, "blueprints", ARMS).get("tiers").get(0).get("mandatory");
  }

  private static ObjectNode casingLine(ObjectNode root) {
    return (ObjectNode) root(root).get("options").get(0).get("options").get(0);
  }

  private static BlueprintIngredient line(Blueprint blueprint, UUID resource) {
    return blueprint.getIngredients().stream()
        .filter(i -> resource.equals(i.getP4kResourceGuid()))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void aLineWithTwoScuAndAMultiplierOf1Point15_stores2Point3Scu() {
    ObjectNode tree = P4kTestCatalogues.tree();
    casingLine(tree).put("quantityScu", 2).put("quantityMultiplier", 1.15);
    Blueprint blueprint = wikiBlueprint();

    runs.apply(P4kTestCatalogues.bytes(tree), false);

    assertThat(line(blueprint, CASING_RESOURCE).getQuantityScu()).isEqualTo(2.3);
  }

  @Test
  void aChoose2Of3Selection_isStoredAsOneSelectionWithThreeOptions() {
    ObjectNode tree = P4kTestCatalogues.tree();
    ArrayNode options = (ArrayNode) root(tree).get("options");
    ObjectNode third = (ObjectNode) options.get(0).deepCopy();
    third.put("slot", "PANEL COVERING");
    options.add(third);
    Blueprint blueprint = wikiBlueprint();

    runs.apply(P4kTestCatalogues.bytes(tree), false);

    assertThat(blueprint.getRequiredGroupCount()).isEqualTo(2);
    assertThat(blueprint.getRequirementGroups())
        .extracting(BlueprintRequirementGroup::getGroupKey)
        .containsExactly("CASING", "INSULATIVE LINER", "PANEL COVERING");
  }

  @Test
  void theP4kLeadsTheRecipeCategoryCraftTimeAndDismantle() {
    Material aslarite = material("Aslarite");
    Blueprint blueprint = wikiBlueprint();

    P4kImportResultDto result = runs.apply(P4kTestCatalogues.fixtureBytes(), false);

    assertThat(blueprint.getP4kUuid()).isEqualTo(ARMS);
    assertThat(blueprint.isP4kRecipe()).isTrue();
    assertThat(blueprint.getCraftTimeSeconds()).isEqualTo(120);
    assertThat(blueprint.getP4kCraftTimeSeconds()).isEqualTo(120);
    assertThat(blueprint.getCategory()).isNotNull();
    assertThat(blueprint.getCategory().getClassName()).isEqualTo("FPSArmours");
    assertThat(blueprint.getCategory().getName()).isNull();
    assertThat(blueprint.getRequiredGroupCount()).isNull();
    assertThat(blueprint.getDismantleEfficiency()).isEqualTo(0.5);
    assertThat(blueprint.getDismantleTimeSeconds()).isEqualTo(15);
    assertThat(line(blueprint, P4kTestCatalogues.ASLARITE).getMaterial()).isEqualTo(aslarite);
    assertThat(blueprint.getDismantleReturns())
        .extracting(r -> r.getP4kResourceGuid(), r -> r.getQuantityScu())
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(CASING_RESOURCE, 0.015),
            org.assertj.core.groups.Tuple.tuple(P4kTestCatalogues.ASLARITE, 0.01));
    BlueprintRequirementModifier casing =
        blueprint.getRequirementGroups().getFirst().getModifiers().getFirst();
    assertThat(casing.getPropertyKey()).isEqualTo("armor_damagemitigation");
    assertThat(casing.getLabel()).isEqualTo("Damage Mitigation");
    assertThat(casing.getValueKind()).isEqualTo(BlueprintModifierValueKind.MULTIPLIER);
    assertThat(casing.getModifierAtMinQuality()).isEqualTo(0.85);
    assertThat(casing.getModifierAtMaxQuality()).isEqualTo(1.15);
    assertThat(result.blueprintCategories()).isNotNull();
    assertThat(result.blueprintCategories().created()).isEqualTo(2);
  }

  @Test
  void anUnchangedRecipe_isNeitherReportedNorRewritten() {
    Blueprint blueprint = wikiBlueprint();
    runs.apply(P4kTestCatalogues.fixtureBytes(), false);
    BlueprintIngredient first = blueprint.getIngredients().getFirst();

    P4kImportResultDto second = runs.apply(P4kTestCatalogues.fixtureBytes(), false);

    assertThat(blueprint.getIngredients().getFirst()).isSameAs(first);
    assertThat(second.blueprintCategories().matched()).isEqualTo(2);
  }

  @Test
  void theAdminsMaterialForAResourceJoiningNoCommodity_survivesTheNextApply() {
    Material assigned = material("Casing Resource");
    Blueprint blueprint = wikiBlueprint();
    runs.apply(P4kTestCatalogues.fixtureBytes(), false);
    P4kReviewCase open =
        reviewCaseRepository
            .findByKindAndGuidAndReason(
                P4kKind.COMMODITY, CASING_RESOURCE, P4kReviewReason.RESOURCE_JOIN_NONE)
            .orElseThrow();
    assertThat(line(blueprint, CASING_RESOURCE).getMaterial()).isNull();

    reviewService.resolve(
        open.getId(), P4kReviewResolution.ASSIGN, null, assigned.getId(), open.getVersion(), ADMIN);
    runs.apply(P4kTestCatalogues.fixtureBytes(), false);
    runs.apply(P4kTestCatalogues.fixtureBytes(), false);

    assertThat(line(blueprint, CASING_RESOURCE).getMaterial()).isEqualTo(assigned);
  }

  @Test
  void theLiveChoose2Of3Blueprint_importsAsTwoOfThreeOptionGroups() throws Exception {
    ObjectNode tree = P4kTestCatalogues.tree();
    try (java.io.InputStream in =
        getClass().getResourceAsStream("/p4k/blueprint-choose-2-of-3-live.json")) {
      ((ArrayNode) tree.get("blueprints"))
          .add(tools.jackson.databind.json.JsonMapper.builder().build().readTree(in));
    }
    Blueprint legs = new Blueprint();
    legs.setScwikiUuid(UUID.randomUUID());
    legs.setScwikiKey("BP_CRAFT_thp_light_legs_01_01_01");
    blueprintRepository.save(legs);

    runs.apply(P4kTestCatalogues.bytes(tree), false);

    assertThat(legs.getRequiredGroupCount()).isEqualTo(2);
    assertThat(legs.getRequirementGroups())
        .extracting(
            BlueprintRequirementGroup::getGroupKey, BlueprintRequirementGroup::getRequiredCount)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("SEGMENT PANELING", 1),
            org.assertj.core.groups.Tuple.tuple("INSULATIVE LINER", 1),
            org.assertj.core.groups.Tuple.tuple("PANEL COVERING", 1));
    assertThat(legs.getIngredients())
        .extracting(BlueprintIngredient::getQuantityScu)
        .containsExactly(0.03, 0.02, 0.03);
  }
}
