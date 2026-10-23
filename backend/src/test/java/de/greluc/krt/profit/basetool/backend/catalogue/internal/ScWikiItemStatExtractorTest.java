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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintStatProperty;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiItemDto;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Unit tests for {@link ScWikiItemStatExtractor} over SC Wiki item payload shapes. */
class ScWikiItemStatExtractorTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static Map<BlueprintStatProperty, Double> extract(String json) {
    return ScWikiItemStatExtractor.extract(MAPPER.readValue(json, ScWikiItemDto.class));
  }

  @Test
  void vehicleWeapon_yieldsIntegrityAndDamagePerShot() {
    Map<BlueprintStatProperty, Double> values =
        extract(
            """
            {"uuid":"26838ca7-418a-47d2-8429-7339ebbb8993","name":"Omnisky III Cannon",
             "is_craftable":true,
             "durability":{"health":550,"resistance":{"distortion":1}},
             "vehicle_weapon":{"damage_per_shot":97.2,"rpm":150}}
            """);

    assertEquals(
        Map.of(
            BlueprintStatProperty.HEALTH_MAX_HEALTH, 550.0,
            BlueprintStatProperty.WEAPON_DAMAGE, 97.2),
        values);
  }

  @Test
  void personalWeapon_yieldsDamageAndFireRate() {
    Map<BlueprintStatProperty, Double> values =
        extract(
            """
            {"personal_weapon":{"damage_per_shot":14.5,"rpm":650,"spread":{"min":2}}}
            """);

    assertEquals(14.5, values.get(BlueprintStatProperty.WEAPON_DAMAGE));
    assertEquals(650.0, values.get(BlueprintStatProperty.WEAPON_FIRE_RATE));
  }

  @Test
  void miningLaser_prefersLaserPowerOverTheZeroDamagePerShot() {
    Map<BlueprintStatProperty, Double> values =
        extract(
            """
            {"mining_laser":{"laser_power":{"min":780,"max":3900}},
             "vehicle_weapon":{"damage_per_shot":0},
             "durability":{"health":8000}}
            """);

    assertEquals(3900.0, values.get(BlueprintStatProperty.WEAPON_DAMAGE));
    assertEquals(8000.0, values.get(BlueprintStatProperty.HEALTH_MAX_HEALTH));
  }

  @Test
  void armour_yieldsMitigationAsFractionAndTheTemperatureRange() {
    Map<BlueprintStatProperty, Double> values =
        extract(
            """
            {"clothing":{"damage_resistance":{"physical":{"multiplier":0.7,"threshold":0}},
                         "temp_resistance_min":-61,"temp_resistance_max":91},
             "temperature_resistance":{"min":-60,"max":90},
             "radiation_resistance":{"radiation_dissipation_rate":145.8}}
            """);

    assertEquals(0.3, values.get(BlueprintStatProperty.ARMOR_DAMAGE_MITIGATION), 1e-9);
    assertEquals(-60.0, values.get(BlueprintStatProperty.ARMOR_TEMPERATURE_MIN));
    assertEquals(90.0, values.get(BlueprintStatProperty.ARMOR_TEMPERATURE_MAX));
    assertEquals(145.8, values.get(BlueprintStatProperty.ARMOR_RADIATION_DISSIPATION));
  }

  @Test
  void clothing_fallsBackToTheClothingBlockForTemperatureAndSuitArmourForMitigation() {
    Map<BlueprintStatProperty, Double> values =
        extract(
            """
            {"clothing":{"temp_resistance_min":-20,"temp_resistance_max":40},
             "suit_armor":{"damage_resistance":{"physical":{"multiplier":0.9}}}}
            """);

    assertEquals(-20.0, values.get(BlueprintStatProperty.ARMOR_TEMPERATURE_MIN));
    assertEquals(40.0, values.get(BlueprintStatProperty.ARMOR_TEMPERATURE_MAX));
    assertEquals(0.1, values.get(BlueprintStatProperty.ARMOR_DAMAGE_MITIGATION), 1e-9);
  }

  @Test
  void shipComponents_yieldTheirGenerationSpeedRangeAndShieldValues() {
    Map<BlueprintStatProperty, Double> values =
        extract(
            """
            {"shield":{"max_health":194400},
             "power_plant":{"power_segment_generation":16},
             "cooler":{"coolant_segment_generation":34},
             "quantum_drive":{"quantum_fuel_requirement":0.00588,
                              "standard_jump":{"drive_speed":263040000}},
             "radar":{"aim_assist":{"distance_min_assignment":780,
                                    "distance_max_assignment":1028.5}}}
            """);

    assertEquals(194400.0, values.get(BlueprintStatProperty.SHIELD_MAX_HEALTH));
    assertEquals(16.0, values.get(BlueprintStatProperty.POWER_GENERATION));
    assertEquals(34.0, values.get(BlueprintStatProperty.COOLANT_GENERATION));
    assertEquals(0.00588, values.get(BlueprintStatProperty.QUANTUM_FUEL_REQUIREMENT));
    assertEquals(263040000.0, values.get(BlueprintStatProperty.QUANTUM_SPEED));
    assertEquals(780.0, values.get(BlueprintStatProperty.RADAR_MIN_AIM_ASSIST_DISTANCE));
    assertEquals(1028.5, values.get(BlueprintStatProperty.RADAR_MAX_AIM_ASSIST_DISTANCE));
  }

  @Test
  void tractorBeamAndSalvageModule_yieldTheirValues() {
    Map<BlueprintStatProperty, Double> values =
        extract(
            """
            {"tractor_beam":{"max_force":2100000,"max_distance":200,
                             "full_strength_distance":100,"max_volume":300000},
             "weapon_modifier":{"salvage":{"salvage_speed_multiplier":0.05,
                                           "radius_multiplier":6,"extraction_efficiency":0.6}}}
            """);

    assertEquals(2100000.0, values.get(BlueprintStatProperty.TRACTOR_FORCE));
    assertEquals(200.0, values.get(BlueprintStatProperty.TRACTOR_MAX_DISTANCE));
    assertEquals(100.0, values.get(BlueprintStatProperty.TRACTOR_FULL_STRENGTH_DISTANCE));
    assertEquals(300000.0, values.get(BlueprintStatProperty.TRACTOR_MAX_VOLUME));
    assertEquals(0.05, values.get(BlueprintStatProperty.HULL_SCRAPING_SPEED));
    assertEquals(6.0, values.get(BlueprintStatProperty.HULL_SCRAPING_RADIUS));
    assertEquals(0.6, values.get(BlueprintStatProperty.HULL_SCRAPING_EFFICIENCY));
  }

  @Test
  void listRowWithoutStatBlocks_yieldsNothing() {
    assertTrue(extract("{\"name\":\"Hadanite\",\"is_craftable\":false}").isEmpty());
  }

  @Test
  void zeroAndNonNumericValues_areSkipped() {
    Map<BlueprintStatProperty, Double> values =
        extract(
            """
            {"durability":{"health":0},"personal_weapon":{"damage_per_shot":"n/a"},
             "shield":["not","an","object"]}
            """);

    assertTrue(values.isEmpty());
  }
}
