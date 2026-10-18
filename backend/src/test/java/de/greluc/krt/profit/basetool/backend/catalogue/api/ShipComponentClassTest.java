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

/** Unit tests for {@link ShipComponentClass}, the wiki class mapping (REQ-DATA-024). */
class ShipComponentClassTest {

  @ParameterizedTest
  @CsvSource({
    "Military, MILITARY",
    "Civilian, CIVILIAN",
    "Industrial, INDUSTRIAL",
    "Stealth, STEALTH",
    "Competition, COMPETITION",
    "military, MILITARY",
    "' Stealth ', STEALTH"
  })
  void knownWikiClass_mapsToItsClass(String wikiClass, ShipComponentClass expected) {
    assertThat(ShipComponentClass.fromWikiClass(wikiClass)).isEqualTo(expected);
  }

  @ParameterizedTest
  @ValueSource(strings = {"Prototype", "Militär", "MIL", "Military Grade"})
  void unknownWikiClass_mapsToNull(String wikiClass) {
    assertThat(ShipComponentClass.fromWikiClass(wikiClass)).isNull();
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"  "})
  void absentWikiClass_mapsToNull(String wikiClass) {
    assertThat(ShipComponentClass.fromWikiClass(wikiClass)).isNull();
  }

  @Test
  void everyConstant_roundTripsThroughItsWikiClass() {
    Arrays.stream(ShipComponentClass.values())
        .forEach(c -> assertThat(ShipComponentClass.fromWikiClass(c.getWikiClass())).isEqualTo(c));
  }
}
