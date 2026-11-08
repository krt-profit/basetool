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

import de.greluc.krt.profit.basetool.backend.catalogue.api.CatalogueMetricNames;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemSourceSystem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Manufacturer;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipComponentClass;
import de.greluc.krt.profit.basetool.backend.catalogue.api.UexCategory;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

/**
 * The P4K leads a matched item's name, manufacturer, type, sub-type, size, grade, class and mass,
 * keeps its own value of each, and leaves the columns it does not carry alone (REQ-P4K-017…019).
 */
@SpringBootTest
@Transactional
class P4kItemPrecedenceTest {

  /** The fixture's Gorgon Defender Industries, the shield's manufacturer. */
  private static final UUID GORGON = UUID.fromString("57bfcf3d-13f6-4c2c-b7c7-6bf8eacb8a24");

  /** The fixture's {@code player} {@code WeaponPersonal} of grade 1 without a class. */
  private static final UUID SMG = UUID.fromString("00335844-8889-4c40-b4c5-e4d8496838cf");

  @Autowired private P4kImportService service;
  @Autowired private P4kImportJobRepository jobRepository;
  @Autowired private GameItemRepository gameItemRepository;
  @Autowired private ManufacturerRepository manufacturerRepository;
  @Autowired private UexCategoryRepository uexCategoryRepository;
  @Autowired private P4kImportChangeRepository changeRepository;
  @Autowired private MeterRegistry meterRegistry;
  @Autowired private P4kCatalogueBusinessGauges businessGauges;
  @Autowired private EntityManager entityManager;

  private P4kImportRuns runs;

  @BeforeEach
  void setUp() {
    runs = new P4kImportRuns(service, jobRepository);
  }

  @Test
  void aMatchedShield_takesEveryLedValueFromTheP4k_andKeepsTheRawGrade() {
    Manufacturer gorgon = new Manufacturer();
    gorgon.setName("Gorgon Defender Industries");
    gorgon.setAbbreviation("GODI");
    gorgon.setP4kUuid(GORGON);
    manufacturerRepository.save(gorgon);
    GameItem shield = item(P4kTestCatalogues.SHIELD, "Wiki AllStop");
    shield.setSizeClass(3);
    shield.setGrade("A");
    shield.setItemClass(ShipComponentClass.STEALTH);
    shield.setMass(99.0);
    shield.setWikiType("ShieldGenerator");
    gameItemRepository.save(shield);

    runs.apply(P4kTestCatalogues.fixtureBytes(), false);
    GameItem stored = reload(shield);

    assertThat(stored.getName()).isEqualTo("AllStop");
    assertThat(stored.getP4kName()).isEqualTo("AllStop");
    assertThat(stored.getManufacturer()).isEqualTo(gorgon);
    assertThat(stored.getP4kManufacturer()).isEqualTo(gorgon);
    assertThat(stored.getWikiType()).isEqualTo("Shield");
    assertThat(stored.getP4kType()).isEqualTo("Shield");
    assertThat(stored.getWikiSubType()).isEqualTo("UNDEFINED");
    assertThat(stored.getSizeClass()).isEqualTo(1);
    assertThat(stored.getP4kSize()).isEqualTo(1);
    assertThat(stored.getGrade()).isEqualTo("C");
    assertThat(stored.getP4kGradeRaw()).isEqualTo(3);
    assertThat(stored.getItemClass()).isEqualTo(ShipComponentClass.MILITARY);
    assertThat(stored.getP4kItemClass()).isEqualTo(ShipComponentClass.MILITARY);
    assertThat(stored.getMass()).isEqualTo(120.0);
    assertThat(stored.getP4kMass()).isEqualTo(120.0);
  }

  @Test
  void aWeaponOfGradeOne_keepsItsWikiGradeAndClass_andStoresTheRawGrade() {
    GameItem smg = item(SMG, "Custodian SMG");
    smg.setGrade("B");
    smg.setItemClass(ShipComponentClass.STEALTH);
    gameItemRepository.save(smg);

    runs.apply(P4kTestCatalogues.fixtureBytes(), false);
    GameItem stored = reload(smg);

    assertThat(stored.getGrade()).isEqualTo("B");
    assertThat(stored.getP4kGradeRaw()).isEqualTo(1);
    assertThat(stored.getItemClass()).isEqualTo(ShipComponentClass.STEALTH);
    assertThat(stored.getP4kItemClass()).isNull();
  }

  @Test
  void anApply_leavesTheDimensionsAndTheUexCategoryAlone() {
    UexCategory category = new UexCategory();
    category.setId(9_350);
    category.setType("item");
    category.setSection("Systems");
    category.setName("Shields");
    category.setIsGameRelated(true);
    category.setIsMining(false);
    category = uexCategoryRepository.save(category);
    GameItem shield = item(P4kTestCatalogues.SHIELD, "Sized shield");
    shield.setDimensionX(1.5);
    shield.setDimensionY(2.5);
    shield.setDimensionZ(3.5);
    shield.setUexCategory(category);
    gameItemRepository.save(shield);

    runs.apply(P4kTestCatalogues.fixtureBytes(), false);
    GameItem stored = reload(shield);

    assertThat(List.of(stored.getDimensionX(), stored.getDimensionY(), stored.getDimensionZ()))
        .containsExactly(1.5, 2.5, 3.5);
    assertThat(stored.getUexCategory().getId()).isEqualTo(9_350);
  }

