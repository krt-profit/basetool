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
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The ship component types whose size and grade are meaningful, keyed by the SC Wiki item type
 * (REQ-INV-061).
 *
 * <p>A wiki type outside this set (paints, flair, controllers, FPS gear, …) is no component.
 */
@Getter
@RequiredArgsConstructor
public enum ShipComponentType {

  /** Shield generator. */
  SHIELD("Shield"),

  /** Cooler. */
  COOLER("Cooler"),

  /** Power plant. */
  POWER_PLANT("PowerPlant"),

  /** Quantum drive. */
  QUANTUM_DRIVE("QuantumDrive"),

  /** Jump drive. */
  JUMP_DRIVE("JumpDrive"),

  /** Radar. */
  RADAR("Radar"),

  /** Ship-mounted gun. */
  WEAPON_GUN("WeaponGun"),

  /** Mining laser. */
  WEAPON_MINING("WeaponMining"),

  /** Missile rack. */
  MISSILE_LAUNCHER("MissileLauncher"),

  /** Missile. */
  MISSILE("Missile"),

  /** Bomb rack. */
  BOMB_LAUNCHER("BombLauncher"),

  /** Bomb. */
  BOMB("Bomb"),

  /** Turret. */
  TURRET("Turret"),

  /** EMP generator. */
  EMP("EMP"),

  /** Quantum interdiction generator. */
  QUANTUM_INTERDICTION_GENERATOR("QuantumInterdictionGenerator"),

  /** Tractor beam. */
  TRACTOR_BEAM("TractorBeam"),

  /** Towing beam. */
  TOWING_BEAM("TowingBeam"),

  /** Salvage head. */
  SALVAGE_HEAD("SalvageHead"),

  /** Life support generator. */
  LIFE_SUPPORT_GENERATOR("LifeSupportGenerator");

  private static final Map<String, ShipComponentType> BY_WIKI_TYPE =
      Arrays.stream(values())
          .collect(
              Collectors.toUnmodifiableMap(ShipComponentType::getWikiType, Function.identity()));

  /** The SC Wiki item {@code type} value, matched case-sensitively. */
  @NotNull private final String wikiType;

  /**
   * Resolves a game item's wiki type to its component type.
   *
   * @param wikiType the {@code GameItem#wikiType}, may be {@code null}
   * @return the component type, or {@code null} when the wiki type is absent or no component
   */
  public static @Nullable ShipComponentType fromWikiType(@Nullable String wikiType) {
    return wikiType == null ? null : BY_WIKI_TYPE.get(wikiType);
  }
}
