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

package de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * The {@code stats} of a catalogue item, bound to the fields the contract's table "From a gameplay
 * property to a stat field" names; every other field is skipped. Each value is the stored field in
 * the stored unit, {@code null} when the Archive does not state it.
 *
 * @param health {@code SHealthComponentParams.Health}
 * @param weapon the weapon block
 * @param shield the shield block
 * @param quantumDrive the quantum drive block
 * @param powerPlant the power plant block
 * @param cooler the cooler block
 * @param armor the suit armour block
 * @param clothing the clothing block
 * @param radar the radar block
 * @param tractorBeam the tractor beam block
 * @param weaponModifier the attachable weapon modifier block
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record P4kItemStats(
    @Nullable Double health,
    @Nullable Weapon weapon,
    @Nullable Shield shield,
    @Nullable QuantumDrive quantumDrive,
    @Nullable PowerPlant powerPlant,
    @Nullable Cooler cooler,
    @Nullable Armor armor,
    @Nullable Clothing clothing,
    @Nullable Radar radar,
    @Nullable TractorBeam tractorBeam,
    @Nullable WeaponModifier weaponModifier) {

  /**
   * The weapon block.
   *
   * @param fireActions the fire modes in stored order
   * @param ammo the ammunition the magazine links to
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Weapon(@Nullable List<FireAction> fireActions, @Nullable Ammo ammo) {}

  /**
   * One fire mode.
   *
   * @param fireRate rounds per minute
   * @param hitType {@code ElectricArc} for the mining beam of a mining laser, another value or
   *     {@code null}
   * @param damagePerSecond the beam's damage per second, the power of a mining laser
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record FireAction(
      @Nullable Double fireRate, @Nullable String hitType, @Nullable Damage damagePerSecond) {}

  /**
   * The ammunition of a weapon.
   *
   * @param damage the damage of one projectile
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Ammo(@Nullable Damage damage) {}

  /**
   * A damage object, one amount per damage type.
   *
   * @param physical {@code DamagePhysical}
   * @param energy {@code DamageEnergy}
   * @param distortion {@code DamageDistortion}
   * @param thermal {@code DamageThermal}
   * @param biochemical {@code DamageBiochemical}
   * @param stun {@code DamageStun}
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Damage(
      @Nullable Double physical,
      @Nullable Double energy,
      @Nullable Double distortion,
      @Nullable Double thermal,
      @Nullable Double biochemical,
      @Nullable Double stun) {}

  /**
   * The shield block.
   *
   * @param maxHealth {@code MaxShieldHealth}
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Shield(@Nullable Double maxHealth) {}

  /**
   * The quantum drive block.
   *
   * @param driveSpeed {@code params.driveSpeed}, metres per second
   * @param fuelRequirement {@code quantumFuelRequirement}
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record QuantumDrive(@Nullable Double driveSpeed, @Nullable Double fuelRequirement) {}

  /**
   * The power plant block.
   *
   * @param powerOutput the amount of the first {@code Power} generation flow
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PowerPlant(@Nullable Double powerOutput) {}

  /**
   * The cooler block.
   *
   * @param coolingRate the amount of the first {@code Coolant} generation flow
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Cooler(@Nullable Double coolingRate) {}

  /**
   * The suit armour block.
   *
   * @param damageResistance the linked {@code DamageResistanceMacro}, or {@code null}
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Armor(@Nullable DamageResistance damageResistance) {}

  /**
   * The damage resistance of an armour, bound to the physical type the SC Wiki compares with.
   *
   * @param physical {@code PhysicalResistance}
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record DamageResistance(@Nullable Resistance physical) {}

  /**
   * One damage type's resistance.
   *
   * @param multiplier the share of the damage that passes
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Resistance(@Nullable Double multiplier) {}

  /**
   * The clothing block.
   *
   * @param temperatureResistanceMin the lowest tolerated temperature, degrees Celsius
   * @param temperatureResistanceMax the highest tolerated temperature, degrees Celsius
   * @param radiationCapacity {@code RadiationResistance.MaximumRadiationCapacity}
   * @param radiationDissipationRate {@code RadiationResistance.RadiationDissipationRate}
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Clothing(
      @Nullable Double temperatureResistanceMin,
      @Nullable Double temperatureResistanceMax,
      @Nullable Double radiationCapacity,
      @Nullable Double radiationDissipationRate) {}

  /**
   * The radar block.
   *
   * @param aimAssistDistanceMin {@code aimAssist.distanceMinAssignment}, metres
   * @param aimAssistDistanceMax {@code aimAssist.distanceMaxAssignment}, metres
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Radar(
      @Nullable Double aimAssistDistanceMin, @Nullable Double aimAssistDistanceMax) {}

  /**
   * The tractor beam block.
   *
   * @param maxForce the maximum force
   * @param maxDistance the maximum distance, metres
   * @param fullStrengthDistance the full-strength distance, metres
   * @param maxVolume the maximum volume
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record TractorBeam(
      @Nullable Double maxForce,
      @Nullable Double maxDistance,
      @Nullable Double fullStrengthDistance,
      @Nullable Double maxVolume) {}

  /**
   * The attachable weapon modifier block.
   *
   * @param salvage the first {@code SSalvageModifier}, or {@code null}
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record WeaponModifier(@Nullable Salvage salvage) {}

  /**
   * A salvage modifier.
   *
   * @param radiusMultiplier the scraper radius multiplier
   * @param salvageSpeedMultiplier the scraper speed multiplier
   * @param extractionEfficiency the extraction efficiency, a fraction of one
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Salvage(
      @Nullable Double radiusMultiplier,
      @Nullable Double salvageSpeedMultiplier,
      @Nullable Double extractionEfficiency) {}
}