  @Test
  void anUnknownGrade_countsOnce_keepsTheRawValue_andGivesNoLetter() {
    GameItem cooler = item(P4kTestCatalogues.COOLER, "Snowpack");
    cooler.setGrade("B");
    gameItemRepository.save(cooler);
    ObjectNode root = P4kTestCatalogues.tree();
    P4kTestCatalogues.record(root, "items", P4kTestCatalogues.COOLER).put("grade", 6);
    double before = counter(CatalogueMetricNames.CATALOGUE_ITEM_GRADE_UNKNOWN);

    P4kImportResultDto result = runs.apply(P4kTestCatalogues.bytes(root), false);
    GameItem stored = reload(cooler);

    assertThat(counter(CatalogueMetricNames.CATALOGUE_ITEM_GRADE_UNKNOWN) - before).isEqualTo(1.0);
    assertThat(stored.getP4kGradeRaw()).isEqualTo(6);
    assertThat(stored.getGrade()).isEqualTo("B");
    assertThat(P4kImportRuns.report(changeRepository, result.runId()))
        .filteredOn(c -> c.getChangeType() == P4kChangeType.UNKNOWN_VALUE)
        .filteredOn(c -> "grade".equals(c.getFieldName()))
        .singleElement()
        .satisfies(c -> assertThat(c.getNewValue()).isEqualTo("6"));
  }

  @Test
  void anUnknownClass_countsOnce_andLeavesTheStoredClass() {
    GameItem shield = item(P4kTestCatalogues.SHIELD, "Classless shield");
    shield.setItemClass(ShipComponentClass.STEALTH);
    gameItemRepository.save(shield);
    ObjectNode root = P4kTestCatalogues.tree();
    P4kTestCatalogues.record(root, "items", P4kTestCatalogues.SHIELD).put("itemClass", "Prototype");
    double before = counter(CatalogueMetricNames.CATALOGUE_ITEM_CLASS_UNKNOWN);

    runs.apply(P4kTestCatalogues.bytes(root), false);
    GameItem stored = reload(shield);

    assertThat(counter(CatalogueMetricNames.CATALOGUE_ITEM_CLASS_UNKNOWN) - before).isEqualTo(1.0);
    assertThat(stored.getItemClass()).isEqualTo(ShipComponentClass.STEALTH);
    assertThat(stored.getP4kItemClass()).isNull();
  }

  @Test
  void anUnknownGradeAndClass_moveTheLastRunGauges() {
    gameItemRepository.save(item(P4kTestCatalogues.COOLER, "Unknown-grade cooler"));
    gameItemRepository.save(item(P4kTestCatalogues.SHIELD, "Unknown-class shield"));
    ObjectNode root = P4kTestCatalogues.tree();
    P4kTestCatalogues.record(root, "items", P4kTestCatalogues.COOLER).put("grade", 6);
    P4kTestCatalogues.record(root, "items", P4kTestCatalogues.SHIELD).put("itemClass", "Prototype");
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    businessGauges.register(registry);

    runs.apply(P4kTestCatalogues.bytes(root), false);
    businessGauges.sample();

    assertThat(
            registry.get(CatalogueMetricNames.P4K_IMPORT_LAST_RUN_UNKNOWN_GRADES).gauge().value())
        .isEqualTo(1.0);
    assertThat(
            registry.get(CatalogueMetricNames.P4K_IMPORT_LAST_RUN_UNKNOWN_CLASSES).gauge().value())
        .isEqualTo(1.0);
  }

  @Test
  void aNullValueInALaterRun_clearsTheP4kValue_butNotTheDisplayedOne() {
    GameItem shield = item(P4kTestCatalogues.SHIELD, "Shrinking shield");
    gameItemRepository.save(shield);
    runs.apply(P4kTestCatalogues.fixtureBytes(), false);
    ObjectNode root = P4kTestCatalogues.tree();
    ObjectNode record = P4kTestCatalogues.record(root, "items", P4kTestCatalogues.SHIELD);
    record.putNull("size");
    record.putNull("mass");
    record.putNull("itemClass");

    runs.apply(P4kTestCatalogues.bytes(root), false);
    GameItem stored = reload(shield);

    assertThat(stored.getSizeClass()).isEqualTo(1);
    assertThat(stored.getP4kSize()).isNull();
    assertThat(stored.getMass()).isEqualTo(120.0);
    assertThat(stored.getP4kMass()).isNull();
    assertThat(stored.getItemClass()).isEqualTo(ShipComponentClass.MILITARY);
    assertThat(stored.getP4kItemClass()).isNull();
  }

  @Test
  void aPreview_writesNoValue() {
    GameItem shield = item(P4kTestCatalogues.SHIELD, "Previewed shield");
    shield.setSizeClass(3);
    gameItemRepository.save(shield);

    runs.preview(P4kTestCatalogues.fixtureBytes());
    GameItem stored = reload(shield);

    assertThat(stored.getSizeClass()).isEqualTo(3);
    assertThat(stored.getP4kSize()).isNull();
    assertThat(stored.getP4kGradeRaw()).isNull();
  }

  private static GameItem item(UUID guid, String name) {
    GameItem item = new GameItem();
    item.setName(name);
    item.setP4kUuid(guid);
    item.setSourceSystems(GameItemSourceSystem.BOTH);
    return item;
  }

  private GameItem reload(GameItem item) {
    entityManager.flush();
    entityManager.clear();
    return gameItemRepository.findById(item.getId()).orElseThrow();
  }

  private double counter(String name) {
    return meterRegistry.counter(name).count();
  }
}
