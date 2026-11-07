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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * An APPLY writes the blueprint stat base values of every item a catalogue blueprint produces from
 * the item's {@code stats}, with the P4K as their source, over an SC Wiki value; an item no
 * catalogue blueprint produces keeps its Wiki values (REQ-P4K-030).
 */
@SpringBootTest
@Transactional
class P4kItemStatImportTest {

  /** The fixture's light armour arms, produced by a fixture blueprint. */
  private static final UUID LIGHT_ARMS = UUID.fromString("de20695c-eb2f-4b68-be6f-9806cbd205a7");

  /** The fixture blueprint that produces {@link #LIGHT_ARMS}. */
  private static final String LIGHT_ARMS_BLUEPRINT = "BP_CRAFT_vgl_armor_light_arms_01_01_20";

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
  void aProducedShield_storesItsP4kMaxHealthOverTheWikiValue() {
    GameItem shield = item(P4kTestCatalogues.SHIELD, "AllStop");
    stat(shield, BlueprintStatProperty.SHIELD_MAX_HEALTH, 3000.0);

    runs.apply(P4kTestCatalogues.bytes(withShieldBlueprint()), false);

    Map<BlueprintStatProperty, GameItemStat> stored = stats(shield);
    assertThat(stored.get(BlueprintStatProperty.SHIELD_MAX_HEALTH).getBaseValue())
        .isEqualTo(3168.0);
    assertThat(stored.get(BlueprintStatProperty.SHIELD_MAX_HEALTH).getSource())
        .isEqualTo(GameItemStatSource.P4K);
    assertThat(stored.get(BlueprintStatProperty.HEALTH_MAX_HEALTH).getBaseValue()).isEqualTo(210.0);
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
  void anItemNoCatalogueBlueprintProduces_keepsItsWikiValue() {
    GameItem shield = item(P4kTestCatalogues.SHIELD, "AllStop");
    stat(shield, BlueprintStatProperty.SHIELD_MAX_HEALTH, 3000.0);

    runs.apply(P4kTestCatalogues.fixtureBytes(), false);

    Map<BlueprintStatProperty, GameItemStat> stored = stats(shield);
    assertThat(stored).containsOnlyKeys(BlueprintStatProperty.SHIELD_MAX_HEALTH);
    assertThat(stored.get(BlueprintStatProperty.SHIELD_MAX_HEALTH).getBaseValue())
        .isEqualTo(3000.0);
    assertThat(stored.get(BlueprintStatProperty.SHIELD_MAX_HEALTH).getSource())
        .isEqualTo(GameItemStatSource.SCWIKI);
  }

  @Test
  void aPreview_writesNoStat() {
    GameItem arms = item(LIGHT_ARMS, "Light arms");

    runs.preview(P4kTestCatalogues.fixtureBytes());

    assertThat(stats(arms)).isEmpty();
  }

  /** The fixture with a copy of the light-arms blueprint that produces the shield instead. */
  private static ObjectNode withShieldBlueprint() {
    ObjectNode root = P4kTestCatalogues.tree();
    ArrayNode blueprints = (ArrayNode) root.get("blueprints");
    for (int i = 0; i < blueprints.size(); i++) {
      if (LIGHT_ARMS_BLUEPRINT.equals(blueprints.get(i).path("className").asString())) {
        ObjectNode copy = ((ObjectNode) blueprints.get(i)).deepCopy();
        copy.put("guid", UUID.randomUUID().toString());
        copy.put("className", "BP_CRAFT_SHLD_GODI_S01_AllStop_SCItem");
        copy.put("key", "BP_CRAFT_SHLD_GODI_S01_AllStop_SCItem");
        copy.put("producedItemGuid", P4kTestCatalogues.SHIELD.toString());
        blueprints.add(copy);
        return root;
      }
    }
    throw new IllegalStateException("Missing " + LIGHT_ARMS_BLUEPRINT);
  }

  private GameItem item(UUID guid, String name) {
    GameItem item = new GameItem();
    item.setName(name);
    item.setP4kUuid(guid);
    item.setSourceSystems(GameItemSourceSystem.BOTH);
    return gameItemRepository.save(item);
  }

  private void stat(GameItem item, BlueprintStatProperty property, double value) {
    GameItemStat stat = new GameItemStat();
    stat.setGameItem(item);
    stat.setProperty(property);
    stat.setBaseValue(value);
    stat.setSource(GameItemStatSource.SCWIKI);
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
