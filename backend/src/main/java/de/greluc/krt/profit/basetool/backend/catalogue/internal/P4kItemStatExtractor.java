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
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kItemStats;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kItemStats.Damage;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kItemStats.FireAction;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Reads the base value of every {@link BlueprintStatProperty} from a catalogue item's {@code stats}
 * by the p4kx contract's table "From a gameplay property to a stat field" (REQ-P4K-030, ADR-0342).
 *
 * <p>A missing, zero or non-finite value yields no entry, as for the SC Wiki.
 */
final class P4kItemStatExtractor {

  /** The {@code hitType} of a mining laser's mining beam, whose power is the laser's damage. */
  private static final String MINING_BEAM = "ElectricArc";

  /** Non-instantiable holder of static helpers. */
  private P4kItemStatExtractor() {}

  /**
   * Extracts the base values the stats carry.
   *
   * @param stats the item's {@code stats}, may be {@code null}
   * @return property to base value, without the properties the stats have no value for
   */
  @NotNull
  static Map<BlueprintStatProperty, Double> extract(@Nullable P4kItemStats stats) {
    Map<BlueprintStatProperty, Double> values = new EnumMap<>(BlueprintStatProperty.class);
    if (stats == null) {
      return values;
    }
    for (BlueprintStatProperty property : BlueprintStatProperty.values()) {
      Double value = valueOf(property, stats);
      if (BlueprintStatValues.isStored(value)) {
        values.put(property, value);
      }
    }
    return values;
  }

  /**
   * Reads one property's base value from the field the contract's table names.
   *
   * @param property the blueprint stat
   * @param stats the item's stats
   * @return the base value, or {@code null} when the stats have none
   */
  @Nullable
  private static Double valueOf(
      @NotNull BlueprintStatProperty property, @NotNull P4kItemStats stats) {
    return switch (property) {
      case HEALTH_MAX_HEALTH -> stats.health();
      case WEAPON_DAMAGE -> weaponDamage(stats.weapon());
      case WEAPON_FIRE_RATE ->
          fireActions(stats.weapon())
              .map(FireAction::fireRate)
              .filter(Objects::nonNull)
              .findFirst()
              .orElse(null);
      case SHIELD_MAX_HEALTH -> read(stats.shield(), P4kItemStats.Shield::maxHealth);
      case POWER_GENERATION -> read(stats.powerPlant(), P4kItemStats.PowerPlant::powerOutput);
      case COOLANT_GENERATION -> read(stats.cooler(), P4kItemStats.Cooler::coolingRate);
      case QUANTUM_SPEED -> read(stats.quantumDrive(), P4kItemStats.QuantumDrive::driveSpeed);
      case QUANTUM_FUEL_REQUIREMENT ->
          read(stats.quantumDrive(), P4kItemStats.QuantumDrive::fuelRequirement);
      case RADAR_MIN_AIM_ASSIST_DISTANCE ->
          read(stats.radar(), P4kItemStats.Radar::aimAssistDistanceMin);
      case RADAR_MAX_AIM_ASSIST_DISTANCE ->
          read(stats.radar(), P4kItemStats.Radar::aimAssistDistanceMax);
      case TRACTOR_FORCE -> read(stats.tractorBeam(), P4kItemStats.TractorBeam::maxForce);
      case TRACTOR_MAX_DISTANCE -> read(stats.tractorBeam(), P4kItemStats.TractorBeam::maxDistance);
      case TRACTOR_FULL_STRENGTH_DISTANCE ->
          read(stats.tractorBeam(), P4kItemStats.TractorBeam::fullStrengthDistance);
      case TRACTOR_MAX_VOLUME -> read(stats.tractorBeam(), P4kItemStats.TractorBeam::maxVolume);
      case HULL_SCRAPING_RADIUS -> salvage(stats, P4kItemStats.Salvage::radiusMultiplier);
      case HULL_SCRAPING_SPEED -> salvage(stats, P4kItemStats.Salvage::salvageSpeedMultiplier);
      case HULL_SCRAPING_EFFICIENCY -> salvage(stats, P4kItemStats.Salvage::extractionEfficiency);
      case ARMOR_DAMAGE_MITIGATION -> BlueprintStatValues.mitigation(physicalMultiplier(stats));
      case ARMOR_TEMPERATURE_MIN ->
          read(stats.clothing(), P4kItemStats.Clothing::temperatureResistanceMin);
      case ARMOR_TEMPERATURE_MAX ->
          read(stats.clothing(), P4kItemStats.Clothing::temperatureResistanceMax);
      case ARMOR_RADIATION_CAPACITY ->
          read(stats.clothing(), P4kItemStats.Clothing::radiationCapacity);
      case ARMOR_RADIATION_DISSIPATION ->
          read(stats.clothing(), P4kItemStats.Clothing::radiationDissipationRate);
    };
  }

  /**
   * Reads a weapon's damage: the damage of one projectile of a gun, else the power of a mining
   * laser's mining beam, each summed over the damage types.
   *
   * @param weapon the weapon block, may be {@code null}
   * @return the damage, or {@code null} when the block states none
   */
  @Nullable
  private static Double weaponDamage(@Nullable P4kItemStats.Weapon weapon) {
    Double projectile =
        weapon == null || weapon.ammo() == null ? null : sum(weapon.ammo().damage());
    if (BlueprintStatValues.isStored(projectile)) {
      return projectile;
    }
    return fireActions(weapon)
        .filter(action -> MINING_BEAM.equals(action.hitType()))
        .map(action -> sum(action.damagePerSecond()))
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);
  }

  /**
   * Sums the stated damage types.
   *
   * @param damage the damage object, may be {@code null}
   * @return the sum, or {@code null} when no type is stated
   */
  @Nullable
  private static Double sum(@Nullable Damage damage) {
    if (damage == null) {
      return null;
    }
    List<Double> amounts =
        Stream.of(
                damage.physical(),
                damage.energy(),
                damage.distortion(),
                damage.thermal(),
                damage.biochemical(),
                damage.stun())
            .filter(Objects::nonNull)
            .toList();
    return amounts.isEmpty() ? null : amounts.stream().mapToDouble(Double::doubleValue).sum();
  }

  @NotNull
  private static Stream<FireAction> fireActions(@Nullable P4kItemStats.Weapon weapon) {
    return weapon == null || weapon.fireActions() == null
        ? Stream.empty()
        : weapon.fireActions().stream().filter(Objects::nonNull);
  }

  @Nullable
  private static Double physicalMultiplier(@NotNull P4kItemStats stats) {
    P4kItemStats.Armor armor = stats.armor();
    if (armor == null || armor.damageResistance() == null) {
      return null;
    }
    return read(armor.damageResistance().physical(), P4kItemStats.Resistance::multiplier);
  }

  @Nullable
  private static Double salvage(
      @NotNull P4kItemStats stats, @NotNull Function<P4kItemStats.Salvage, Double> field) {
    P4kItemStats.WeaponModifier modifier = stats.weaponModifier();
    return modifier == null ? null : read(modifier.salvage(), field);
  }

  @Nullable
  private static <B> Double read(@Nullable B block, @NotNull Function<B, Double> field) {
    return block == null ? null : field.apply(block);
  }
}
