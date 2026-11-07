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

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The blueprint modifier properties whose base value the catalogue stores per crafted item, keyed
 * by the SC Wiki {@code property_key} and by the P4K gameplay property's {@code className} of the
 * p4kx contract's table "From a gameplay property to a stat field" (REQ-P4K-030, ADR-0342). A
 * property missing here (the recoil, reload, spread and crafter properties) has no single stat
 * field and keeps the multiplier-only display.
 */
@Getter
@RequiredArgsConstructor
public enum BlueprintStatProperty {
  /** Integrity of a ship component or weapon. */
  HEALTH_MAX_HEALTH("health_maxhealth", "GPP_Health_MaxHealth", BlueprintStatUnit.NONE),
  /** Damage per shot of a weapon, or the maximum laser power of a mining laser. */
  WEAPON_DAMAGE("weapon_damage", "GPP_Weapon_Damage", BlueprintStatUnit.NONE),
  /** Fire rate of a personal weapon. */
  WEAPON_FIRE_RATE("weapon_firerate", "GPP_Weapon_FireRate", BlueprintStatUnit.ROUNDS_PER_MINUTE),
  /** Maximum shield strength of a shield generator. */
  SHIELD_MAX_HEALTH("shield_maxhealth", "GPP_Shield_MaxHealth", BlueprintStatUnit.NONE),
  /** Power segments a power plant generates. */
  POWER_GENERATION(
      "itemresource_powergeneration", "GPP_ItemResource_PowerGeneration", BlueprintStatUnit.NONE),
  /** Coolant segments a cooler generates. */
  COOLANT_GENERATION(
      "itemresource_coolantgeneration",
      "GPP_ItemResource_CoolantGeneration",
      BlueprintStatUnit.NONE),
  /** Standard-jump drive speed of a quantum drive. */
  QUANTUM_SPEED("quantum_speed", "GPP_Quantum_Speed", BlueprintStatUnit.METER_PER_SECOND),
  /** Quantum fuel requirement of a quantum drive. */
  QUANTUM_FUEL_REQUIREMENT(
      "quantum_fuelrequirement", "GPP_Quantum_FuelRequirement", BlueprintStatUnit.NONE),
  /** Minimum aim-assist distance of a radar. */
  RADAR_MIN_AIM_ASSIST_DISTANCE(
      "radar_minaimassistdistance", "GPP_Radar_MinAimAssistDistance", BlueprintStatUnit.METER),
  /** Maximum aim-assist distance of a radar. */
  RADAR_MAX_AIM_ASSIST_DISTANCE(
      "radar_maxaimassistdistance", "GPP_Radar_MaxAimAssistDistance", BlueprintStatUnit.METER),
  /** Maximum force of a tractor beam. */
  TRACTOR_FORCE("weapon_tractor_force", "GPP_Weapon_Tractor_Force", BlueprintStatUnit.NONE),
  /** Maximum distance of a tractor beam. */
  TRACTOR_MAX_DISTANCE(
      "weapon_tractor_maxdist", "GPP_Weapon_Tractor_MaxDist", BlueprintStatUnit.METER),
  /** Full-strength distance of a tractor beam. */
  TRACTOR_FULL_STRENGTH_DISTANCE(
      "weapon_tractor_fullstrengthdist",
      "GPP_Weapon_Tractor_FullStrengthDist",
      BlueprintStatUnit.METER),
  /** Maximum volume a tractor beam can hold. */
  TRACTOR_MAX_VOLUME(
      "weapon_tractor_maxvolume", "GPP_Weapon_Tractor_MaxVolume", BlueprintStatUnit.NONE),
  /** Radius multiplier of a salvage scraper module. */
  HULL_SCRAPING_RADIUS(
      "weapon_hullscraping_radius", "GPP_Weapon_HullScraping_Radius", BlueprintStatUnit.NONE),
  /** Speed multiplier of a salvage scraper module. */
  HULL_SCRAPING_SPEED(
      "weapon_hullscraping_speed", "GPP_Weapon_HullScraping_Speed", BlueprintStatUnit.NONE),
  /** Extraction efficiency of a salvage scraper module. */
  HULL_SCRAPING_EFFICIENCY(
      "weapon_hullscraping_efficiency",
      "GPP_Weapon_HullScraping_Efficiency",
      BlueprintStatUnit.PERCENT),
  /** Physical damage mitigation of armour or clothing, {@code 1 − multiplier}. */
  ARMOR_DAMAGE_MITIGATION(
      "armor_damagemitigation", "GPP_Armor_DamageMitigation", BlueprintStatUnit.PERCENT),
  /** Lowest tolerated temperature of armour or clothing. */
  ARMOR_TEMPERATURE_MIN(
      "armor_temperaturemin", "GPP_Armor_TemperatureMin", BlueprintStatUnit.CELSIUS),
  /** Highest tolerated temperature of armour or clothing. */
  ARMOR_TEMPERATURE_MAX(
      "armor_temperaturemax", "GPP_Armor_TemperatureMax", BlueprintStatUnit.CELSIUS),
  /** Radiation capacity of armour or clothing. */
  ARMOR_RADIATION_CAPACITY(
      "armor_radiationcapacity", "GPP_Armor_RadiationCapacity", BlueprintStatUnit.NONE),
  /** Radiation dissipation rate of armour. */
  ARMOR_RADIATION_DISSIPATION(
      "armor_radiationdissipation", "GPP_Armor_RadiationDissipation", BlueprintStatUnit.NONE);

  private static final Map<String, BlueprintStatProperty> BY_KEY =
      Arrays.stream(values())
          .collect(
              Collectors.toUnmodifiableMap(
                  BlueprintStatProperty::getPropertyKey, Function.identity()));

  private static final Map<String, BlueprintStatProperty> BY_CLASS_NAME =
      Arrays.stream(values())
          .collect(
              Collectors.toUnmodifiableMap(
                  BlueprintStatProperty::getClassName, Function.identity()));

  /** The SC Wiki {@code property_key} of the blueprint modifier. */
  @NotNull private final String propertyKey;

  /** The {@code className} of the P4K {@code gameplayProperties} entry. */
  @NotNull private final String className;

  /** The unit the stored base value is expressed in. */
  @NotNull private final BlueprintStatUnit unit;

  /**
   * Resolves a blueprint modifier's property key.
   *
   * @param propertyKey the SC Wiki property key, may be {@code null}
   * @return the property, or empty when the key has no stored base value
   */
  @NotNull
  public static Optional<BlueprintStatProperty> fromKey(@Nullable String propertyKey) {
    return propertyKey == null ? Optional.empty() : Optional.ofNullable(BY_KEY.get(propertyKey));
  }

  /**
   * Resolves a P4K gameplay property by the contract's fixed table.
   *
   * @param className the {@code className} of a {@code gameplayProperties} entry, may be {@code
   *     null}
   * @return the property, or empty when the gameplay property has no single stat field
   */
  @NotNull
  public static Optional<BlueprintStatProperty> fromClassName(@Nullable String className) {
    return className == null ? Optional.empty() : Optional.ofNullable(BY_CLASS_NAME.get(className));
  }
}
