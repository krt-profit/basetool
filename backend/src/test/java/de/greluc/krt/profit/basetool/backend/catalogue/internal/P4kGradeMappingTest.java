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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The grade letter of a P4K item, read from its raw grade and type (REQ-P4K-018). */
class P4kGradeMappingTest {

  @ParameterizedTest
  @CsvSource({
    "Cooler, 1, A",
    "PowerPlant, 2, B",
    "Shield, 3, C",
    "QuantumDrive, 4, D",
    "Radar, 1, A",
    "shield, 3, C"
  })
  void aComponentTypeMapsOneToFourOntoAToD(String type, int raw, String letter) {
    assertThat(P4kGradeMapping.letter(type, raw)).isEqualTo(letter);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "WeaponPersonal",
        "Char_Armor_Arms",
        "JumpDrive",
        "LifeSupportGenerator",
        "QuantumInterdictionGenerator",
        "Misc",
        "WeaponMining",
        "WeaponAttachment"
      })
  void everyOtherTypeHasNoLetterFromTheP4k(String type) {
    assertThat(P4kGradeMapping.letter(type, 1)).isNull();
    assertThat(P4kGradeMapping.letter(type, 3)).isNull();
  }

  @Test
  void aGradeOutsideOneToFourHasNoLetterOnAComponentType() {
    assertThat(P4kGradeMapping.letter("Shield", 6)).isNull();
    assertThat(P4kGradeMapping.letter("Shield", 0)).isNull();
  }

  @Test
  void aMissingTypeOrGradeHasNoLetter() {
    assertThat(P4kGradeMapping.letter(null, 2)).isNull();
    assertThat(P4kGradeMapping.letter("Cooler", null)).isNull();
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 5, 6, 7, 12})
  void aGradeOutsideOneToFourIsUnknown(int raw) {
    assertThat(P4kGradeMapping.isUnknown(raw)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 4})
  void aGradeOfOneToFourIsKnown(int raw) {
    assertThat(P4kGradeMapping.isUnknown(raw)).isFalse();
  }

  @Test
  void noGradeIsNotUnknown() {
    assertThat(P4kGradeMapping.isUnknown(null)).isFalse();
  }
}
