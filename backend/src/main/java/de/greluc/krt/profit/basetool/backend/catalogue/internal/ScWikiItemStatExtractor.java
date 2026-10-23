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

import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintStatProperty;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiItemDto;
import java.util.EnumMap;
import java.util.Map;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Reads the base value of every {@link BlueprintStatProperty} from an SC Wiki item payload
 * (REQ-DATA-027, ADR-0309).
 *
 * <p>A missing, non-numeric or zero value yields no entry: quality cannot change a zero base.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class ScWikiItemStatExtractor {

  /**
   * Extracts the base values the payload carries.
   *
   * @param dto the SC Wiki item payload, a detail response or a kind-endpoint list row
   * @return property to base value, without the properties the payload has no value for
   */
  @NotNull
  static Map<BlueprintStatProperty, Double> extract(@NotNull ScWikiItemDto dto) {
    Map<BlueprintStatProperty, Double> values = new EnumMap<>(BlueprintStatProperty.class);
    for (BlueprintStatProperty property : BlueprintStatProperty.values()) {
      Double value = valueOf(property, dto);
      if (value != null && value != 0.0 && Double.isFinite(value)) {
        values.put(property, value);
      }
    }
    return values;
  }

  /**
   * Reads one property's base value.
   *
   * @param property the blueprint stat
   * @param dto the SC Wiki item payload
   * @return the base value, or {@code null} when the payload has none
   */
  @Nullable
  private static Double valueOf(
      @NotNull BlueprintStatProperty property, @NotNull ScWikiItemDto dto) {
    return switch (property) {
      case HEALTH_MAX_HEALTH -> number(dto.durability(), "health");
      case WEAPON_DAMAGE ->
          firstOf(
              number(dto.miningLaser(), "laser_power", "max"),
              number(dto.personalWeapon(), "damage_per_shot"),
              number(dto.vehicleWeapon(), "damage_per_shot"));
      case WEAPON_FIRE_RATE -> number(dto.personalWeapon(), "rpm");
      case SHIELD_MAX_HEALTH -> number(dto.shield(), "max_health");
      case POWER_GENERATION -> number(dto.powerPlant(), "power_segment_generation");
      case COOLANT_GENERATION -> number(dto.cooler(), "coolant_segment_generation");
      case QUANTUM_SPEED -> number(dto.quantumDrive(), "standard_jump", "drive_speed");
      case QUANTUM_FUEL_REQUIREMENT -> number(dto.quantumDrive(), "quantum_fuel_requirement");
      case RADAR_MIN_AIM_ASSIST_DISTANCE ->
          number(dto.radar(), "aim_assist", "distance_min_assignment");
      case RADAR_MAX_AIM_ASSIST_DISTANCE ->
          number(dto.radar(), "aim_assist", "distance_max_assignment");
      case TRACTOR_FORCE -> number(dto.tractorBeam(), "max_force");
      case TRACTOR_MAX_DISTANCE -> number(dto.tractorBeam(), "max_distance");
      case TRACTOR_FULL_STRENGTH_DISTANCE -> number(dto.tractorBeam(), "full_strength_distance");
      case TRACTOR_MAX_VOLUME -> number(dto.tractorBeam(), "max_volume");
      case HULL_SCRAPING_RADIUS -> number(dto.weaponModifier(), "salvage", "radius_multiplier");
      case HULL_SCRAPING_SPEED ->
          number(dto.weaponModifier(), "salvage", "salvage_speed_multiplier");
      case HULL_SCRAPING_EFFICIENCY ->
          number(dto.weaponModifier(), "salvage", "extraction_efficiency");
      case ARMOR_DAMAGE_MITIGATION -> mitigation(dto);
      case ARMOR_TEMPERATURE_MIN ->
          firstOf(
              number(dto.temperatureResistance(), "min"),
              number(dto.clothing(), "temp_resistance_min"));
      case ARMOR_TEMPERATURE_MAX ->
          firstOf(
              number(dto.temperatureResistance(), "max"),
              number(dto.clothing(), "temp_resistance_max"));
      case ARMOR_RADIATION_DISSIPATION ->
          number(dto.radiationResistance(), "radiation_dissipation_rate");
    };
  }

  /**
   * Derives the physical damage mitigation from the damage multiplier the armour lets through.
   *
   * @param dto the SC Wiki item payload
   * @return {@code 1 - multiplier}, or {@code null} when neither block carries the multiplier
   */
  @Nullable
  private static Double mitigation(@NotNull ScWikiItemDto dto) {
    Double multiplier =
        firstOf(
            number(dto.clothing(), "damage_resistance", "physical", "multiplier"),
            number(dto.suitArmor(), "damage_resistance", "physical", "multiplier"));
    return multiplier == null ? null : 1.0 - multiplier;
  }

  /**
   * Walks a path below a block and reads a number.
   *
   * @param block the payload block, may be {@code null}
   * @param path the field names below the block
   * @return the number, or {@code null} when the block, a field or a numeric value is missing
   */
  @Nullable
  private static Double number(@Nullable JsonNode block, @NotNull String... path) {
    JsonNode node = block;
    for (String field : path) {
      if (node == null || !node.isObject()) {
        return null;
      }
      node = node.get(field);
    }
    return node != null && node.isNumber() ? node.doubleValue() : null;
  }

  /**
   * Picks the first present candidate.
   *
   * @param candidates the values in order of preference
   * @return the first non-null value, or {@code null} when all are absent
   */
  @Nullable
  private static Double firstOf(@Nullable Double... candidates) {
    for (Double candidate : candidates) {
      if (candidate != null) {
        return candidate;
      }
    }
    return null;
  }
}
