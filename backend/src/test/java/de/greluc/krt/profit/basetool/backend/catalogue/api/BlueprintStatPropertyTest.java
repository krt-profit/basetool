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

package de.greluc.krt.profit.basetool.backend.catalogue.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Every {@code className} of the p4kx contract's table "From a gameplay property to a stat field"
 * resolves as the table says (REQ-P4K-030).
 */
class BlueprintStatPropertyTest {

  /** The table's rows with a stat field, as the contract lists them. */
  private static final Map<String, BlueprintStatProperty> WITH_FIELD = withField();

  /** The table's rows whose field is "none". */
  private static final Set<String> WITHOUT_FIELD =
      Set.of(
          "GPP_Weapon_Recoil_Kick",
          "GPP_Weapon_Recoil_Handling",
          "GPP_Weapon_Recoil_Smoothness",
          "GPP_Weapon_ReloadSpeed",
          "GPP_Weapon_Spread",
          "GPP_Crafter_CraftSpeed",
          "GPP_Crafter_DismantleEfficiency");

  private static Map<String, BlueprintStatProperty> withField() {
    Map<String, BlueprintStatProperty> rows = new LinkedHashMap<>();
    rows.put("GPP_Armor_DamageMitigation", BlueprintStatProperty.ARMOR_DAMAGE_MITIGATION);
    rows.put("GPP_Armor_TemperatureMin", BlueprintStatProperty.ARMOR_TEMPERATURE_MIN);
    rows.put("GPP_Armor_TemperatureMax", BlueprintStatProperty.ARMOR_TEMPERATURE_MAX);
    rows.put("GPP_Armor_RadiationCapacity", BlueprintStatProperty.ARMOR_RADIATION_CAPACITY);
    rows.put("GPP_Armor_RadiationDissipation", BlueprintStatProperty.ARMOR_RADIATION_DISSIPATION);
    rows.put("GPP_Health_MaxHealth", BlueprintStatProperty.HEALTH_MAX_HEALTH);
    rows.put("GPP_Weapon_Damage", BlueprintStatProperty.WEAPON_DAMAGE);
    rows.put("GPP_Weapon_FireRate", BlueprintStatProperty.WEAPON_FIRE_RATE);
    rows.put("GPP_ItemResource_PowerGeneration", BlueprintStatProperty.POWER_GENERATION);
    rows.put("GPP_ItemResource_CoolantGeneration", BlueprintStatProperty.COOLANT_GENERATION);
    rows.put("GPP_Shield_MaxHealth", BlueprintStatProperty.SHIELD_MAX_HEALTH);
    rows.put("GPP_Radar_MinAimAssistDistance", BlueprintStatProperty.RADAR_MIN_AIM_ASSIST_DISTANCE);
    rows.put("GPP_Radar_MaxAimAssistDistance", BlueprintStatProperty.RADAR_MAX_AIM_ASSIST_DISTANCE);
    rows.put("GPP_Quantum_Speed", BlueprintStatProperty.QUANTUM_SPEED);
    rows.put("GPP_Quantum_FuelRequirement", BlueprintStatProperty.QUANTUM_FUEL_REQUIREMENT);
    rows.put("GPP_Weapon_Tractor_Force", BlueprintStatProperty.TRACTOR_FORCE);
    rows.put("GPP_Weapon_Tractor_MaxDist", BlueprintStatProperty.TRACTOR_MAX_DISTANCE);
    rows.put(
        "GPP_Weapon_Tractor_FullStrengthDist",
        BlueprintStatProperty.TRACTOR_FULL_STRENGTH_DISTANCE);
    rows.put("GPP_Weapon_Tractor_MaxVolume", BlueprintStatProperty.TRACTOR_MAX_VOLUME);
    rows.put("GPP_Weapon_HullScraping_Radius", BlueprintStatProperty.HULL_SCRAPING_RADIUS);
    rows.put("GPP_Weapon_HullScraping_Speed", BlueprintStatProperty.HULL_SCRAPING_SPEED);
    rows.put("GPP_Weapon_HullScraping_Efficiency", BlueprintStatProperty.HULL_SCRAPING_EFFICIENCY);
    return rows;
  }

  @Test
  void theTableHasTheContractsTwentyNineRows() {
    assertThat(WITH_FIELD.size() + WITHOUT_FIELD.size()).isEqualTo(29);
  }

  @Test
  void everyClassNameWithAStatField_mapsToItsProperty() {
    WITH_FIELD.forEach((className, property) ->
        assertThat(BlueprintStatProperty.fromClassName(className)).contains(property));
  }

  @Test
  void everyClassNameWithoutAStatField_mapsToNoProperty() {
    WITHOUT_FIELD.forEach(
        className -> assertThat(BlueprintStatProperty.fromClassName(className)).isEmpty());
    assertThat(BlueprintStatProperty.fromClassName(null)).isEmpty();
  }

  @Test
  void everyPropertyIsARowOfTheTable() {
    assertThat(Arrays.stream(BlueprintStatProperty.values()).collect(Collectors.toSet()))
        .containsExactlyInAnyOrderElementsOf(WITH_FIELD.values());
  }

  @Test
  void thePropertyKeyIsTheClassNameInTheWikisForm() {
    assertThat(BlueprintStatProperty.fromKey("armor_radiationcapacity"))
        .contains(BlueprintStatProperty.ARMOR_RADIATION_CAPACITY);
    assertThat(BlueprintStatProperty.fromKey("weapon_tractor_fullstrengthdist"))
        .contains(BlueprintStatProperty.TRACTOR_FULL_STRENGTH_DISTANCE);
  }
}
