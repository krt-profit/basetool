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
 * by the SC Wiki {@code property_key} (REQ-DATA-027, ADR-0309). A property key missing here has no
 * known item field and keeps the multiplier-only display.
 */
@Getter
@RequiredArgsConstructor
public enum BlueprintStatProperty {
  /** Integrity of a ship component or weapon. */
  HEALTH_MAX_HEALTH("health_maxhealth", BlueprintStatUnit.NONE),
  /** Damage per shot of a weapon, or the maximum laser power of a mining laser. */
  WEAPON_DAMAGE("weapon_damage", BlueprintStatUnit.NONE),
  /** Fire rate of a personal weapon. */
  WEAPON_FIRE_RATE("weapon_firerate", BlueprintStatUnit.ROUNDS_PER_MINUTE),
  /** Maximum shield strength of a shield generator. */
  SHIELD_MAX_HEALTH("shield_maxhealth", BlueprintStatUnit.NONE),
  /** Power segments a power plant generates. */
  POWER_GENERATION("itemresource_powergeneration", BlueprintStatUnit.NONE),
  /** Coolant segments a cooler generates. */
  COOLANT_GENERATION("itemresource_coolantgeneration", BlueprintStatUnit.NONE),
  /** Standard-jump drive speed of a quantum drive. */
  QUANTUM_SPEED("quantum_speed", BlueprintStatUnit.METER_PER_SECOND),
  /** Quantum fuel requirement of a quantum drive. */
  QUANTUM_FUEL_REQUIREMENT("quantum_fuelrequirement", BlueprintStatUnit.NONE),
  /** Minimum aim-assist distance of a radar. */
  RADAR_MIN_AIM_ASSIST_DISTANCE("radar_minaimassistdistance", BlueprintStatUnit.METER),
  /** Maximum aim-assist distance of a radar. */
  RADAR_MAX_AIM_ASSIST_DISTANCE("radar_maxaimassistdistance", BlueprintStatUnit.METER),
  /** Maximum force of a tractor beam. */
  TRACTOR_FORCE("weapon_tractor_force", BlueprintStatUnit.NONE),
  /** Maximum distance of a tractor beam. */
  TRACTOR_MAX_DISTANCE("weapon_tractor_maxdist", BlueprintStatUnit.METER),
  /** Full-strength distance of a tractor beam. */
  TRACTOR_FULL_STRENGTH_DISTANCE("weapon_tractor_fullstrengthdist", BlueprintStatUnit.METER),
  /** Maximum volume a tractor beam can hold. */
  TRACTOR_MAX_VOLUME("weapon_tractor_maxvolume", BlueprintStatUnit.NONE),
  /** Radius multiplier of a salvage scraper module. */
  HULL_SCRAPING_RADIUS("weapon_hullscraping_radius", BlueprintStatUnit.NONE),
  /** Speed multiplier of a salvage scraper module. */
  HULL_SCRAPING_SPEED("weapon_hullscraping_speed", BlueprintStatUnit.NONE),
  /** Extraction efficiency of a salvage scraper module. */
  HULL_SCRAPING_EFFICIENCY("weapon_hullscraping_efficiency", BlueprintStatUnit.PERCENT),
  /** Physical damage mitigation of armour or clothing. */
  ARMOR_DAMAGE_MITIGATION("armor_damagemitigation", BlueprintStatUnit.PERCENT),
  /** Lowest tolerated temperature of armour or clothing. */
  ARMOR_TEMPERATURE_MIN("armor_temperaturemin", BlueprintStatUnit.CELSIUS),
  /** Highest tolerated temperature of armour or clothing. */
  ARMOR_TEMPERATURE_MAX("armor_temperaturemax", BlueprintStatUnit.CELSIUS),
  /** Radiation dissipation rate of armour. */
  ARMOR_RADIATION_DISSIPATION("armor_radiationdissipation", BlueprintStatUnit.NONE);

  private static final Map<String, BlueprintStatProperty> BY_KEY =
      Arrays.stream(values())
          .collect(
              Collectors.toUnmodifiableMap(
                  BlueprintStatProperty::getPropertyKey, Function.identity()));

  /** The SC Wiki {@code property_key} of the blueprint modifier. */
  @NotNull private final String propertyKey;

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
}
