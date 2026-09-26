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

package de.greluc.krt.profit.basetool.frontend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests für die Farbklassen-Logik der Einheitsboxen im Einheiten-Panel der Einsatz-Detailseite.
 * Stellt sicher, dass der Index-Modulo die korrekte CSS-Klasse ergibt und die Palette mindestens 20
 * Farben umfasst.
 */
class MissionUnitColorIndexTest {

  /** Anzahl der definierten Farbklassen in styles.css */
  static final int PALETTE_SIZE = 24;

  /**
   * Berechnet die CSS-Klasse für eine Einheitsbox anhand des Thymeleaf-Loop-Index, analog zur
   * Template-Logik: {@code 'unit-color-' + (iterStat.index % PALETTE_SIZE)}.
   */
  static String unitColorClass(int index) {
    return "unit-color-" + (index % PALETTE_SIZE);
  }

  @Test
  void paletteSizeShouldBeAtLeast20() {
    Set<String> distinctClasses =
        IntStream.range(0, 20)
            .mapToObj(MissionUnitColorIndexTest::unitColorClass)
            .collect(Collectors.toSet());
    assertEquals(
        20,
        distinctClasses.size(),
        "Die Farbpalette muss mindestens 20 Farben umfassen; Indizes 0..19 erzeugten nur "
            + distinctClasses.size()
            + " unterschiedliche Klassen (PALETTE_SIZE="
            + PALETTE_SIZE
            + ").");
  }

  @ParameterizedTest(name = "Index {0} -> unit-color-{1}")
  @CsvSource({"0,  0", "1,  1", "23, 23", "24, 0", "25, 1", "47, 23", "48, 0", "100, 4"})
  void colorClassShouldWrapAroundPaletteSize(int index, int expectedColorIndex) {
    String expectedClass = "unit-color-" + expectedColorIndex;

    String actualClass = unitColorClass(index);

    assertEquals(
        expectedClass,
        actualClass,
        "Für Index " + index + " wird Klasse '" + expectedClass + "' erwartet.");
  }

  @Test
  void firstTwentyIndicesShouldProduceDistinctClasses() {
    int count = 20;

    for (int i = 0; i < count; i++) {
      for (int j = i + 1; j < count; j++) {
        String classI = unitColorClass(i);
        String classJ = unitColorClass(j);
        assertTrue(
            !classI.equals(classJ),
            "Index " + i + " und " + j + " dürfen nicht dieselbe Klasse ergeben: " + classI);
      }
    }
  }
}
