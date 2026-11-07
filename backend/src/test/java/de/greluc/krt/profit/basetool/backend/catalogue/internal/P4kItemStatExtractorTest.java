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

import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintStatProperty;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kItemStats;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link P4kItemStatExtractor} reads the stat field the p4kx contract's table names, on stat blocks
 * of the LIVE 4.10.2 export (REQ-P4K-030).
 */
class P4kItemStatExtractorTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static Map<BlueprintStatProperty, Double> extract(String json) {
    return P4kItemStatExtractor.extract(MAPPER.readValue(json, P4kItemStats.class));
  }

  @Test
  void aPersonalWeapon_takesTheProjectileDamageAndTheFirstFireRate() {
    Map<BlueprintStatProperty, Double> values =
        extract(
            """
            {"weapon":{"fireActions":[
              {"kind":"SWeaponActionFireRapidParams","fireRate":800.0,"damagePerSecond":null},
              {"kind":"SWeaponActionFireChargedParams","fireRate":900.0}],
             "ammo":{"damage":{"physical":0.0,"energy":13.0,"distortion":0.0,"thermal":0.0,
               "biochemical":0.0,"stun":0.0}}},
             "health":null}
            """);

    assertThat(values)
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                BlueprintStatProperty.WEAPON_DAMAGE, 13.0,
                BlueprintStatProperty.WEAPON_FIRE_RATE, 800.0));
  }

  @Test
  void aMiningLaser_takesThePowerOfItsMiningBeam() {
    Map<BlueprintStatProperty, Double> values =
        extract(
            """
            {"weapon":{"fireActions":[
              {"kind":"SWeaponActionFireBeamParams","hitType":"ElectricArc",
               "damagePerSecond":{"physical":0.0,"energy":2600.0}},
              {"kind":"SWeaponActionFireBeamParams","hitType":"Extraction",
               "damagePerSecond":{"physical":0.0,"energy":1295.0}}],
             "ammo":null},
             "health":3500.0}
            """);

    assertThat(values.get(BlueprintStatProperty.WEAPON_DAMAGE)).isEqualTo(2600.0);
    assertThat(values.get(BlueprintStatProperty.HEALTH_MAX_HEALTH)).isEqualTo(3500.0);
    assertThat(values).doesNotContainKey(BlueprintStatProperty.WEAPON_FIRE_RATE);
  }

  @Test
  void aCoolerAndAQuantumDrive_takeTheirBlockFields() {
    assertThat(extract("{\"cooler\":{\"coolingRate\":46.0},\"health\":550.0}"))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                BlueprintStatProperty.COOLANT_GENERATION, 46.0,
                BlueprintStatProperty.HEALTH_MAX_HEALTH, 550.0));
    assertThat(
            extract("{\"quantumDrive\":{\"driveSpeed\":223513700.0,\"fuelRequirement\":0.02289}}"))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                BlueprintStatProperty.QUANTUM_SPEED, 223513700.0,
                BlueprintStatProperty.QUANTUM_FUEL_REQUIREMENT, 0.02289));
  }

  @Test
  void aSalvageModifier_takesItsSalvageFields() {
    assertThat(
            extract(
                "{\"weaponModifier\":{\"salvage\":{\"radiusMultiplier\":3.5,"
                    + "\"salvageSpeedMultiplier\":0.15,\"extractionEfficiency\":0.9}}}"))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                BlueprintStatProperty.HULL_SCRAPING_RADIUS, 3.5,
                BlueprintStatProperty.HULL_SCRAPING_SPEED, 0.15,
                BlueprintStatProperty.HULL_SCRAPING_EFFICIENCY, 0.9));
  }

  @Test
  void zeroAndAbsentValues_andNoStats_yieldNoEntry() {
    assertThat(extract("{\"shield\":{\"maxHealth\":0.0},\"health\":null}")).isEmpty();
    assertThat(P4kItemStatExtractor.extract(null)).isEmpty();
  }
}
