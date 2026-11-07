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
import static org.assertj.core.api.Assertions.within;

import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintStatProperty;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemSourceSystem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemStat;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemStatRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemStatSource;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobRepository;
import jakarta.persistence.EntityManager;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

/**
 * An APPLY writes the blueprint stat base values of every catalogue item the run matched from the
 * item's {@code stats}, with the P4K as their source, over an SC Wiki value; a P4K value an item no
 * longer carries is removed only from a complete catalogue (REQ-P4K-030).
 */
@SpringBootTest
@Transactional
class P4kItemStatImportTest {

  /** The fixture's light armour arms, produced by a fixture blueprint. */
  private static final UUID LIGHT_ARMS = UUID.fromString("de20695c-eb2f-4b68-be6f-9806cbd205a7");

  /** The cooler the fixture Titan's left cooler port holds; no fixture blueprint produces it. */
  private static final UUID BRACER = UUID.fromString("4ec3037b-4b6f-4837-b00d-cd9b4836a82a");

  @Autowired private P4kImportService service;
  @Autowired private P4kImportJobRepository jobRepository;
  @Autowired private GameItemRepository gameItemRepository;
  @Autowired private GameItemStatRepository statRepository;
  @Autowired private EntityManager entityManager;

  private P4kImportRuns runs;

  @BeforeEach
  void setUp() {
    runs = new P4kImportRuns(service, jobRepository);
  }

  @Test
  void aShieldNoBlueprintProduces_storesItsP4kMaxHealthOverTheWikiValue() {
    GameItem shield = item(P4kTestCatalogues.SHIELD, "AllStop");
    stat(shield, BlueprintStatProperty.SHIELD_MAX_HEALTH, 3000.0, GameItemStatSource.SCWIKI);

    runs.apply(P4kTestCatalogues.fixtureBytes(), false);

    Map<BlueprintStatProperty, GameItemStat> stored = stats(shield);
    assertThat(stored.get(BlueprintStatProperty.SHIELD_MAX_HEALTH).getBaseValue())
        .isEqualTo(3168.0);
    assertThat(stored.get(BlueprintStatProperty.SHIELD_MAX_HEALTH).getSource())
        .isEqualTo(GameItemStatSource.P4K);
    assertThat(stored.get(BlueprintStatProperty.HEALTH_MAX_HEALTH).getBaseValue()).isEqualTo(210.0);
  }

  @Test
  void aShipComponentOfAFixtureLoadout_storesItsCoolingRateAndHealth() {
    GameItem bracer = item(BRACER, "Bracer");

    runs.apply(P4kTestCatalogues.fixtureBytes(), false);

    Map<BlueprintStatProperty, GameItemStat> stored = stats(bracer);
    assertThat(stored.get(BlueprintStatProperty.COOLANT_GENERATION).getBaseValue()).isEqualTo(34.0);
    assertThat(stored.get(BlueprintStatProperty.HEALTH_MAX_HEALTH).getBaseValue()).isEqualTo(180.0);
    assertThat(stored.values()).allMatch(stat -> stat.getSource() == GameItemStatSource.P4K);
  }

  @Test
  void producedArmour_storesTheMitigationAsOneMinusTheMultiplier_andItsClothingValues() {
    GameItem arms = item(LIGHT_ARMS, "Light arms");

    runs.apply(P4kTestCatalogues.fixtureBytes(), false);

    Map<BlueprintStatProperty, GameItemStat> stored = stats(arms);
    assertThat(stored.get(BlueprintStatProperty.ARMOR_DAMAGE_MITIGATION).getBaseValue())
        .isCloseTo(0.2, within(1e-6));
    assertThat(stored.get(BlueprintStatProperty.ARMOR_TEMPERATURE_MIN).getBaseValue())
        .isEqualTo(-38.0);
    assertThat(stored.get(BlueprintStatProperty.ARMOR_TEMPERATURE_MAX).getBaseValue())
        .isEqualTo(68.0);
    assertThat(stored.get(BlueprintStatProperty.ARMOR_RADIATION_CAPACITY).getBaseValue())
        .isEqualTo(26000.0);
    assertThat(stored.get(BlueprintStatProperty.ARMOR_RADIATION_DISSIPATION).getBaseValue())
        .isEqualTo(145.8);
    assertThat(stored.values()).allMatch(stat -> stat.getSource() == GameItemStatSource.P4K);
  }

  @Test
  void aWikiValueOfAStatTheCatalogueDoesNotCarry_stays() {
    GameItem shield = item(P4kTestCatalogues.SHIELD, "AllStop");
    stat(shield, BlueprintStatProperty.WEAPON_DAMAGE, 12.0, GameItemStatSource.SCWIKI);

    runs.apply(P4kTestCatalogues.fixtureBytes(), false);

    GameItemStat damage = stats(shield).get(BlueprintStatProperty.WEAPON_DAMAGE);
    assertThat(damage.getBaseValue()).isEqualTo(12.0);
    assertThat(damage.getSource()).isEqualTo(GameItemStatSource.SCWIKI);
  }

  @Test
  void aP4kValueTheItemNoLongerCarries_isRemovedFromACompleteCatalogueOnly() {
    GameItem shield = item(P4kTestCatalogues.SHIELD, "AllStop");
    stat(shield, BlueprintStatProperty.QUANTUM_SPEED, 5.0, GameItemStatSource.P4K);
    ObjectNode partial = P4kTestCatalogues.tree();
    ((ObjectNode) partial.get("completeness").get("items")).put("status", "partial");

    runs.apply(P4kTestCatalogues.bytes(partial), false);
    assertThat(stats(shield)).containsKey(BlueprintStatProperty.QUANTUM_SPEED);

    runs.apply(P4kTestCatalogues.withChangeNumber("12913471"), false);
    assertThat(stats(shield)).doesNotContainKey(BlueprintStatProperty.QUANTUM_SPEED);
  }

  @Test
  void aPreview_writesNoStat() {
    GameItem arms = item(LIGHT_ARMS, "Light arms");

    runs.preview(P4kTestCatalogues.fixtureBytes());

    assertThat(stats(arms)).isEmpty();
  }

  private GameItem item(UUID guid, String name) {
    GameItem item = new GameItem();
    item.setName(name);
    item.setP4kUuid(guid);
    item.setSourceSystems(GameItemSourceSystem.BOTH);
    return gameItemRepository.save(item);
  }

  private void stat(
      GameItem item, BlueprintStatProperty property, double value, GameItemStatSource source) {
    GameItemStat stat = new GameItemStat();
    stat.setGameItem(item);
    stat.setProperty(property);
    stat.setBaseValue(value);
    stat.setSource(source);
    statRepository.save(stat);
  }

  private Map<BlueprintStatProperty, GameItemStat> stats(GameItem item) {
    entityManager.flush();
    entityManager.clear();
    Map<BlueprintStatProperty, GameItemStat> byProperty =
        new EnumMap<>(BlueprintStatProperty.class);
    statRepository
        .findByGameItemId(item.getId())
        .forEach(stat -> byProperty.put(stat.getProperty(), stat));
    return byProperty;
  }
}
