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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link ShipComponentType}, the wiki-type to component-type mapping (REQ-INV-061).
 */
class ShipComponentTypeTest {

  @ParameterizedTest
  @CsvSource({
    "Shield, SHIELD",
    "Cooler, COOLER",
    "PowerPlant, POWER_PLANT",
    "QuantumDrive, QUANTUM_DRIVE",
    "Radar, RADAR",
    "WeaponGun, WEAPON_GUN",
    "MissileLauncher, MISSILE_LAUNCHER",
    "QuantumInterdictionGenerator, QUANTUM_INTERDICTION_GENERATOR",
    "EMP, EMP"
  })
  void knownWikiType_mapsToItsComponentType(String wikiType, ShipComponentType expected) {
    assertThat(ShipComponentType.fromWikiType(wikiType)).isEqualTo(expected);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Paints",
        "Flair_Surface",
        "Flair_Wall",
        "FlightController",
        "FuelTank",
        "Char_Armor_Helmet",
        "WeaponPersonal",
        "shield",
        "SHIELD"
      })
  void nonComponentOrUnknownWikiType_mapsToNull(String wikiType) {
    assertThat(ShipComponentType.fromWikiType(wikiType)).isNull();
  }

  @ParameterizedTest
  @NullAndEmptySource
  void absentWikiType_mapsToNull(String wikiType) {
    assertThat(ShipComponentType.fromWikiType(wikiType)).isNull();
  }

  @Test
  void everyConstant_roundTripsThroughItsWikiType() {
    Arrays.stream(ShipComponentType.values())
        .forEach(
            type -> assertThat(ShipComponentType.fromWikiType(type.getWikiType())).isEqualTo(type));
  }
}
